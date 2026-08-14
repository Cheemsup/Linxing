package org.linxing.linxing_agent.agent.memory.longterm.tool;

import com.fasterxml.jackson.annotation.JsonAlias;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按关键词检索已完成学习阶段的历史归档（决策 6：专门的历史检索通道）。
 *
 * <p>扫描 {@code History/} 下全部 .md 归档（含 {@code _merged.md}，排除 {@code .raw/} 与 {@code .trash/} 段），
 * 关键词大小写不敏感子串匹配全文，返回命中文件的路径与内容片段。
 * <p>keyword 为空时返回最近归档路径列表（按路径降序 = 最新月份优先）。
 * <p>历史内容不再注入上下文【长期记忆】段（决策 6），改由本工具按需检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchHistoryTool implements Tool {

    private static final String NAME = "search_history";
    private static final String BRIEF = "检索历史学习归档";
    private static final String DISPLAY_LABEL = "检索历史记忆";
    private static final String WHEN_TO_USE = "当需要回顾已完成的学习阶段、查找历史学习主题或总结时使用";
    private static final String DESCRIPTION = "按关键词检索已完成学习阶段的历史归档。"
            + "扫描 History/{yyyy-MM}/*.md（含 _merged.md，排除 .raw/ 与 .trash/），"
            + "返回命中的归档文件路径与内容片段。keyword 为空则返回最近归档列表。";
    private static final String HISTORY_PREFIX = "History/";
    private static final int DEFAULT_LIMIT = 10;
    /** 片段长度上限（首个命中处前后截断） */
    private static final int SNIPPET_MAX = 500;

    private final MemoryWorkspace memoryWorkspace;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String brief() {
        return BRIEF;
    }

    @Override
    public String whenToUse() {
        return WHEN_TO_USE;
    }

    @Override
    public String displayLabel() {
        return DISPLAY_LABEL;
    }

    @Override
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder()
                .addProperty("keyword",
                        JsonStringSchema.builder()
                                .description("关键词（主题/学习成果等）。可选；为空则返回最近归档列表。")
                                .build())
                .addProperty("limit",
                        JsonIntegerSchema.builder()
                                .description("最大返回条数，默认 10")
                                .build())
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        Integer userId = context.getUserId();
        SearchArgs args;
        try {
            args = objectMapper.readValue(request.getArguments(), SearchArgs.class);
        } catch (Exception e) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "参数解析失败: " + e.getMessage());
        }
        int limit = args.getLimit() == null || args.getLimit() <= 0 ? DEFAULT_LIMIT : args.getLimit();
        String keyword = args.getKeyword() == null ? "" : args.getKeyword().trim();

        try {
            memoryWorkspace.initUserWorkspaceIfAbsent(userId);
            List<String> allFiles = memoryWorkspace.list(userId);
            // 仅保留 History/ 下归档文件，排除 .raw / .trash 段
            List<String> historyFiles = new ArrayList<>();
            for (String path : allFiles) {
                if (isSearchableHistory(path)) {
                    historyFiles.add(path);
                }
            }
            // 按路径降序（最新月份优先）
            historyFiles.sort(Comparator.reverseOrder());

            if (keyword.isBlank()) {
                // 无关键词：返回最近归档路径列表
                List<String> recent = historyFiles.size() > limit
                        ? historyFiles.subList(0, limit) : historyFiles;
                String result = recent.isEmpty()
                        ? "历史归档为空。"
                        : "最近历史归档：\n" + String.join("\n", recent);
                return ToolCallResult.success(request.getToolCallId(), NAME, result);
            }

            // 关键词命中：返回路径 + 片段
            StringBuilder sb = new StringBuilder();
            int hits = 0;
            String lowerKw = keyword.toLowerCase();
            for (String path : historyFiles) {
                if (hits >= limit) {
                    break;
                }
                String content = safeRead(userId, path);
                if (content.isBlank()) {
                    continue;
                }
                int idx = content.toLowerCase().indexOf(lowerKw);
                if (idx < 0) {
                    continue;
                }
                String snippet = snippetAround(content, idx, keyword);
                sb.append(path).append("\n---\n").append(snippet).append("\n\n");
                hits++;
            }
            String result = hits == 0
                    ? "未命中关键词「" + keyword + "」的历史归档。"
                    : "命中 " + hits + " 条历史归档（关键词「" + keyword + "」）：\n\n" + sb;
            return ToolCallResult.success(request.getToolCallId(), NAME, result);
        } catch (MemoryAccessException e) {
            log.warn("[SearchHistoryTool] 检索失败 userId={}: {}", userId, e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, "检索历史归档失败: " + e.getMessage());
        }
    }

    /**
     * 判定是否为可检索的历史归档：History/ 开头、.md 结尾、不含 .raw / .trash 段。
     */
    private static boolean isSearchableHistory(String path) {
        if (path == null || !path.startsWith(HISTORY_PREFIX) || !path.endsWith(".md")) {
            return false;
        }
        for (String seg : path.split("/")) {
            if (".raw".equals(seg) || ".trash".equals(seg)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取首个命中处前后的片段，截断至 {@link #SNIPPET_MAX}。
     */
    private static String snippetAround(String content, int hitIdx, String keyword) {
        int half = Math.max(SNIPPET_MAX - keyword.length(), 0) / 2;
        int start = Math.max(0, hitIdx - half);
        int end = Math.min(content.length(), hitIdx + keyword.length() + half);
        String snippet = content.substring(start, end).replace("\n", " ").trim();
        if (snippet.length() > SNIPPET_MAX) {
            snippet = snippet.substring(0, SNIPPET_MAX) + "…";
        }
        return (start > 0 ? "…" : "") + snippet + (end < content.length() ? "…" : "");
    }

    private String safeRead(Integer userId, String path) {
        try {
            return memoryWorkspace.read(userId, path);
        } catch (MemoryAccessException e) {
            log.warn("[SearchHistoryTool] 读取失败 userId={} path={}: {}", userId, path, e.getMessage());
            return "";
        }
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class SearchArgs {
        @JsonAlias({"query", "q"})
        private String keyword;
        private Integer limit;
    }
}
