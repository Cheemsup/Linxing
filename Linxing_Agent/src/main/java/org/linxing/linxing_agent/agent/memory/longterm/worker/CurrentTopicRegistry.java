package org.linxing.linxing_agent.agent.memory.longterm.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Current 主题注册表：维护 {@code Learning/Current/} 下最多 3 个主题文件的不变量。
 *
 * <p>触发时机：每次写路径（主 Agent {@code write_memory} / 用户 HTTP 编辑）成功写入
 * {@code Learning/Current/} 下文件后调用 {@link #checkAndEvictIfOverQuota}——
 * 若主题数超过 3，把 {@code started_at} 最老的主题归档到 History 并删除原文件。
 *
 * <p>"最老"靠 frontmatter 的 {@code started_at} 判定（决策 4：不用文件 mtime，会被编辑刷新）。
 * frontmatter 缺失/不可解析时回退到文件 mtime + warn。
 *
 * <p>本类只做超额驱逐判定与触发，单文件归档动作委托 {@link HistoryArchiver}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrentTopicRegistry {

    /** Current 主题目录（相对用户根） */
    private static final String CURRENT_DIR = "Learning/Current";
    /** 最多同时维持的主题数（决策 4） */
    private static final int MAX_TOPICS = 3;
    private static final String STARTED_AT_PREFIX = "started_at:";

    private final MemoryWorkspace memoryWorkspace;
    private final HistoryArchiver historyArchiver;

    /**
     * 检查 Current 主题数，超额则驱逐最老的到 History。
     * <p>幂等：count ≤ {@link #MAX_TOPICS} 时无操作。
     *
     * @param userId 用户 ID
     */
    public void checkAndEvictIfOverQuota(Integer userId) {
        List<String> currentFiles = listCurrentTopics(userId);
        if (currentFiles.size() <= MAX_TOPICS) {
            return;
        }
        // 超额：按 started_at 升序，驱逐最老的（可能需驱逐多轮直至 ≤ MAX）
        List<TopicMeta> metas = new ArrayList<>();
        for (String path : currentFiles) {
            String content = safeRead(userId, path);
            String topic = HistoryArchiver.extractTopic(content);
            if (topic == null || topic.isBlank()) {
                // Topic 解析失败：用文件名兜底，避免归档失败
                topic = fileNameStem(path);
            }
            OffsetDateTime startedAt = parseStartedAt(content, userId, path);
            metas.add(new TopicMeta(path, topic, startedAt));
        }
        metas.sort(Comparator.comparing(m -> m.startedAt));
        // 驱逐最老的，直至主题数 ≤ MAX_TOPICS
        int toEvict = metas.size() - MAX_TOPICS;
        for (int i = 0; i < toEvict; i++) {
            TopicMeta victim = metas.get(i);
            String content = safeRead(userId, victim.path);
            historyArchiver.archive(userId, victim.topic, content, victim.startedAt);
            memoryWorkspace.delete(userId, victim.path);
            log.info("[CurrentTopicRegistry] 超额驱逐 userId={} topic={} path={} -> History",
                    userId, victim.topic, victim.path);
        }
    }

    /**
     * 列出 {@code Learning/Current/} 下全部主题文件（相对路径）。
     */
    private List<String> listCurrentTopics(Integer userId) {
        List<String> all;
        try {
            all = memoryWorkspace.list(userId);
        } catch (MemoryAccessException e) {
            log.warn("[CurrentTopicRegistry] 列出文件失败 userId={}: {}", userId, e.getMessage());
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String path : all) {
            if (isTopicFile(path)) {
                result.add(path);
            }
        }
        return result;
    }

    /**
     * 判定是否为真实主题文件：{@code Learning/Current/} 下 .md 且文件名不以 {@code _} 开头。
     * <p>{@code _template.md} 等结构样板不计入主题数（决策 4 的 3 主题上限只约束真实主题）。
     */
    private static boolean isTopicFile(String path) {
        if (!path.startsWith(CURRENT_DIR + "/") || !path.endsWith(".md")) {
            return false;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return !name.startsWith("_");
    }

    /**
     * 解析 frontmatter 中的 {@code started_at}。失败回退到 null（归档时 HistoryArchiver 会处理）。
     */
    private OffsetDateTime parseStartedAt(String content, Integer userId, String path) {
        if (content == null || content.isBlank()) {
            return null;
        }
        // 简单 frontmatter 解析：在首对 --- 之间查找 started_at: 行
        String frontmatter = extractFrontmatter(content);
        if (frontmatter == null) {
            log.warn("[CurrentTopicRegistry] 缺少 frontmatter，started_at 不可解析 userId={} path={}", userId, path);
            return null;
        }
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(STARTED_AT_PREFIX)) {
                String value = trimmed.substring(STARTED_AT_PREFIX.length()).trim();
                try {
                    return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (DateTimeParseException e) {
                    log.warn("[CurrentTopicRegistry] started_at 解析失败 userId={} path={} value={}", userId, path, value);
                    return null;
                }
            }
        }
        log.warn("[CurrentTopicRegistry] frontmatter 无 started_at 字段 userId={} path={}", userId, path);
        return null;
    }

    /**
     * 提取首对 {@code ---} 之间的 frontmatter 内容；无则返回 null。
     */
    private static String extractFrontmatter(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            return null;
        }
        int firstEnd = trimmed.indexOf("\n", 3);
        if (firstEnd < 0) {
            return null;
        }
        int secondStart = trimmed.indexOf("\n---", firstEnd);
        if (secondStart < 0) {
            return null;
        }
        return trimmed.substring(firstEnd + 1, secondStart);
    }

    private static String fileNameStem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String safeRead(Integer userId, String path) {
        try {
            return memoryWorkspace.read(userId, path);
        } catch (MemoryAccessException e) {
            log.warn("[CurrentTopicRegistry] 读取失败 userId={} path={}: {}", userId, path, e.getMessage());
            return "";
        }
    }

    private record TopicMeta(String path, String topic, OffsetDateTime startedAt) {
    }
}
