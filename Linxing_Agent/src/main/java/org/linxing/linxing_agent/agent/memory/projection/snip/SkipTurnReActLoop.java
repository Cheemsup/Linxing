package org.linxing.linxing_agent.agent.memory.projection.snip;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.AgentMemory;
import org.linxing.linxing_agent.agent.memory.AgentMemoryFactory;
import org.linxing.linxing_agent.agent.memory.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.recovery.TurnBoundary;
import org.linxing.linxing_agent.agent.memory.ruleset.RuleSetStore;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * SkipTurn ReAct 精简小循环（thePlan P2-2 / nowRefact §6-4/§6-5）。
 *
 * <p>独立于主 {@code AgentExecutor} 的精简 ReAct 循环：<b>非流式、不落库、不推 SSE</b>。
 * 复用 {@link LlmType#SUMMARY_MODEL}（deepseek 非流式 {@link OpenAiChatModel}，支持 tool_calls）。
 *
 * <p>循环挂两把内部 tool（{@link org.linxing.linxing_agent.agent.memory.projection.snip.rules.UpdateSkipTurnRuleTool}
 * / {@link org.linxing.linxing_agent.agent.memory.projection.snip.rules.ReadCurrentRulesTool}），
 * LLM 读完历史后按条目粒度调 {@code update_skip_turn_rule} 增删改 SkipTurnRule，攒进
 * {@link RuleSetStore.RuleUpdateBatch}。循环结束（final 或达 maxSteps）返回 batch；
 * 任一步异常直接抛出，由 {@link SnipLoopExecutor} catch 后整批丢弃、不应用（避免破损中间态）。
 *
 * <p>小循环工具不进主 ToolRegistry（手工分派，见 {@link SkipTurnReActContext#executeTool}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkipTurnReActLoop {

    /** Snip 小循环系统提示词（初稿，待调）。 */
    private static final String SNIP_SYSTEM_PROMPT =
            "你是对话上下文压缩助手。给定按 Turn 分段的对话历史，判断哪些 Turn 属于【低价值】"
                    + "（寒暄/进度确认/重复试错/已失效的中间探索），对每个低价值 Turn 调用 "
                    + "update_skip_turn_rule(action=add, turnId=..., reason=...) 标记跳过。"
                    + "可先调 read_current_rules 查看已存在的 rule 避免重复。"
                    + "只标记你确信低价值的 Turn，宁缺毋滥；当前轮与近期 Turn 不应跳过。"
                    + "完成后直接输出 final 文本（如\"done\"），不再调用工具。";

    /** 每个 Turn 内容截断上限（防 prompt 自身膨胀）。 */
    private static final int TURN_TEXT_TRUNCATE = 500;

    private final LlmManager llmManager;
    private final RuleSetStore ruleSetStore;
    private final AgentMemoryFactory memoryFactory;
    private final ObjectMapper objectMapper;

    @Value("${agent.projection.snip.max-steps:6}")
    private int maxSteps;

    /**
     * 运行 Snip 小循环，把 SkipTurnRule 操作攒进给定 batch（与 RewriteToolRule 共用同一 batch，
     * 由 SnipLoopExecutor 统一原子应用）。
     * <p>异常直接抛出——调用方（SnipLoopExecutor）catch 后丢弃 batch，不应用。
     *
     * @param recovered Recovery 结果（含 messages + turnBoundaries）；turnBoundaries 为空时直接返回
     * @param sessionId 会话 id
     * @param batch     共享批次，SkipTurnRule 操作攒入此 batch
     */
    public void run(RecoveredHistory recovered, Integer sessionId, RuleSetStore.RuleUpdateBatch batch) {
        if (recovered == null || recovered.getTurnBoundaries() == null
                || recovered.getTurnBoundaries().isEmpty()) {
            return;//无 Turn 结构可分析
        }
        SkipTurnReActContext ctx = new SkipTurnReActContext(sessionId, ruleSetStore, objectMapper, batch);

        OpenAiChatModel model = llmManager.getModel(LlmType.SUMMARY_MODEL);//非流式，支持 tool_calls
        List<ToolSpecification> specs = List.of(
                org.linxing.linxing_agent.agent.memory.projection.snip.rules.UpdateSkipTurnRuleTool.SPEC,
                org.linxing.linxing_agent.agent.memory.projection.snip.rules.ReadCurrentRulesTool.SPEC);

        AgentMemory mem = memoryFactory.create();
        mem.add(SystemMessage.from(SNIP_SYSTEM_PROMPT));
        mem.add(UserMessage.from(renderHistoryForPrompt(recovered)));

        for (int step = 1; step <= maxSteps; step++) {
            ChatRequest req = ChatRequest.builder()
                    .messages(mem.messages())
                    .toolSpecifications(specs)
                    .build();
            ChatResponse resp = model.chat(req);//非流式，抛异常即中断整批丢弃
            AiMessage ai = resp.aiMessage();

            if (!ai.hasToolExecutionRequests()) {
                break;//final → 退出循环，提交 batch
            }
            mem.add(ai);
            for (dev.langchain4j.agent.tool.ToolExecutionRequest tr : ai.toolExecutionRequests()) {
                String resultText = ctx.executeTool(tr);
                mem.add(ToolExecutionResultMessage.from(tr, resultText));
            }
        }
        log.info("[SkipTurnReActLoop] sessionId={} 小循环结束，batch size={}", sessionId, ctx.getBatch().size());
    }

    /**
     * 把 recovered 的 turnBoundaries + messages 渲染成带 turnId 的文本供 LLM 分析。
     * 每个 Turn 标注 turnStartMessageId，内容按消息前缀+截断呈现。
     */
    private String renderHistoryForPrompt(RecoveredHistory recovered) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是当前会话的对话历史（已按 Turn 分段）。每个 Turn 标注了 turnId（DB 消息 id）。"
                + "请判断哪些 Turn 低价值并调 update_skip_turn_rule 标记。\n\n");
        List<ChatMessage> msgs = recovered.getMessages();
        for (TurnBoundary tb : recovered.getTurnBoundaries()) {
            sb.append("【Turn turnId=").append(tb.getTurnStartMessageId()).append("】\n");
            for (int i = tb.getStartIdx(); i < tb.getEndIdx() && i < msgs.size(); i++) {
                ChatMessage m = msgs.get(i);
                sb.append(prefixOf(m)).append(truncate(textOf(m), TURN_TEXT_TRUNCATE)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String prefixOf(ChatMessage msg) {
        if (msg instanceof UserMessage) return "用户：";
        if (msg instanceof AiMessage) return "助手：";
        if (msg instanceof ToolExecutionResultMessage) return "工具结果：";
        return "";
    }

    private String textOf(ChatMessage msg) {
        if (msg instanceof UserMessage um) return um.singleText();
        if (msg instanceof AiMessage am) return am.text() != null ? am.text() : "[tool_call]";
        if (msg instanceof SystemMessage sm) return sm.text();
        if (msg instanceof ToolExecutionResultMessage tm) return tm.text();
        return "";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
