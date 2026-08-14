package org.linxing.linxing_agent.agent.memory.longterm.worker;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.tool.ListMemoryTool;
import org.linxing.linxing_agent.agent.memory.longterm.tool.ReadMemoryTool;
import org.linxing.linxing_agent.agent.memory.longterm.tool.WriteMemoryTool;
import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemory;
import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemoryFactory;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Memory Worker ReAct 小循环：回答完成后异步判断并更新长期记忆 Markdown。
 *
 * @deprecated 2026.08.06 决策 7：对话后自动触发已移除，本小循环不再被调用，保留待评估是否复活。
 *             SYSTEM_PROMPT 已更新为多主题 Current + 按月 History 结构，若复活需配合实际链路验证。
 */
@Deprecated
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryWorkerReActLoop {

    private static final String SYSTEM_PROMPT =
            "你是长期记忆维护器。判断本轮对话是否产生了需要长期化的信息："
                    + "用户角色或偏好的变化、当前学习状态/主题/计划的变化、学习阶段的切换。\n"
                    + "记忆结构：当前学习主题存放于 Learning/Current/{topic}.md（多主题，最多 3 个，"
                    + "超出时最老主题自动归档），已完成学习阶段归档于 History/{yyyy-MM}/{topic}.md（按月分级，"
                    + "每周自动合并简写为 _merged.md）。\n"
                    + "约束：长期记忆只保存当前最新状态；不记录闲聊、临时信息、单次任务细节；"
                    + "不新增或删除 Section（一二级标题固定），仅修改 Section 内容。\n"
                    + "工作方式：先调 list_memory 查看有哪些记忆文件，再 read_memory 读取你可能要改的文件全文，"
                    + "确认后再 write_memory 整体覆盖写入（带 read_memory 返回的 baseline mtime+size 做冲突检测）。\n"
                    + "若本轮无需更新任何记忆，直接输出 final 文本（如 done），不再调用工具。";

    private final LlmManager llmManager;
    private final AgentMemoryFactory memoryFactory;
    private final ReadMemoryTool readMemoryTool;
    private final ListMemoryTool listMemoryTool;
    private final WriteMemoryTool writeMemoryTool;

    @Value("${agent.memory.longterm.worker.max-steps:}")
    private int maxSteps;//模型 Memory Worker 循环的最大次数

    /**
     * 运行 Memory Worker ReAct 小循环。
     * @param userId    用户 ID（隔离依据）
     * @param sessionId 会话 ID（仅日志用）
     * @param query     本轮用户问题
     * @param answer    本轮助手回答
     */
    public void run(Integer userId, Integer sessionId, String query, String answer) {
        MemoryWorkerReActContext ctx = new MemoryWorkerReActContext(
                userId, readMemoryTool, listMemoryTool, writeMemoryTool);

        OpenAiChatModel model = llmManager.getModel(LlmType.MEMORY_WORKER_MODEL);//非流式，支持 tool_calls
        List<ToolSpecification> specs = List.of(
                toSpec(readMemoryTool),
                toSpec(listMemoryTool),
                toSpec(writeMemoryTool));

        //构造上下文：System Prompt（工具使用约束）+ 本轮对话（UserMessage）
        AgentMemory mem = memoryFactory.create();
        mem.add(SystemMessage.from(SYSTEM_PROMPT));
        mem.add(UserMessage.from(renderUserTurn(query, answer)));

        for (int step = 1; step <= maxSteps; step++) {//ReAct，多轮模型循环
            ChatRequest req = ChatRequest.builder()
                    .messages(mem.messages())
                    .toolSpecifications(specs)
                    .build();
            ChatResponse resp = model.chat(req);//非流式，抛异常即中断整批丢弃
            AiMessage ai = resp.aiMessage();

            if (!ai.hasToolExecutionRequests()) {
                break;//final，退出循环
            }
            mem.add(ai);
            for (dev.langchain4j.agent.tool.ToolExecutionRequest tr : ai.toolExecutionRequests()) {//每轮循环可能含多个tool_call
                String resultText = ctx.executeTool(tr);//逐个执行
                mem.add(ToolExecutionResultMessage.from(tr, resultText));
            }
        }
        log.info("[MemoryWorkerReActLoop] sessionId={} userId={} 小循环结束", sessionId, userId);
    }

    /**
     * 把工具 Bean 的 name/description/spec 现场构建为 LangChain4j ToolSpecification。
     * <p>三把工具是 Spring Bean（非静态 SPEC 常量），故在此现场构建。
     */
    private static ToolSpecification toSpec(org.linxing.linxing_agent.agent.tool.Tool tool) {
        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(tool.spec())
                .build();
    }

    /**
     * 渲染本轮对话为 UserMessage：仅 query + answer 截断，不预塞记忆文件全文——
     * LLM 自行调 read_memory 按需读取，节省 token 预算。
     */
    private static String renderUserTurn(String query, String answer) {
        return "===本轮用户问题===\n" + truncate(query, 2000)
                + "\n\n===本轮助手回答===\n" + truncate(answer, 4000);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(已截断)";
    }
}
