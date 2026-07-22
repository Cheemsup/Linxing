package org.linxing.linxing_agent.agent.memory.longterm.tool;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 列出当前用户 Memory Workspace 内全部 Markdown 文件，返回带相对路径的列表。
 * <p>主要供 LLM/用户查看目录，决定后续 read_memory 的目标文件。用户隔离依据 {@link AgentContext#getUserId()}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListMemoryTool implements Tool {

    private static final String NAME = "list_memory";
    private static final String BRIEF = "列出长期记忆全部文件";
    private static final String DISPLAY_LABEL = "查看长期记忆目录";
    private static final String WHEN_TO_USE = "当你需要查看当前用户有哪些长期记忆文件、或不确定该读哪个记忆文件时使用";
    private static final String DESCRIPTION = "列出当前用户长期记忆 Workspace 内全部 Markdown 文件，返回带相对路径的列表。"
            + "用于查看目录以决定后续读取哪个记忆文件。无参数。";

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
        return JsonObjectSchema.builder().build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        Integer userId = context.getUserId();
        try {
            memoryWorkspace.initUserWorkspaceIfAbsent(userId);
            List<String> files = memoryWorkspace.list(userId);
            String result = files.isEmpty()
                    ? "长期记忆目录为空。"
                    : "长期记忆文件列表：\n" + String.join("\n", files);
            return ToolCallResult.success(request.getToolCallId(), NAME, result);
        } catch (MemoryAccessException e) {
            log.warn("[ListMemoryTool] 列出记忆失败 userId={}: {}", userId, e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, "列出长期记忆失败: " + e.getMessage());
        }
    }
}
