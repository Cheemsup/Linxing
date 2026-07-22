package org.linxing.linxing_agent.agent.memory.longterm.injector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Long-term Memory 常驻段装配。
 * <p>供 {@code DefaultContextBuilder} 在 System Prompt 装配阶段调用：
 * <ul>
 *   <li>{@link #buildResidentSection(Integer)}：产出【长期记忆】段（Directory 全文 + Agent/User/Current 头部摘要）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LongMemoryInjector {

    /** Agent/User/Current 头部摘要的字符上限（截断，避免 prompt 膨胀） */
    private static final int HEAD_SUMMARY_MAX_CHARS = 600;

    private static final String AGENT_MD = "Agent.md";
    private static final String USER_MD = "User.md";
    private static final String CURRENT_MD = "Learning/Current.md";
    private static final String DIRECTORY_MD = "Directory.md";

    private final MemoryWorkspace memoryWorkspace;
    private final HistoryMetaScanner historyMetaScanner;

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
        parts.put(CURRENT_MD, headSummary(safeRead(userId, CURRENT_MD)));
        // Directory 全文注入
        String directory = safeRead(userId, DIRECTORY_MD);
        if (directory.isBlank() && parts.values().stream().allMatch(v -> v == null || v.isBlank())) {
            return "";//全部为空：不产出段
        }

        StringBuilder sb = new StringBuilder("【长期记忆】\n");
        sb.append("Agent 设定（Agent.md 摘要）：\n").append(parts.get(AGENT_MD)).append("\n\n");
        sb.append("用户偏好（User.md 摘要）：\n").append(parts.get(USER_MD)).append("\n\n");
        sb.append("当前学习状态（Learning/Current.md 摘要）：\n").append(parts.get(CURRENT_MD)).append("\n\n");
        // Directory.md 全文注入（导航骨架 Agent/User/Learning 部分）
        sb.append("Memory 目录（Directory.md 全文）：\n").append(directory).append("\n\n");
        // History 部分由 HistoryMetaScanner 运行时实时扫描归档文件注入元信息
        String historyMeta = historyMetaScanner.scanHistoryMetaSection(userId);
        if (!historyMeta.isBlank()) {
            sb.append(historyMeta);
        }
        sb.append("\n如需查看某记忆文件全文，请使用 @相对路径 标记标注，或直接调用 read_memory 工具读取。");
        return sb.toString();
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
