package org.linxing.linxing_agent.agent.memory.longterm.tool;

import com.fasterxml.jackson.annotation.JsonAlias;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryFileWriter;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.linxing.linxing_agent.agent.memory.longterm.worker.CurrentTopicRegistry;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 整体覆盖写入指定长期记忆 Markdown。
 * <p>2026.08.06：开放给主 Agent（决策 7-A），写路径走 {@link MemoryFileWriter} 的 CAS（决策 8），
 * 写入 {@code Learning/Current/} 后触发超额主题归档（决策 4）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteMemoryTool implements Tool {

    private static final String NAME = "write_memory";
    private static final String DESCRIPTION = "整体覆盖写入当前用户长期记忆指定 Markdown 文件。"
            + "参数 path 为相对路径，content 为完整 Markdown 内容（整体覆盖）。"
            + "推荐回传 read_memory 结果里的 baseline_mtime/baseline_size 做冲突检测（CAS）："
            + "若文件在读取后被改动，本次写入将放弃（用户优先）。";
    /** Current 主题目录前缀：写入此目录下新文件后触发超额归档检查 */
    private static final String CURRENT_DIR = "Learning/Current/";

    private final MemoryWorkspace memoryWorkspace;
    private final MemoryFileWriter memoryFileWriter;
    private final CurrentTopicRegistry currentTopicRegistry;
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
     * 注册为主 Agent 工具（决策 7-A）：用户在对话中显式要求改长期记忆时由 Agent 调用。
     * <p>提示词约束主 Agent 仅在用户显式要求时写入，非明确要求不主动改写。
     */
    @Override
    public boolean shouldRegisterToMainAgent() {
        return true;
    }

    @Override
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder()
                .addProperty("path",
                        JsonStringSchema.builder()
                                .description("Memory 文件相对路径，如 Agent.md、Learning/Current/Java.md")
                                .build())
                .addProperty("content",
                        JsonStringSchema.builder()
                                .description("完整 Markdown 内容，整体覆盖原文件")
                                .build())
                .addProperty("baseline_mtime",
                        JsonStringSchema.builder()
                                .description("可选但推荐：read_memory 返回的文件 mtime（毫秒），用于 CAS 冲突检测")
                                .build())
                .addProperty("baseline_size",
                        JsonStringSchema.builder()
                                .description("可选但推荐：read_memory 返回的文件 size（字节），用于 CAS 冲突检测")
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
            MemoryFileWriter.WriteResult result;
            boolean isNewFile = !memoryWorkspace.resolve(userId, args.getPath()).toFile().exists();
            if (args.getBaselineMtime() != null && args.getBaselineSize() != null) {
                // Agent 回传了基线：走 CAS
                MemoryFileWriter.FileBaseline baseline = new MemoryFileWriter.FileBaseline(
                        args.getBaselineMtime(), args.getBaselineSize());
                result = memoryFileWriter.writeIfUnchanged(userId, args.getPath(), baseline, content);
            } else {
                // Agent 未回传基线：降级为强制写（不 CAS），warn 提示
                log.warn("[WriteMemoryTool] Agent 未回传 baseline，降级为强制写 userId={} path={}", userId, args.getPath());
                result = memoryFileWriter.writeForce(userId, args.getPath(), content);
            }
            if (result instanceof MemoryFileWriter.WriteResult.Conflict) {
                log.info("[WriteMemoryTool] CAS 冲突，放弃写入 userId={} path={}", userId, args.getPath());
                return ToolCallResult.failure(request.getToolCallId(), NAME,
                        "写入放弃：文件在读取后被改动（CAS 冲突，用户优先）。请重新 read_memory 后再写入。");
            }
            // 写入成功后，若为 Learning/Current/ 下新文件，触发超额归档检查
            if (args.getPath().startsWith(CURRENT_DIR) && isNewFile) {
                currentTopicRegistry.checkAndEvictIfOverQuota(userId);
            }
            return ToolCallResult.success(request.getToolCallId(), NAME, "已写入：" + args.getPath());
        } catch (MemoryAccessException e) {
            log.warn("[WriteMemoryTool] 写入记忆失败 userId={} path={}: {}", userId, args.getPath(), e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, "写入长期记忆失败: " + e.getMessage());
        }
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class WriteArgs {
        /** path兼容name，容错设计 */
        @JsonAlias({"name"})
        private String path;
        private String content;
        /** CAS 基线：read_memory 返回的文件 mtime（毫秒）。可选，缺失则降级强制写 */
        @JsonAlias({"mtime"})
        private Long baselineMtime;
        /** CAS 基线：read_memory 返回的文件 size（字节）。可选，缺失则降级强制写 */
        @JsonAlias({"size"})
        private Long baselineSize;
    }
}
