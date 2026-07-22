package org.linxing.linxing_agent.agent.memory.longterm.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * History 自动归档器：检测 {@code Learning/Current.md} 的学习主题切换，将旧阶段固化到 {@code History/}。
 *
 * <p>归档时机：在 {@link MemoryWorkerService} 用 LLM 产出的新内容覆盖 {@code Current.md} 之前调用
 * {@link #archiveIfStageSwitched}——检测旧/新 Topic 是否变化，若变化则先把旧 Current 写入
 * {@code History/{旧主题}.md}（带完成时间），再把新内容覆盖 Current。
 *
 * <p>归档文件内容：学习主题 / 学习成果 / 学习总结 / 完成时间，取自旧 Current 的对应 Section。
 *
 * TODO：此功能太不成熟，还待进一步设计和完善。
 * <p>暂时弃用（2026.07.22）：调用方 WriteMemoryTool 已摘除对 archiveIfStageSwitched 的调用，
 * 本类作为孤立 Bean 保留（无人注入），待设计完善后在 WriteMemoryTool.execute 内按 TODO 标记恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryArchiver {

    private static final String HISTORY_DIR = "History";
    private static final String TRASH_DIR = "History/.trash";
    private static final String TOPIC_HEADER = "## Topic";
    private static final String PLAN_HEADER = "## Plan";
    private static final String NEXT_GOAL_HEADER = "## Next Goal";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MemoryWorkspace memoryWorkspace;

    /**
     * 检测旧/新 Current 的 Topic 是否切换；若切换，先归档旧 Current，返回 true（调用方随后覆盖 Current 为新内容）。
     * <p>无切换、Topic 解析失败、新内容无 Topic（异常）时均返回 false（不归档，不阻断覆盖）。
     *
     * @param userId        用户 ID
     * @param oldCurrent     旧 Current.md 全文（覆盖前）
     * @param newCurrentText 新 Current.md 全文（LLM 产出，即将覆盖）
     * @return 是否已执行归档
     */
    public boolean archiveIfStageSwitched(Integer userId, String oldCurrent, String newCurrentText) {
        String oldTopic = extractTopic(oldCurrent);
        String newTopic = extractTopic(newCurrentText);
        if (oldTopic == null || oldTopic.isBlank() || newTopic == null || newTopic.isBlank()) {
            // 旧或新无有效 Topic：不判定切换，避免误归档（含首阶段 Topic 尚未填写的情况）
            return false;
        }
        if (oldTopic.equalsIgnoreCase(newTopic)) {
            return false;//同主题：仅内容更新，不归档
        }
        // 阶段切换：归档旧 Current
        archive(userId, oldTopic, oldCurrent);
        return true;
    }

    /**
     * 将旧 Current 固化到 History/{topic}.md，并备份到 History/.trash/。
     */
    private void archive(Integer userId, String oldTopic, String oldCurrent) {
        String safeName = sanitizeFileName(oldTopic);
        String archivePath = HISTORY_DIR + "/" + safeName + ".md";
        String archiveContent = buildArchiveContent(oldTopic, oldCurrent);
        // 兜底备份：防止误判丢 Current
        String trashPath = TRASH_DIR + "/" + safeName + "-" + OffsetDateTime.now().format(FILE_TS) + ".md";
        try {
            memoryWorkspace.write(userId, archivePath, archiveContent);
            memoryWorkspace.write(userId, trashPath, oldCurrent);
            log.info("[HistoryArchiver] 学习阶段归档 userId={} topic={} -> {}", userId, oldTopic, archivePath);
        } catch (MemoryAccessException e) {
            log.warn("[HistoryArchiver] 归档失败 userId={} topic={}: {}", userId, oldTopic, e.getMessage());
        }
    }

    /**
     * 构建归档文件内容：学习主题/学习成果/学习总结/完成时间，取自旧 Current 的 Plan/Next Goal 等 Section。
     */
    private String buildArchiveContent(String topic, String oldCurrent) {
        String plan = extractSectionBody(oldCurrent, PLAN_HEADER);
        String nextGoal = extractSectionBody(oldCurrent, NEXT_GOAL_HEADER);
        String completedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        StringBuilder sb = new StringBuilder();
        sb.append("# History: ").append(topic).append("\n\n");
        sb.append("## 学习主题\n").append(topic).append("\n\n");
        sb.append("## 学习成果\n").append(plan.isBlank() ? "(无)" : plan).append("\n\n");
        sb.append("## 学习总结\n").append(nextGoal.isBlank() ? "(无)" : nextGoal).append("\n\n");
        sb.append("## 完成时间\n").append(completedAt).append("\n");
        return sb.toString();
    }

    /**
     * 从 Current.md 提取 Topic：首个 {@code ## Topic} 之后到下一个二级标题前的首段非空文本。
     */
    private static String extractTopic(String current) {
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
    private static String extractSectionBody(String md, String header) {
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
    private static String sanitizeFileName(String topic) {
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
