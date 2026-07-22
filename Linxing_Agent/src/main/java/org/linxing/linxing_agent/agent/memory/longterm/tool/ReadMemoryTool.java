package org.linxing.linxing_agent.agent.memory.longterm.tool;

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

/**
 * 读取指定相对路径的长期记忆 Markdown 全文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadMemoryTool implements Tool {

    private static final String NAME = "read_memory";
    private static final String BRIEF = "读取指定长期记忆文件";
    private static final String DISPLAY_LABEL = "读取长期记忆";
    private static final String WHEN_TO_USE = "当你需要读取某个长期记忆文件的完整内容时使用";
    private static final String DESCRIPTION = "读取当前用户长期记忆 Workspace 内指定 Markdown 文件的完整内容。"
            + "参数 path 为相对路径，如 Agent.md、Learning/Current.md、History/AgentMemory.md。";

    private final MemoryWorkspace memoryWorkspace;

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
                .addProperty("path",
                        JsonStringSchema.builder()
                                .description("Memory 文件相对路径，如 Agent.md、Learning/Current.md")
                                .build())
                .required("path")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        Integer userId = context.getUserId();
        String path = readPathArgument(request);
        if (path == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "path 参数为空");
        }
        try {
            memoryWorkspace.initUserWorkspaceIfAbsent(userId);
            String content = memoryWorkspace.read(userId, path);
            return ToolCallResult.success(request.getToolCallId(), NAME, content);
        } catch (MemoryAccessException e) {
            log.warn("[ReadMemoryTool] 读取记忆失败 userId={} path={}: {}", userId, path, e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, "读取长期记忆失败: " + e.getMessage());
        }
    }

    /**
     * 轻量解析 path 参数：直接从 arguments JSON 提取 path 字段，避免依赖 ObjectMapper（供 Builder `@` 引用解析复用）。
     */
    private String readPathArgument(ToolCallRequest request) {
        String args = request.getArguments();
        if (args == null || args.isBlank()) {
            return null;
        }
        return extractJsonField(args, "path");
    }

    /**
     * 极简 JSON 字段提取：仅支持顶层字符串字段，用于 path 这种简单参数。复杂结构请走 ObjectMapper。
     */
    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int start = -1;
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                start = i + 1;
                break;
            }
            if (!Character.isWhitespace(c)) {
                return null;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }
}
