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
import tools.jackson.databind.ObjectMapper;

/**
 * 整体覆盖写入指定长期记忆 Markdown。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteMemoryTool implements Tool {

    private static final String NAME = "write_memory";
    private static final String DESCRIPTION = "整体覆盖写入当前用户长期记忆指定 Markdown 文件。"
            + "参数 path 为相对路径，content 为完整 Markdown 内容（整体覆盖）。";
    /** 覆盖此文件曾触发学习阶段归档检测；归档逻辑已暂时弃用，此常量保留为恢复锚点 */
    @SuppressWarnings("unused")
    private static final String CURRENT_MD = "Learning/Current.md";

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

    /**
     * 不注册为主 Agent 工具：仅 Memory Worker 内部调用。
     */
    @Override
    public boolean shouldRegisterToMainAgent() {
        return false;
    }

    @Override
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder()
                .addProperty("path",
                        JsonStringSchema.builder()
                                .description("Memory 文件相对路径，如 Agent.md、Learning/Current.md")
                                .build())
                .addProperty("content",
                        JsonStringSchema.builder()
                                .description("完整 Markdown 内容，整体覆盖原文件")
                                .build())
                .required("path", "content")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        Integer userId = context.getUserId();
        WriteArgs args;
        try {
            args = objectMapper.readValue(request.getArguments(), WriteArgs.class);
        } catch (Exception e) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "参数解析失败: " + e.getMessage());
        }
        if (args.getPath() == null || args.getPath().isBlank()) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "path 不能为空");
        }
        String content = args.getContent() == null ? "" : args.getContent();
        try {
            memoryWorkspace.initUserWorkspaceIfAbsent(userId);
            // TODO[HistoryArchiver 暂时弃用 2026.07.22]
            memoryWorkspace.write(userId, args.getPath(), content);
            return ToolCallResult.success(request.getToolCallId(), NAME, "已写入：" + args.getPath());
        } catch (MemoryAccessException e) {
            log.warn("[WriteMemoryTool] 写入记忆失败 userId={} path={}: {}", userId, args.getPath(), e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, "写入长期记忆失败: " + e.getMessage());
        }
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class WriteArgs {
        private String path;
        private String content;
    }
}
