package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.workflow.HumanInTheLoop;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.subagent.common.HumanInTheLoopFactory;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.tool.impl.RagSearchTool;
import org.linxing.linxing_agent.agent.tool.impl.WebSearchTool;
import org.springframework.stereotype.Service;

/**
 * 知识收集阶段工作流编排服务。
 * 作为顶层 {@link StudyPlanWorkflowService} 两阶段编排的第一阶段，负责：
 *   条件触发澄清（needs_clarification → 阻塞等待用户回复，仅一次）
 *   调用 {@link KnowledgeCollectorAgent} 自主搜索收集素材，写入 AgenticScope 的 "materials"
 * 编排顺序：clarifyConditional → collector。先澄清再收集，因为澄清后的补充信息可能引导新的搜索方向。
 * 阶段完成后，scope 中的 materials / clarification 供第二阶段{@link ContentGenerationWorkflowService} 使用。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeCollectionWorkflowService {

    private static final String CLARIFY_AGENT_NAME = "StudyPlanClarifyAgent";
    private static final String KNOWLEDGE_AGENT_NAME = "KnowledgeCollectorAgent";
    private static final String KNOWLEDGE_DISPLAY_LABEL = "收集资料";

    /** 澄清等待超时（秒），与 StudyPlanWorkflowService 保持一致 */
    private static final long CLARIFY_TIMEOUT_SECONDS = 1500;
    private static final String CLARIFY_TIMEOUT_REPLY = "无补充信息";

    private final HumanInTheLoopFactory humanInTheLoopFactory;
    private final WebSearchTool webSearchTool;
    private final RagSearchTool ragSearchTool;

    /**
     * 构建知识收集阶段的 UntypedAgent。
     *
     * @param recorder     步骤记录器
     * @param chatModel    非流式 ChatModel
     * @param sessionId    会话 ID（用于 HumanInTheLoop 回复路由）
     * @return 知识收集阶段工作流 Agent
     */
    public UntypedAgent build(StepRecorder recorder, ChatModel chatModel, Integer sessionId) {
        // ---- 澄清 Agent（公共工厂创建，阻塞等待用户回复）----
        HumanInTheLoop clarifyAgent = humanInTheLoopFactory.create(
                sessionId, "clarification", CLARIFY_TIMEOUT_SECONDS,
                CLARIFY_TIMEOUT_REPLY, recorder, CLARIFY_AGENT_NAME,
                AgentStepTypes.PHASE_KNOWLEDGE_SEARCH);

        // 条件包装：needs_clarification → clarifyAgent
        UntypedAgent clarifyConditional = AgenticServices
                .conditionalBuilder()
                .subAgents(
                        scope -> StepRecorder.readBooleanState(scope, "needs_clarification", false),
                        clarifyAgent
                )
                .build();

        // ---- 知识收集 Agent（带 @Tool 的 AI Service，内部 tool-calling 循环）----
        KnowledgeCollectorAgent collector = AgenticServices
                .agentBuilder(KnowledgeCollectorAgent.class)
                .chatModel(chatModel)
                .tools(webSearchTool, ragSearchTool)
                .outputKey("materials")
                // clarification 在 needs_clarification=false 时不会被 HumanInTheLoop 写入 scope，
                // 提供默认值避免 MissingArgumentException
                .defaultKeyValue("clarification", CLARIFY_TIMEOUT_REPLY)
                .listener(StepRecorder.createListener(
                        KNOWLEDGE_AGENT_NAME, "knowledge_search",
                        KNOWLEDGE_DISPLAY_LABEL, "materials", recorder,
                        AgentStepTypes.PHASE_KNOWLEDGE_SEARCH))
                .build();

        // ---- 顺序编排：先澄清（条件）再收集 ----
        //TODO：后续改为条件agent，澄清行为应该是条件触发（一次）、知识搜集也应该是条件触发（可能多次）
        return AgenticServices.sequenceBuilder()
                .subAgents(clarifyConditional, collector)
                .build();
    }
}
