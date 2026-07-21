package org.linxing.linxing_agent.agent.memory.window.projection.snip;

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
import org.linxing.linxing_agent.agent.memory.window.projection.snip.rules.ReadCurrentRulesTool;
import org.linxing.linxing_agent.agent.memory.window.projection.snip.rules.UpdateSkipTurnRuleTool;
import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemory;
import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemoryFactory;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.recovery.TurnBoundary;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SkipTurnReActLoop {

    private static final String SNIP_SYSTEM_PROMPT =
            "你是对话上下文压缩助手。给定【当前轮用户问题】作为判定锚点，以及按 Turn 分段的对话历史，"
                    + "判断哪些 Turn 对当前轮【已无价值】——"
                    + "(1) 低价值：寒暄/进度确认/重复试错/已失效的中间探索；"
                    + "(2) 已被消化：曾经有用但其关键结论已被后续 Turn 吸收、对回答当前轮已无新信息。"
                    + "以当前轮问题为参照：若某 Turn 的内容与当前轮主题无关，或其结论已被当前轮直接覆盖/被后续回答吸收，"
                    + "则对它调用 update_skip_turn_rule(action=add, turnId=..., reason=...) 标记跳过。"
                    + "可先调 read_current_rules 查看已存在的 rule 避免重复。"
                    + "只标记你确信对当前轮已无价值的 Turn，宁缺毋滥；当前轮本身不参与历史、不应跳过。"
                    + "完成后直接输出 final 文本（如\"done\"），不再调用工具。";

    private final LlmManager llmManager;
    private final RuleSetStore ruleSetStore;
    private final AgentMemoryFactory memoryFactory;
    private final ObjectMapper objectMapper;

    @Value("${agent.projection.snip.max-steps:6}")
    private int maxSteps;//模型snip循环的最大次数

    /**
     * 运行 Snip 小循环，把 SkipTurnRule 操作攒进 batch
     * @param recovered
     * @param sessionId
     * @param currentQuery
     * @param batch
     */
    public void run(RecoveredHistory recovered, Integer sessionId, String currentQuery,
                    RuleSetStore.RuleUpdateBatch batch) {
        if (recovered == null || recovered.getTurnBoundaries() == null
                || recovered.getTurnBoundaries().isEmpty()) {
            return;//无 Turn 结构可分析
        }
        SkipTurnReActContext ctx = new SkipTurnReActContext(sessionId, ruleSetStore, objectMapper, batch);

        OpenAiChatModel model = llmManager.getModel(LlmType.SUMMARY_MODEL);//非流式，支持 tool_calls
        List<ToolSpecification> specs = List.of(
                UpdateSkipTurnRuleTool.SPEC,
                ReadCurrentRulesTool.SPEC);

        //构造上下文
        AgentMemory mem = memoryFactory.create();
        mem.add(SystemMessage.from(SNIP_SYSTEM_PROMPT));
        mem.add(UserMessage.from(renderHistoryForPrompt(recovered, currentQuery)));

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
            for (dev.langchain4j.agent.tool.ToolExecutionRequest tr : ai.toolExecutionRequests()) {//每轮循环可能含有多个tool_call
                String resultText = ctx.executeTool(tr);//逐个执行
                mem.add(ToolExecutionResultMessage.from(tr, resultText));
            }
        }
        log.info("[SkipTurnReActLoop] sessionId={} 小循环结束，batch size={}", sessionId, ctx.getBatch().size());
    }

    /**
     * 把 turnBoundaries + messages 渲染成带 turnId 的文本供 LLM 分析
     * @param recovered
     * @param currentQuery
     * @return
     */
    private String renderHistoryForPrompt(RecoveredHistory recovered, String currentQuery) {
        StringBuilder sb = new StringBuilder();
        if (currentQuery != null && !currentQuery.isBlank()) {
            sb.append("【当前轮用户问题（判定锚点）】\n")
                    .append(currentQuery)
                    .append("\n\n请以上述当前轮问题为参照，判断下方各历史 Turn 对回答它是否仍有价值："
                            + "若某 Turn 与当前轮主题无关，或其结论已被后续 Turn/当前轮吸收、不再提供新信息，"
                            + "则标记跳过。\n\n");
        }
        sb.append("以下是当前会话的对话历史（已按 Turn 分段）。每个 Turn 标注了 turnId（DB 消息 id）。"
                + "请判断哪些 Turn 对当前轮已无价值并调 update_skip_turn_rule 标记。\n\n");
        List<ChatMessage> msgs = recovered.getMessages();//历史取自于激活的路径内容
        for (TurnBoundary tb : recovered.getTurnBoundaries()) {
            sb.append("【Turn turnId=").append(tb.getTurnStartMessageId()).append("】\n");
            for (int i = tb.getStartIdx(); i < tb.getEndIdx() && i < msgs.size(); i++) {
                ChatMessage m = msgs.get(i);
                sb.append(prefixOf(m)).append(textOf(m)).append("\n");
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

}
