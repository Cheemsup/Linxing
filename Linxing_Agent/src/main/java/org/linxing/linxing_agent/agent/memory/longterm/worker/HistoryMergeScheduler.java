package org.linxing.linxing_agent.agent.memory.longterm.worker;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryFileWriter;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * History 每周合并调度器（决策 2+3：按月分级 + cron 每周合并简写）。
 *
 * <p>每周一 07:23（自然周 Mon-Sun，避开 :00 整点）触发，合并本月 {@code History/{yyyy-MM}/} 下的原始归档：
 * <ul>
 *   <li>读全部原始归档（排除 {@code .raw/} 子目录与 {@code _merged.md}）。</li>
 *   <li>LLM 合并简写为单一 {@code _merged.md}（保留关键主题/成果，压缩冗余）。</li>
 *   <li>已合并的原始归档移入 {@code .raw/} 保留，避免重复合并导致信息衰减。</li>
 *   <li>幂等：无原始归档可合并（已全部在 {@code .raw/}）时跳过。</li>
 * </ul>
 *
 * <p>跨用户迭代：遍历 {@code rootDir} 下全部用户目录，逐个合并。longterm 包首个跨用户操作，
 * 串行执行——TODO：用户量大时考虑并行化（镜像 {@code MemoryWorkerAsyncConfig} 的跨用户 TODO）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryMergeScheduler {

    private static final String HISTORY_DIR = "History";
    private static final String MERGED_FILE = HistoryArchiver.MERGED_FILE;
    private static final String RAW_DIR = HistoryArchiver.RAW_DIR;

    private static final String MERGE_SYSTEM_PROMPT =
            "你是历史学习归档合并器。将多份已完成学习阶段的归档合并为一份简明摘要，"
                    + "保留各阶段的关键学习主题、核心成果与总结，压缩重复与冗余信息。"
                    + "输出为 Markdown，按时间或主题组织，不要逐条堆砌原文。";

    private final MemoryWorkspace memoryWorkspace;
    private final MemoryFileWriter memoryFileWriter;
    private final LlmManager llmManager;

    /**
     * 每周一 07:23 合并本月历史归档（自然周，避开 :00 整点）。
     * <p>跨用户遍历，逐个调用 {@link #mergeMonthForUser}。
     */
    @Scheduled(cron = "0 23 7 * * 1")
    public void mergeCurrentMonthHistory() {
        log.info("[HistoryMergeScheduler] 每周合并任务启动");
        List<String> userIds = memoryWorkspace.listUserIds();
        if (userIds.isEmpty()) {
            log.info("[HistoryMergeScheduler] 无用户目录，跳过");
            return;
        }
        String yyyyMM = java.time.OffsetDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        for (String userIdStr : userIds) {
            Integer userId;
            try {
                userId = Integer.valueOf(userIdStr);
            } catch (NumberFormatException e) {
                continue;//非数字目录名跳过
            }
            try {
                mergeMonthForUser(userId, yyyyMM);
            } catch (Exception e) {
                log.error("[HistoryMergeScheduler] 合并失败 userId={} month={}: {}", userId, yyyyMM, e.getMessage(), e);
            }
        }
        log.info("[HistoryMergeScheduler] 每周合并任务结束，处理 {} 个用户", userIds.size());
    }

    /**
     * 合并指定用户指定月份的历史归档。
     * <p>幂等：无原始归档可合并时跳过。
     *
     * @param userId  用户 ID
     * @param yyyyMM  月份（如 {@code 2026-08}）
     */
    void mergeMonthForUser(Integer userId, String yyyyMM) {
        String monthDir = HISTORY_DIR + "/" + yyyyMM;
        List<String> allFiles;
        try {
            allFiles = memoryWorkspace.list(userId);
        } catch (MemoryAccessException e) {
            log.warn("[HistoryMergeScheduler] 列出文件失败 userId={}: {}", userId, e.getMessage());
            return;
        }
        // 筛选本月原始归档：在 monthDir 下、.md 结尾、排除 .raw / .trash 段、排除 _merged.md
        List<String> rawArchives = new ArrayList<>();
        boolean mergedExists = false;
        for (String path : allFiles) {
            if (!path.startsWith(monthDir + "/") || !path.endsWith(".md")) {
                continue;
            }
            if (containsSegment(path, RAW_DIR) || containsSegment(path, ".trash")) {
                continue;
            }
            if (path.endsWith("/" + MERGED_FILE)) {
                mergedExists = true;
                continue;
            }
            rawArchives.add(path);
        }
        if (rawArchives.isEmpty()) {
            // 无原始归档可合并：已合并过或本月无归档
            log.debug("[HistoryMergeScheduler] 无原始归档可合并 userId={} month={} mergedExists={}",
                    userId, yyyyMM, mergedExists);
            return;
        }
        // 读全部原始归档内容
        StringBuilder concat = new StringBuilder();
        for (String path : rawArchives) {
            String content = safeRead(userId, path);
            if (!content.isBlank()) {
                concat.append("=== ").append(path).append(" ===\n").append(content).append("\n\n");
            }
        }
        if (concat.length() == 0) {
            log.warn("[HistoryMergeScheduler] 原始归档内容均为空 userId={} month={}", userId, yyyyMM);
            return;
        }
        // LLM 合并简写
        String mergedContent;
        try {
            mergedContent = llmMerge(concat.toString());
        } catch (Exception e) {
            log.error("[HistoryMergeScheduler] LLM 合并失败 userId={} month={}: {}", userId, yyyyMM, e.getMessage(), e);
            return;
        }
        if (mergedContent == null || mergedContent.isBlank()) {
            log.warn("[HistoryMergeScheduler] LLM 合并结果为空 userId={} month={}", userId, yyyyMM);
            return;
        }
        // 写 _merged.md（强制写：系统合并，无 CAS，有原子写 + 备份）
        String mergedPath = monthDir + "/" + MERGED_FILE;
        memoryFileWriter.writeForce(userId, mergedPath, mergedContent);
        log.info("[HistoryMergeScheduler] 合并产物写入 userId={} month={} -> {}", userId, yyyyMM, mergedPath);
        // 原始归档移入 .raw/（保留，避免重复合并信息衰减）
        for (String path : rawArchives) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String rawPath = monthDir + "/" + RAW_DIR + "/" + fileName;
            try {
                memoryWorkspace.move(userId, path, rawPath);
            } catch (MemoryAccessException e) {
                log.warn("[HistoryMergeScheduler] 移动原始归档失败 userId={} {} -> {}: {}",
                        userId, path, rawPath, e.getMessage());
            }
        }
        log.info("[HistoryMergeScheduler] 合并完成 userId={} month={} 合并 {} 份原始归档",
                userId, yyyyMM, rawArchives.size());
    }

    /**
     * 调 LLM 合并归档内容。单轮 ChatRequest，非流式。
     */
    private String llmMerge(String archives) {
        OpenAiChatModel model = llmManager.getModel(LlmType.MEMORY_WORKER_MODEL);
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(MERGE_SYSTEM_PROMPT),
                        UserMessage.from("以下是本月多份历史学习归档，请合并简写为一份摘要：\n\n" + archives)))
                .build();
        ChatResponse resp = model.chat(req);
        return resp.aiMessage().text();
    }

    /**
     * 路径是否含指定段（如 {@code .raw}）。
     */
    private static boolean containsSegment(String path, String segment) {
        for (String seg : path.split("/")) {
            if (segment.equals(seg)) {
                return true;
            }
        }
        return false;
    }

    private String safeRead(Integer userId, String path) {
        try {
            return memoryWorkspace.read(userId, path);
        } catch (MemoryAccessException e) {
            log.warn("[HistoryMergeScheduler] 读取失败 userId={} path={}: {}", userId, path, e.getMessage());
            return "";
        }
    }
}
