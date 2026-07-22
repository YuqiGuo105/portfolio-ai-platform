package site.yuqi.agent.guide;

import org.springframework.stereotype.Service;
import site.yuqi.agent.intent.IntentResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates model-selected guide keys and builds the SSE payload consumed by the portfolio UI. */
@Service
public class WebGuidePlanService {

    private static final int MAX_STEPS = 7;

    public WebGuidePlan build(IntentResult intent) {
        String language = normalizeLanguage(intent == null ? null : intent.language());
        Map<String, Object> entities = intent == null ? Map.of() : intent.entities();
        List<String> selectedKeys = selectedKeys(entities.get("guideTargetKeys"));
        String startMode = normalizeStartMode(entities.get("guideStartMode"));
        String responseMessage = cleanText(entities.get("guideResponseMessage"), 240);
        if (responseMessage == null) {
            responseMessage = isChinese(language)
                    ? "我已经为你准备好网页导览，并标出了相关区域。"
                    : "I prepared a guided view of the relevant areas on the site.";
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        for (String key : selectedKeys) {
            WebGuideCatalog.Target target = WebGuideCatalog.targetsByKey().get(key);
            if (target == null) continue;
            WebGuideCatalog.LocalizedCard card = target.cardFor(language);
            LinkedHashMap<String, Object> step = new LinkedHashMap<>();
            step.put("id", key.replace('.', '-'));
            step.put("targetKey", key);
            step.put("route", target.route());
            step.put("targetId", target.targetId());
            step.put("title", card.title());
            step.put("content", card.content());
            step.put("card", Map.of(
                    "title", card.title(),
                    "content", card.content(),
                    "action", isChinese(language) ? "查看此区域" : "View this section"));
            steps.add(Map.copyOf(step));
        }

        Map<String, String> controls = isChinese(language)
                ? Map.of("start", "开始网页导览", "previous", "上一步", "next", "下一步", "done", "完成", "close", "关闭")
                : Map.of("start", "Start web guide", "previous", "Previous", "next", "Next", "done", "Done", "close", "Close");

        return new WebGuidePlan(
                language,
                "START_NOW".equals(startMode),
                startMode,
                responseMessage,
                List.copyOf(steps),
                controls);
    }

    private List<String> selectedKeys(Object raw) {
        List<String> selected = new ArrayList<>();
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                String key = cleanText(value, 64);
                if (key != null && WebGuideCatalog.targetsByKey().containsKey(key) && !selected.contains(key)) {
                    selected.add(key);
                    if (selected.size() >= MAX_STEPS) break;
                }
            }
        }
        return selected.isEmpty() ? WebGuideCatalog.allowedKeys() : List.copyOf(selected);
    }

    private static String normalizeStartMode(Object raw) {
        String value = cleanText(raw, 24);
        if (value == null) return "OFFER";
        String normalized = value.toUpperCase(Locale.ROOT);
        return "START_NOW".equals(normalized) ? "START_NOW" : "OFFER";
    }

    private static String normalizeLanguage(String language) {
        return isChinese(language) ? "zh" : "en";
    }

    private static boolean isChinese(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static String cleanText(Object raw, int maxLength) {
        if (!(raw instanceof String value)) return null;
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) return null;
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    public record WebGuidePlan(
            String language,
            boolean autoStart,
            String startMode,
            String responseMessage,
            List<Map<String, Object>> steps,
            Map<String, String> controls) {

        public Map<String, Object> toPayload() {
            return Map.of(
                    "schemaVersion", 1,
                    "language", language,
                    "autoStart", autoStart,
                    "startMode", startMode,
                    "responseMessage", responseMessage,
                    "steps", steps,
                    "highlights", steps,
                    "controls", controls);
        }
    }
}
