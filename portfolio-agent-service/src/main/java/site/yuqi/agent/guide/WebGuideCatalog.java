package site.yuqi.agent.guide;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Server-owned destinations available to the LLM planner. The model selects
 * stable keys; it never emits selectors or executable page content.
 */
public final class WebGuideCatalog {

    private static final Map<String, Target> TARGETS = targets();

    private WebGuideCatalog() {
    }

    public static Map<String, Target> targetsByKey() {
        return TARGETS;
    }

    public static List<String> allowedKeys() {
        return List.copyOf(TARGETS.keySet());
    }

    public static String classifierContract() {
        return """
                Interactive web guide contract:
                - Return WEB_GUIDE only when the user semantically requests interactive orientation or navigation through the portfolio website. A request that asks for portfolio facts or content remains KNOWLEDGE_QA.
                - GENERAL_CHAT is reserved for conversational turns that require no portfolio facts or content lookup.
                - If the user names, references, or follows up about a portfolio entity and wants its introduction, details, source, or link, return KNOWLEDGE_QA even when the utterance is only a short title or noun phrase.
                - Choose WEB_GUIDE only when the primary expected outcome is a UI action such as guiding, locating, highlighting, or opening an area of the website. Do not substitute a generic guide response for an information request.
                - For WEB_GUIDE use targetTool=null, riskLevel=READ_ONLY, requiresConfirmation=false, and generationTier=STANDARD.
                - Put guideTargetKeys in entities as an ordered subset of these keys: %s.
                - Put guideStartMode in entities as START_NOW or OFFER. Choose it from the user's intent and conversational context.
                - Put guideResponseMessage in entities as a short user-facing message written in the same language as the current user input.
                - Do not create selectors, URLs, HTML, scripts, or target keys outside this catalog.
                """.formatted(String.join(", ", allowedKeys()));
    }

    private static Map<String, Target> targets() {
        LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
        targets.put("home.about", new Target(
                "home.about", "/", "tour-about", "/#tour-about",
                new LocalizedCard("About Yuqi", "Meet Yuqi and see the engineering focus behind this portfolio."),
                new LocalizedCard("关于 Yuqi", "了解 Yuqi，以及这个作品集所体现的工程方向。")));
        targets.put("home.background", new Target(
                "home.background", "/", "tour-background", "/#tour-background",
                new LocalizedCard("Background", "Review education, experience, and the technical domains Yuqi has worked in."),
                new LocalizedCard("经历背景", "查看教育、工作经历，以及 Yuqi 深入实践的技术领域。")));
        targets.put("home.projects", new Target(
                "home.projects", "/", "tour-projects", "/#tour-projects",
                new LocalizedCard("Projects", "Explore production-minded systems, architecture decisions, and implementation details."),
                new LocalizedCard("项目作品", "浏览生产级系统、架构决策和具体实现。")));
        targets.put("home.techBlogs", new Target(
                "home.techBlogs", "/", "tour-techblogs", "/#tour-techblogs",
                new LocalizedCard("Technical blogs", "Read engineering deep dives, system design notes, and implementation write-ups."),
                new LocalizedCard("技术博客", "阅读工程深度分析、系统设计笔记和实现文章。")));
        targets.put("home.lifeBlogs", new Target(
                "home.lifeBlogs", "/", "tour-life", "/#tour-life",
                new LocalizedCard("Life blogs", "Discover interests and experiences outside day-to-day engineering work."),
                new LocalizedCard("生活博客", "了解工程工作之外的兴趣与经历。")));
        targets.put("home.dashboard", new Target(
                "home.dashboard", "/", "tour-real-time-data", "/#tour-real-time-data",
                new LocalizedCard("Live platform dashboard", "Inspect real-time data, visitor insights, and microservice health signals."),
                new LocalizedCard("实时平台面板", "查看实时数据、访客洞察和微服务健康信号。")));
        targets.put("home.contact", new Target(
                "home.contact", "/", "tour-contact", "/#tour-contact",
                new LocalizedCard("Contact", "Use the contact form to reach Yuqi about engineering roles or collaboration."),
                new LocalizedCard("联系 Yuqi", "通过联系表单沟通工程职位或合作机会。")));
        return Collections.unmodifiableMap(targets);
    }

    public record Target(
            String key,
            String route,
            String targetId,
            String href,
            LocalizedCard english,
            LocalizedCard chinese) {

        public LocalizedCard cardFor(String language) {
            return language != null && language.toLowerCase().startsWith("zh") ? chinese : english;
        }
    }

    public record LocalizedCard(String title, String content) {
    }
}
