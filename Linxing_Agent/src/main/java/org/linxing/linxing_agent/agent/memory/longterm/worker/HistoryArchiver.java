package org.linxing.linxing_agent.agent.memory.longterm.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryFileWriter;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * History 归档器：将超额的 Current 主题归档到 {@code History/{yyyy-MM}/{topic}.md}。
 *
 * <p>归档时机：{@link CurrentTopicRegistry} 检测到 {@code Learning/Current/} 下主题数超过 3 时，
 * 把 {@code started_at} 最老的主题移入 History。本类负责单文件归档动作，
 * {@link CurrentTopicRegistry} 负责超额判定与触发。
 *
 * <p>归档路径：{@code History/{yyyy-MM}/{topic}.md}，月份取归档时刻（决策 2：归档时刻简单可排序，
 * 学习起止区间作为文件内元信息保留供合并参考）。
 *
 * <p>归档文件内容：学习主题 / 学习成果 / 学习总结 / 完成时间，取自旧 Current 的 Plan/Next Goal 等 Section。
 * header 字符串（{@code ## 学习主题} / {@code ## 完成时间}）是 {@code search_history} 等扫描的契约，保持稳定。
 *
 * <p>2026.08.06 重写：判定维度从"Topic 变化"换成"超额归档"（决策 4+5）。
 * 文本解析工具（extractTopic/extractSectionBody/sanitizeFileName）从旧版复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryArchiver {

    /** History 根目录 */
    private static final String HISTORY_DIR = "History";
    /** 已合并的原始归档存放目录（避免重复合并导致信息衰减） */
    public static final String RAW_DIR = ".raw";
    /** 每周合并产物文件名 */
    public static final String MERGED_FILE = "_merged.md";
    private static final String TOPIC_HEADER = "## Topic";
    private static final String PLAN_HEADER = "## Plan";
    private static final String NEXT_GOAL_HEADER = "## Next Goal";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final MemoryFileWriter memoryFileWriter;

    /**
     * 将一个 Current 主题归档到 {@code History/{yyyy-MM}/{topic}.md}。
     * <p>归档时刻定月份（决策 2），内容取自旧 Current 的 Plan/Next Goal 等 Section。
     * 落盘走 {@link MemoryFileWriter#writeForce}（系统归档，无 CAS，但有原子写 + 备份）。
     *
     * @param userId         用户 ID
     * @param topic          学习主题（用于文件名与归档内容）
     * @param currentContent 旧 Current 文件全文（含 frontmatter，归档时剥离）
     * @param startedAt      主题开始时间（作为文件内元信息保留，供合并参考）
     */
    public void archive(Integer userId, String topic, String currentContent, OffsetDateTime startedAt) {
        String safeName = sanitizeFileName(topic);
        OffsetDateTime now = OffsetDateTime.now();
        String monthDir = now.format(MONTH_FMT);
        String archivePath = HISTORY_DIR + "/" + monthDir + "/" + safeName + ".md";
        String archiveContent = buildArchiveContent(topic, currentContent, startedAt, now);
        memoryFileWriter.writeForce(userId, archivePath, archiveContent);
        log.info("[HistoryArchiver] 超额归档 userId={} topic={} startedAt={} -> {}",
                userId, topic, startedAt, archivePath);
    }

    /**
     * 计算指定时间所在月份的归档目录相对路径（如 {@code History/2026-08}）。
     */
    public static String monthDir(OffsetDateTime time) {
        return HISTORY_DIR + "/" + time.format(MONTH_FMT);
    }

    /**
     * 构建归档文件内容：学习主题/学习成果/学习总结/完成时间。
     * <p>学习成果取自旧 Current 的 {@code ## Plan}，学习总结取自 {@code ## Next Goal}，
     * 完成时间为归档时刻（ISO offset）。Current 的 frontmatter 不复制进 History。
     */
    private String buildArchiveContent(String topic, String oldCurrent,
                                        OffsetDateTime startedAt, OffsetDateTime completedAt) {
        String plan = extractSectionBody(oldCurrent, PLAN_HEADER);
        String nextGoal = extractSectionBody(oldCurrent, NEXT_GOAL_HEADER);
        StringBuilder sb = new StringBuilder();
        sb.append("# History: ").append(topic).append("\n\n");
        sb.append("## 学习主题\n").append(topic).append("\n\n");
        sb.append("## 学习成果\n").append(plan.isBlank() ? "(无)" : plan).append("\n\n");
        sb.append("## 学习总结\n").append(nextGoal.isBlank() ? "(无)" : nextGoal).append("\n\n");
        if (startedAt != null) {
            sb.append("## 开始时间\n").append(startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append("\n\n");
        }
        sb.append("## 完成时间\n").append(completedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append("\n");
        return sb.toString();
    }

    /**
     * 从 Current.md 提取 Topic：首个 {@code ## Topic} 之后到下一个二级标题前的首段非空文本。
     */
    public static String extractTopic(String current) {
        String body = extractSectionBody(current, TOPIC_HEADER);
        if (body == null || body.isBlank()) {
            return null;
        }
        // Topic 取首行非空文本
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (!t.isBlank() && !t.startsWith("<!--")) {
                return t;
            }
        }
        return null;
    }

    /**
     * 提取指定二级标题（如 {@code ## Plan}）之后的 Section body，到下一个二级标题或文末。
     */
    public static String extractSectionBody(String md, String header) {
        if (md == null || md.isBlank()) {
            return "";
        }
        int idx = md.indexOf(header);
        if (idx < 0) {
            return "";
        }
        int bodyStart = idx + header.length();
        int nextH2 = md.indexOf("\n## ", bodyStart);
        return nextH2 < 0 ? md.substring(bodyStart).trim() : md.substring(bodyStart, nextH2).trim();
    }

    /**
     * 文件名安全化：保留中文/字母/数字，其余字符替换为下划线。
     */
    public static String sanitizeFileName(String topic) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < topic.length(); i++) {
            char c = topic.charAt(i);
            if (Character.isLetterOrDigit(c) || isCjk(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        return result.isEmpty() ? "unnamed" : result;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }
}
