package site.yuqi.agent.budget;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatBudgetService {

    private static final long USD_MICROS = 1_000_000L;
    private static final String KEY_PREFIX = "agent:budget:chat:daily:";
    private static final String METRICS_KEY_PREFIX = "agent:budget:chat:daily:metrics:";
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private static final DefaultRedisScript<List> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local amount = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])
            local current = tonumber(redis.call('GET', key) or '0')
            if current + amount > limit then
              if ttl > 0 then redis.call('EXPIRE', key, ttl) end
              return {0, current, limit - current}
            end
            local updated = redis.call('INCRBY', key, amount)
            if ttl > 0 then redis.call('EXPIRE', key, ttl) end
            return {1, updated, limit - updated}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${agent.budget.enabled:true}")
    private boolean enabled;

    @Value("${agent.budget.daily-usd-limit:0.75}")
    private BigDecimal dailyUsdLimit;

    @Value("${agent.budget.per-request-reservation-usd:0.05}")
    private BigDecimal perRequestReservationUsd;

    @Value("${agent.budget.standard-model-estimate-usd:0.002}")
    private BigDecimal standardModelEstimateUsd;

    @Value("${agent.budget.deep-model-estimate-usd:0.04}")
    private BigDecimal deepModelEstimateUsd;

    @Value("${agent.budget.deep-path-enabled:true}")
    private boolean deepPathEnabled;

    @Value("${agent.budget.deep-path-min-remaining-usd:0.10}")
    private BigDecimal deepPathMinRemainingUsd;

    @Value("${agent.budget.high-cost-disable-at-ratio:0.90}")
    private BigDecimal highCostDisableAtRatio;

    @Value("${agent.budget.zone:UTC}")
    private String budgetZone;

    public ChatBudgetService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public BudgetDecision reserveChatRequest() {
        BudgetDecision decision = reserve(perRequestReservationUsd);
        recordCounter(decision.allowed() ? "chatRequests" : "budgetDeniedRequests", 1);
        return decision;
    }

    public HighCostPathDecision evaluateHighCostPath() {
        BudgetSnapshot snapshot = snapshot();
        if (!enabled) {
            return HighCostPathDecision.allowed("budget_disabled", snapshot);
        }
        if (!deepPathEnabled) {
            return HighCostPathDecision.denied("deep_path_disabled", snapshot);
        }
        if (snapshot.remainingUsd().compareTo(deepPathMinRemainingUsd) < 0) {
            return HighCostPathDecision.denied("remaining_budget_below_deep_threshold", snapshot);
        }
        if (snapshot.usageRatio().compareTo(highCostDisableAtRatio) >= 0) {
            return HighCostPathDecision.denied("daily_budget_near_limit", snapshot);
        }
        return HighCostPathDecision.allowed("within_budget", snapshot);
    }

    public BudgetSnapshot snapshot() {
        BudgetWindow window = currentWindow();
        long limitMicros = usdToMicros(dailyUsdLimit);
        long usedMicros = readLong(window.key());
        long remainingMicros = Math.max(limitMicros - usedMicros, 0L);
        Map<String, Long> counters = readMetricCounters(window.metricsKey());
        long modelCalls = counters.getOrDefault("modelCalls", 0L);
        long deepModelCalls = counters.getOrDefault("deepModelCalls", 0L);
        BigDecimal usageRatio = limitMicros <= 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(usedMicros)
                        .divide(BigDecimal.valueOf(limitMicros), 4, RoundingMode.HALF_UP);
        BigDecimal deepModelRatio = modelCalls <= 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(deepModelCalls)
                        .divide(BigDecimal.valueOf(modelCalls), 4, RoundingMode.HALF_UP);
        String mode = guardrailMode(usageRatio);
        return new BudgetSnapshot(
                window.dateKey(),
                enabled,
                microsToUsd(limitMicros),
                microsToUsd(usedMicros),
                microsToUsd(remainingMicros),
                microsToUsd(counters.getOrDefault("estimatedLlmMicros", 0L)),
                counters.getOrDefault("chatRequests", 0L),
                counters.getOrDefault("budgetDeniedRequests", 0L),
                modelCalls,
                counters.getOrDefault("standardModelCalls", 0L),
                deepModelCalls,
                deepModelRatio,
                counters.getOrDefault("toolCalls", 0L),
                counters.getOrDefault("webSearchCalls", 0L),
                counters.getOrDefault("downgradedDeepCalls", 0L),
                usageRatio,
                evaluateHighCostAllowed(mode, usageRatio, remainingMicros),
                mode,
                window.resetAt());
    }

    public void recordModelCall(String model, boolean deep, boolean webSearch) {
        Map<String, Long> increments = new LinkedHashMap<>();
        increments.put("modelCalls", 1L);
        increments.put(deep ? "deepModelCalls" : "standardModelCalls", 1L);
        increments.put("estimatedLlmMicros", usdToMicros(deep ? deepModelEstimateUsd : standardModelEstimateUsd));
        if (webSearch) increments.put("webSearchCalls", 1L);
        if (model != null && !model.isBlank()) {
            increments.put("model:" + sanitizeCounterName(model), 1L);
        }
        recordCounters(increments);
    }

    public void recordToolCall(String toolName) {
        Map<String, Long> increments = new LinkedHashMap<>();
        increments.put("toolCalls", 1L);
        if (toolName != null && !toolName.isBlank()) {
            increments.put("tool:" + sanitizeCounterName(toolName), 1L);
        }
        recordCounters(increments);
    }

    public void recordHighCostDowngrade(String reason) {
        Map<String, Long> increments = new LinkedHashMap<>();
        increments.put("downgradedDeepCalls", 1L);
        if (reason != null && !reason.isBlank()) {
            increments.put("downgrade:" + sanitizeCounterName(reason), 1L);
        }
        recordCounters(increments);
    }

    private BudgetDecision reserve(BigDecimal reservationUsd) {
        BudgetWindow window = currentWindow();
        long limitMicros = usdToMicros(dailyUsdLimit);
        long reservationMicros = usdToMicros(reservationUsd);

        if (!enabled || limitMicros <= 0 || reservationMicros <= 0) {
            return BudgetDecision.allowed(
                    microsToUsd(limitMicros),
                    BigDecimal.ZERO,
                    microsToUsd(Math.max(limitMicros, 0)),
                    microsToUsd(Math.max(reservationMicros, 0)),
                    window.resetAt());
        }

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) redisTemplate.execute(
                    RESERVE_SCRIPT,
                    List.of(window.key()),
                    String.valueOf(limitMicros),
                    String.valueOf(reservationMicros),
                    String.valueOf(window.ttlSeconds()));

            if (result == null || result.size() < 3) {
                return denyStoreUnavailable(limitMicros, reservationMicros, window);
            }

            boolean allowed = result.get(0) == 1L;
            long usedMicros = Math.max(result.get(1), 0L);
            long remainingMicros = Math.max(result.get(2), 0L);
            if (allowed) {
                return BudgetDecision.allowed(
                        microsToUsd(limitMicros),
                        microsToUsd(usedMicros),
                        microsToUsd(remainingMicros),
                        microsToUsd(reservationMicros),
                        window.resetAt());
            }
            return BudgetDecision.denied(
                    "daily_budget_exhausted",
                    microsToUsd(limitMicros),
                    microsToUsd(usedMicros),
                    microsToUsd(remainingMicros),
                    microsToUsd(reservationMicros),
                    window.resetAt());
        } catch (Exception e) {
            log.warn("Chat budget reservation failed: {}", e.toString());
            return denyStoreUnavailable(limitMicros, reservationMicros, window);
        }
    }

    private BudgetDecision denyStoreUnavailable(long limitMicros,
                                                long reservationMicros,
                                                BudgetWindow window) {
        return BudgetDecision.denied(
                "budget_store_unavailable",
                microsToUsd(limitMicros),
                BigDecimal.ZERO,
                microsToUsd(Math.max(limitMicros, 0)),
                microsToUsd(Math.max(reservationMicros, 0)),
                window.resetAt());
    }

    private BudgetWindow currentWindow() {
        ZoneId zone = safeZone(budgetZone);
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate date = now.toLocalDate();
        ZonedDateTime resetAt = date.plusDays(1).atStartOfDay(zone);
        long ttlSeconds = Math.max(Duration.between(now, resetAt).toSeconds() + 3600, 60);
        String dateKey = date.format(KEY_DATE);
        return new BudgetWindow(
                dateKey,
                KEY_PREFIX + dateKey,
                METRICS_KEY_PREFIX + dateKey,
                resetAt.toInstant(),
                ttlSeconds);
    }

    private ZoneId safeZone(String zone) {
        try {
            return ZoneId.of(zone == null || zone.isBlank() ? "UTC" : zone);
        } catch (Exception e) {
            log.warn("Invalid agent.budget.zone={}, falling back to UTC", zone);
            return ZoneId.of("UTC");
        }
    }

    private static long usdToMicros(BigDecimal usd) {
        if (usd == null) return 0L;
        return usd.multiply(BigDecimal.valueOf(USD_MICROS))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private static BigDecimal microsToUsd(long micros) {
        return BigDecimal.valueOf(micros)
                .divide(BigDecimal.valueOf(USD_MICROS), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private long readLong(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            return raw == null || raw.isBlank() ? 0L : Long.parseLong(raw);
        } catch (Exception e) {
            log.warn("Budget counter read failed key={}: {}", key, e.toString());
            return 0L;
        }
    }

    private Map<String, Long> readMetricCounters(String key) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            Map<String, Long> counters = new LinkedHashMap<>();
            entries.forEach((field, value) -> counters.put(
                    String.valueOf(field),
                    parseLong(value)));
            return counters;
        } catch (Exception e) {
            log.warn("Budget metric read failed key={}: {}", key, e.toString());
            return Map.of();
        }
    }

    private void recordCounter(String field, long increment) {
        recordCounters(Map.of(field, increment));
    }

    private void recordCounters(Map<String, Long> increments) {
        if (increments == null || increments.isEmpty()) return;
        BudgetWindow window = currentWindow();
        try {
            increments.forEach((field, increment) -> {
                if (field != null && !field.isBlank() && increment != 0) {
                    redisTemplate.opsForHash().increment(window.metricsKey(), field, increment);
                }
            });
            redisTemplate.expire(window.metricsKey(), Duration.ofSeconds(window.ttlSeconds()));
        } catch (Exception e) {
            log.warn("Budget metric write failed: {}", e.toString());
        }
    }

    private boolean evaluateHighCostAllowed(String mode, BigDecimal usageRatio, long remainingMicros) {
        if (!enabled || usdToMicros(dailyUsdLimit) <= 0) return deepPathEnabled;
        return deepPathEnabled
                && !"HARD_LIMIT".equals(mode)
                && usageRatio.compareTo(highCostDisableAtRatio) < 0
                && remainingMicros >= usdToMicros(deepPathMinRemainingUsd);
    }

    private String guardrailMode(BigDecimal usageRatio) {
        if (!enabled || usdToMicros(dailyUsdLimit) <= 0) return "DISABLED";
        if (usageRatio.compareTo(BigDecimal.ONE) >= 0) return "HARD_LIMIT";
        if (usageRatio.compareTo(highCostDisableAtRatio) >= 0) return "DEGRADED";
        return "NORMAL";
    }

    private static long parseLong(Object value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String sanitizeCounterName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public record HighCostPathDecision(boolean allowed, String reason, BudgetSnapshot snapshot) {
        public static HighCostPathDecision allowed(String reason, BudgetSnapshot snapshot) {
            return new HighCostPathDecision(true, reason, snapshot);
        }

        public static HighCostPathDecision denied(String reason, BudgetSnapshot snapshot) {
            return new HighCostPathDecision(false, reason, snapshot);
        }
    }

    public record BudgetSnapshot(
            String date,
            boolean enabled,
            BigDecimal limitUsd,
            BigDecimal reservedUsd,
            BigDecimal remainingUsd,
            BigDecimal estimatedLlmUsd,
            long chatRequests,
            long budgetDeniedRequests,
            long modelCalls,
            long standardModelCalls,
            long deepModelCalls,
            BigDecimal deepModelRatio,
            long toolCalls,
            long webSearchCalls,
            long downgradedDeepCalls,
            BigDecimal usageRatio,
            boolean highCostPathAllowed,
            String guardrailMode,
            Instant resetAt) {}

    private record BudgetWindow(String dateKey, String key, String metricsKey, Instant resetAt, long ttlSeconds) {}
}
