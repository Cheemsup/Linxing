package org.linxing.linxing_agent.agent.memory.longterm.injector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Long-term Memory 常驻段装配。
 * <p>供 {@code DefaultContextBuilder} 在 System Prompt 装配阶段调用：
 * <ul>
 *   <li>{@link #buildResidentSection(Integer)}：产出【长期记忆】段（Directory 全文 + Agent/User/Current 头部摘要）。</li>
 * </ul>
 *
 * <p>2026.08.06 改造（决策 4+6）：
 * <ul>
 *   <li>Current 由单文件改为多主题目录 {@code Learning/Current/{topic}.md}，摘要聚合全部主题文件。</li>
 *   <li>移除 History 元信息段——历史内容不再注入上下文，改由 {@code search_history} 工具按需检索。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LongMemoryInjector {

    /** Agent/User/Current 头部摘要的字符上限（截断，避免 prompt 膨胀） */
    private static final int HEAD_SUMMARY_MAX_CHARS = 600;
    /** Current 多主题目录（相对用户根） */
    private static final String CURRENT_DIR = "Learning/Current";

    private static final String AGENT_MD = "Agent.md";
    private static final String USER_MD = "User.md";
    private static final String DIRECTORY_MD = "Directory.md";

    private final MemoryWorkspace memoryWorkspace;

    /**
     * 构建【长期记忆】常驻段。userId 为空或 Memory 读取失败时返回空串（降级跳过）。
     */
    public String buildResidentSection(Integer userId) {
        if (userId == null) {
            return "";
        }
        try {
            memoryWorkspace.initUserWorkspaceIfAbsent(userId);
        } catch (MemoryAccessException e) {
            log.warn("[LongMemoryInjector] 初始化用户 Memory 失败 userId={}: {}", userId, e.getMessage());
            return "";
        }
        // 用 LinkedHashMap 保序，并自动去重（@ 引用可能重复命中同一文件）
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put(AGENT_MD, headSummary(safeRead(userId, AGENT_MD)));
        parts.put(USER_MD, headSummary(safeRead(userId, USER_MD)));
        String currentSummary = buildCurrentTopicsSummary(userId);
        parts.put("Current", currentSummary);
        // Directory 全文注入
        String directory = safeRead(userId, DIRECTORY_MD);
        if (directory.isBlank() && parts.values().stream().allMatch(v -> v == null || v.isBlank())) {
            return "";//全部为空：不产出段
        }

        StringBuilder sb = new StringBuilder("【长期记忆】\n");
        sb.append("Agent 设定（Agent.md 摘要）：\n").append(parts.get(AGENT_MD)).append("\n\n");
        sb.append("用户偏好（User.md 摘要）：\n").append(parts.get(USER_MD)).append("\n\n");
        sb.append("当前学习状态（Learning/Current/ 多主题摘要）：\n").append(currentSummary).append("\n\n");
        // Directory.md 全文注入（导航骨架 Agent/User/Learning 部分）
        sb.append("Memory 目录（Directory.md 全文）：\n").append(directory).append("\n\n");
        // History 部分不再注入元信息段（决策 6）：历史检索改由 search_history 工具按需进行
        sb.append("如需查看某记忆文件全文，请使用 @相对路径 标记标注，或直接调用 read_memory 工具读取。"
                + "检索已完成学习阶段的历史归档，请调用 search_history 工具。");
        return sb.toString();
    }

    /**
     * 聚合 {@code Learning/Current/} 下全部主题文件的头部摘要（决策 4：多主题，最多 3 个）。
     * <p>每文件取首个 Section 摘要（frontmatter 在首个 {@code ## } 之前，{@link #headSummary} 正确跳过）。
     * 空目录返回占位文本。
     */
    private String buildCurrentTopicsSummary(Integer userId) {
        List<String> currentFiles;
        try {
            currentFiles = memoryWorkspace.list(userId).stream()
                    .filter(LongMemoryInjector::isTopicFile)
                    .sorted()
                    .toList();
        } catch (MemoryAccessException e) {
            log.warn("[LongMemoryInjector] 列出 Current 主题失败 userId={}: {}", userId, e.getMessage());
            return "(无当前学习主题)";
        }
        if (currentFiles.isEmpty()) {
            return "(无当前学习主题)";
        }
        StringBuilder sb = new StringBuilder();
        for (String path : currentFiles) {
            String content = safeRead(userId, path);
            String topic = headSummary(content);
            String topicFileName = path.substring((CURRENT_DIR + "/").length());
            sb.append("- ").append(topicFileName).append(": ").append(topic).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 判定是否为真实主题文件：{@code Learning/Current/} 下 .md 且文件名不以 {@code _} 开头
     * （{@code _template.md} 结构样板不计入主题摘要）。
     */
    private static boolean isTopicFile(String path) {
        if (!path.startsWith(CURRENT_DIR + "/") || !path.endsWith(".md")) {
            return false;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return !name.startsWith("_");
    }

    /**
     * 安全读取：失败仅告警返回空串，不抛异常。
     */
    private String safeRead(Integer userId, String relativePath) {
        try {
            return memoryWorkspace.read(userId, relativePath);
        } catch (MemoryAccessException e) {
            log.warn("[LongMemoryInjector] 读取 Memory 失败 userId={} path={}: {}", userId, relativePath, e.getMessage());
            return "";
        }
    }

    /**
     * 头部摘要：取首个非空 Section 内容，截断至 {@link #HEAD_SUMMARY_MAX_CHARS}。
     * <p>V1 最小摘录规则：取首个二级标题（## ）之后到下一个二级标题之前的内容；若无结构则取开头。
     */
    private static String headSummary(String content) {
        if (content == null || content.isBlank()) {
            return "(空)";
        }
        String trimmed = content.trim();
        int firstH2 = trimmed.indexOf("\n## ");
        if (firstH2 < 0) {
            return truncate(trimmed);
        }
        int bodyStart = firstH2 + "\n## ".length();
        int nextH2 = trimmed.indexOf("\n## ", bodyStart);
        String section = nextH2 < 0
                ? trimmed.substring(bodyStart)
                : trimmed.substring(bodyStart, nextH2);
        return truncate(section.trim());
    }

    private static String truncate(String s) {
        if (s.length() <= HEAD_SUMMARY_MAX_CHARS) {
            return s;
        }
        return s.substring(0, HEAD_SUMMARY_MAX_CHARS) + "…(已截断)";
    }
}
