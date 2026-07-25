package org.linxing.linxing_agent.agent.memory.longterm.tool;

import com.fasterxml.jackson.annotation.JsonAlias;
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
        // 用 ObjectMapper 解析参数：渐进披露模式下 LLM 未 resolve 拿到 schema 时可能用 name 键，
        // @JsonAlias({"name"}) 兼容此键名漂移，path 仍为规范名。
        ReadArgs args;
        try {
            args = objectMapper.readValue(request.getArguments(), ReadArgs.class);
        } catch (Exception e) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "参数解析失败: " + e.getMessage());
        }
        String path = args.getPath();
        if (path == null || path.isBlank()) {
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
     * read_memory 工具参数。
     * <p>{@code path} 为规范键名（与 {@link #spec()} 声明一致）；
     * {@code @JsonAlias({"name"})} 兼容渐进披露模式下 LLM 未先 resolve 拿 schema 时用 {@code name} 键的漂移。
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class ReadArgs {
        @JsonAlias({"name"})
        private String path;
    }
}
