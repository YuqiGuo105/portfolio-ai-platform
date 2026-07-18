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
import java.util.List;

@Slf4j
@Service
public class ChatBudgetService {

    private static final long USD_MICROS = 1_000_000L;
    private static final String KEY_PREFIX = "agent:budget:chat:daily:";
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

    @Value("${agent.budget.zone:UTC}")
    private String budgetZone;

    public ChatBudgetService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public BudgetDecision reserveChatRequest() {
        return reserve(perRequestReservationUsd);
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
        return new BudgetWindow(
                KEY_PREFIX + date.format(KEY_DATE),
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

    private record BudgetWindow(String key, Instant resetAt, long ttlSeconds) {}
}
