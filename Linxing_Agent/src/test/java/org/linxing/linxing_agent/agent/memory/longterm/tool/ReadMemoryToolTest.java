package org.linxing.linxing_agent.agent.memory.longterm.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryFileWriter;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryTemplates;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspaceProperties;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReadMemoryTool 参数解析单测。
 *
 * <p>核心验证点：渐进披露模式下 LLM 未先 resolve 拿 schema 时，可能用 {@code name} 键代替规范键
 * {@code path}（见 agent_steps 实证：{@code {"name":"User.md"}} 曾导致 "path 参数为空" 失败）。
 * {@link ReadMemoryTool.ReadArgs#path} 上的 {@code @JsonAlias({"name"})} 应兼容此漂移。
 *
 * <p>不启动 Spring：手工 new {@link MemoryWorkspace}（{@link MemoryWorkspaceProperties} 配 {@link TempDir}）、
 * Mockito mock {@link AgentContext}（execute 仅用 {@code getUserId()}）。
 */
@DisplayName("ReadMemoryTool：path/name 键名兼容")
class ReadMemoryToolTest {

    private static final Integer USER_ID = 9991;
    private static final String TOOL_CALL_ID = "call_test";

    @TempDir
    Path tempDir;

    private ReadMemoryTool newTool() {
        MemoryWorkspaceProperties props = new MemoryWorkspaceProperties();
        props.setRootDir(tempDir.toString());
        MemoryTemplates templates = new MemoryTemplates();
        templates.load();
        MemoryWorkspace workspace = new MemoryWorkspace(props, templates);
        MemoryFileWriter writer = new MemoryFileWriter(workspace);
        return new ReadMemoryTool(workspace, writer, new tools.jackson.databind.ObjectMapper());
    }

    private AgentContext mockContext() {
        AgentContext ctx = mock(AgentContext.class);
        when(ctx.getUserId()).thenReturn(USER_ID);
        return ctx;
    }

    private ToolCallResult run(ReadMemoryTool tool, String arguments) {
        ToolCallRequest req = ToolCallRequest.builder()
                .toolCallId(TOOL_CALL_ID)
                .toolName("read_memory")
                .arguments(arguments)
                .build();
        return tool.execute(req, mockContext());
    }

    @Test
    @DisplayName("规范键 path：成功读取（首次访问懒生成模板后读到 Agent.md）")
    void shouldReadWithCanonicalPathKey() {
        ToolCallResult result = run(newTool(), "{\"path\": \"Agent.md\"}");
        assertTrue(result.isSuccess(), "path 键应成功");
        assertNotNull(result.getResult());
        assertTrue(result.getResult().contains("# Agent"), "应读到 Agent.md 模板内容");
    }

    @Test
    @DisplayName("兼容键 name：成功读取（渐进披露下 LLM 键名漂移的核心修复点）")
    void shouldReadWithAliasNameKey() {
        ToolCallResult result = run(newTool(), "{\"name\": \"Agent.md\"}");
        assertTrue(result.isSuccess(), "name 键应通过 @JsonAlias 兼容成功");
        assertNotNull(result.getResult());
        assertTrue(result.getResult().contains("# Agent"));
    }

    @Test
    @DisplayName("空 JSON：返回 path 参数为空")
    void shouldFailOnEmptyJson() {
        ToolCallResult result = run(newTool(), "{}");
        assertFalse(result.isSuccess());
        assertEquals("path 参数为空", result.getError());
    }

    @Test
    @DisplayName("path 空串：返回 path 参数为空")
    void shouldFailOnBlankPath() {
        ToolCallResult result = run(newTool(), "{\"path\": \"\"}");
        assertFalse(result.isSuccess());
        assertEquals("path 参数为空", result.getError());
    }

    @Test
    @DisplayName("非法 JSON：返回参数解析失败")
    void shouldFailOnInvalidJson() {
        ToolCallResult result = run(newTool(), "not a json");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().startsWith("参数解析失败"), "应提示解析失败，实际: " + result.getError());
    }
}
