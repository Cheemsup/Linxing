package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.WindowMemory;
import org.linxing.linxing_agent.agent.memory.SummaryMemory;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.catalog.Catalog;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.linxing.linxing_agent.agent.skill.SkillMetadata;
import org.linxing.linxing_agent.agent.skill.SkillRegistry;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.linxing.linxing_agent.agent.tool.ToolSpec;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AgentExecutor {

    /**
     * 大模型最大调用轮次，注意并不等同于step
     */
    private static final int MAX_STEPS = 20;

    /**
     * 渐进式披露阈值：tool + skill 注册总数超过此值时启用渐进披露模式，低于等于阈值时采用全量注入模式
     */
    @Value("${agent.disclosure.threshold:5}")
    private int disclosureThreshold;

    private static final String SYSTEM_PROMPT_TEMPLATE_FULL = AgentPrompts.SYSTEM_PROMPT_TEMPLATE_FULL;

    private static final String SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE = AgentPrompts.SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE;

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final List<CatalogProvider> catalogProviders;
    private final AgentStepMapper agentStepMapper;
    private final ObjectMapper objectMapper;

    public AgentExecutor(ToolRegistry toolRegistry, SkillRegistry skillRegistry,
                         List<CatalogProvider> catalogProviders,
                         AgentStepMapper agentStepMapper, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.catalogProviders = catalogProviders;
        this.agentStepMapper = agentStepMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * ReAct Agent核心执行循环：LLM推理→工具调用→结果注入→下一轮，直到获得最终回答或超过最大步数
     * @param context
     * @param chatModel
     * @param listener
     * @return
     */
    public AgentResult execute(AgentContext context, OpenAiStreamingChatModel chatModel, AgentStepListener listener) {
        List<AgentStepVO> recordedSteps = new ArrayList<>();

        //根据工具+技能总数决定是否启用渐进披露模式
        int totalCount = toolRegistry.size() + skillRegistry.size();
        boolean progressiveMode = totalCount > disclosureThreshold;

        //构建系统提示词并注入Agent记忆
        SystemMessage systemMessage = SystemMessage.from(buildSystemPrompt(progressiveMode));
        if (context.getMemory() instanceof WindowMemory wm) {
            wm.setSystemMessage(systemMessage);//WindowMemory及其子类统一使用setSystemMessage，确保系统提示词独立存储且在摘要后可恢复
        } else {
            context.getMemory().add(systemMessage);
        }

        List<ToolSpecification> initialSpecs = buildInitialToolSpecs(progressiveMode);
        Set<String> activatedToolNames = new HashSet<>();//渐进模式下已动态激活的工具名集合

        int stepNumber = 0;

        //主循环：LLM推理 → 工具调用 → 结果注入 → 下一轮
        while (stepNumber < MAX_STEPS) {
            stepNumber++;

            listener.onStep(AgentStepEvent.builder()
                    .eventType(AgentStepTypes.THINKING)
                    .stepNumber(stepNumber)
                    .phase(AgentStepTypes.PHASE_THINKING)
                    .build());

            List<ToolSpecification> roundSpecs = buildRoundToolSpecs(initialSpecs, activatedToolNames, progressiveMode);//渐进模式下追加已激活的工具规格

            List<ChatMessage> currentMessages = context.getMemory().messages();
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(currentMessages)
                    .toolSpecifications(roundSpecs)
                    .build();

            //调用流式LLM并等待完整响应
            ChatResponse response;
            StreamingResponseFuture future;
            try {
                future = new StreamingResponseFuture(listener, stepNumber);
                chatModel.chat(chatRequest, future);
                response = future.await(600, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("[AgentExecutor] LLM调用失败: {}", e.getMessage(), e);
                listener.onStep(AgentStepEvent.builder()
                        .eventType(AgentStepTypes.ERROR)
                        .stepNumber(stepNumber)
                        .phase(AgentStepTypes.PHASE_THINKING)
                        .error(e.getMessage())
                        .stepData(Map.of(AgentStepTypes.KEY_ERROR_CODE, AgentStepTypes.ERR_LLM_CALL_FAILED))
                        .finalStep(true)
                        .build());
                AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                        AgentStepTypes.ERROR, "LLM调用失败: " + e.getMessage(),
                        Map.of(AgentStepTypes.KEY_ERROR_CODE, AgentStepTypes.ERR_LLM_CALL_FAILED));
                agentStepMapper.insert(step);
                recordedSteps.add(toStepVO(step));

                return AgentResult.builder()
                        .answer("抱歉，处理您的问题时出现了错误，请稍后重试。")
                        .sourcesJson("[]")
                        .steps(recordedSteps)
                        .totalSteps(stepNumber)
                        .build();
            }

            AiMessage aiMessage = response.aiMessage();

            log.debug("[DEBUG] 步骤{} hasTool={}", stepNumber, aiMessage.hasToolExecutionRequests());

            //持久化推理/思考内容到agent_steps（仅当LLM产生了thinking token时）
            if (future.hasThinkingContent()) {
                String thinkingText = future.getThinkingContent();
                AgentStep thinkingStep = buildStep(context.getSessionId(), null, stepNumber,
                        AgentStepTypes.THINKING, truncate(thinkingText, 8000),
                        Map.of("thinking_tokens", thinkingText.length()));
                agentStepMapper.insert(thinkingStep);
                recordedSteps.add(toStepVO(thinkingStep));
            }

            if (aiMessage.hasToolExecutionRequests()) {
                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();

                context.getMemory().add(aiMessage);//将LLM的工具调用消息加入记忆，供后续轮次参考

                for (ToolExecutionRequest toolReq : toolRequests) {

                    listener.onStep(AgentStepEvent.builder()
                            .eventType(AgentStepTypes.TOOL_CALL)
                            .stepNumber(stepNumber)
                            .phase(AgentStepTypes.PHASE_THINKING)
                            .stepData(Map.of(
                                    AgentStepTypes.KEY_TOOL_CALL_ID, toolReq.id(),
                                    AgentStepTypes.KEY_TOOL_NAME, toolReq.name(),
                                    "arguments", toolReq.arguments()))
                            .build());

                    ToolCallRequest toolCallRequest = ToolCallRequest.builder()
                            .toolCallId(toolReq.id())
                            .toolName(toolReq.name())
                            .arguments(toolReq.arguments())
                            .build();

                    //查找并执行工具，未知工具返回失败
                    ToolSpec toolSpec = toolRegistry.getTool(toolReq.name());
                    ToolCallResult toolResult;
                    if (toolSpec == null) {
                        toolResult = ToolCallResult.failure(toolReq.id(), toolReq.name(),
                                "未知工具: " + toolReq.name());
                    } else {
                        toolResult = toolSpec.execute(toolCallRequest, context);//执行工具调用，获取结果
                    }

                    //渐进披露模式：resolve成功后提取被解析的工具名并动态激活
                    if (progressiveMode && "resolve".equals(toolReq.name()) && toolResult.isSuccess()) {
                        List<String> resolvedNames = parseResolvedNames(toolReq.arguments());
                        for (String name : resolvedNames) {
                            if (toolRegistry.getTool(name) != null) {
                                activatedToolNames.add(name);
                            }
                            //技能被解析时，将其关联的工具也动态激活
                            SkillMetadata skillMeta = skillRegistry.getMetadata(name);
                            if (skillMeta != null && skillMeta.getToolNames() != null) {
                                for (String toolName : skillMeta.getToolNames()) {
                                    if (toolRegistry.getTool(toolName) != null) {
                                        activatedToolNames.add(toolName);
                                    }
                                }
                            }
                        }
                    }

                    //构建工具执行结果文本
                    String resultText = toolResult.isSuccess()
                            ? toolResult.getResult()
                            : "Error: " + toolResult.getError();

                    listener.onStep(AgentStepEvent.builder()
                            .eventType(AgentStepTypes.TOOL_RESULT)
                            .stepNumber(stepNumber)
                            .phase(AgentStepTypes.PHASE_THINKING)
                            .stepData(Map.of(
                                    AgentStepTypes.KEY_TOOL_CALL_ID, toolReq.id(),
                                    AgentStepTypes.KEY_TOOL_NAME, toolReq.name(),
                                    AgentStepTypes.KEY_IS_SUCCESS, toolResult.isSuccess()))
                            .build());

                    //工具执行结果注入记忆，供LLM下一轮参考
                    ToolExecutionResultMessage resultMsg = ToolExecutionResultMessage.from(toolReq, resultText);
                    context.getMemory().add(resultMsg);

                    //记录工具调用步骤到数据库
                    String stepContent = toolResult.isSuccess()
                            ? toolReq.arguments()
                            : "Error: " + toolResult.getError();
                    AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                            AgentStepTypes.TOOL_CALL, stepContent,
                            Map.of(AgentStepTypes.KEY_TOOL_CALL_ID, toolReq.id(),
                                    AgentStepTypes.KEY_TOOL_NAME, toolReq.name()));
                    agentStepMapper.insert(step);
                    recordedSteps.add(toStepVO(step));

                    //记录工具返回结果步骤
                    AgentStep obsStep = buildStep(context.getSessionId(), null, stepNumber,
                            AgentStepTypes.TOOL_RESULT,
                            toolResult.isSuccess() ? toolResult.getResult() : toolResult.getError(),
                            Map.of(AgentStepTypes.KEY_TOOL_CALL_ID, toolReq.id(),
                                    AgentStepTypes.KEY_TOOL_NAME, toolReq.name(),
                                    AgentStepTypes.KEY_IS_SUCCESS, toolResult.isSuccess()));
                    agentStepMapper.insert(obsStep);
                    recordedSteps.add(toStepVO(obsStep));
                }

                //SummaryMemory在工具调用后尝试摘要压缩，避免上下文过长
                if (context.getMemory() instanceof SummaryMemory sm) {
                    sm.summarizeIfNeeded();
                }
            } else {
                //无工具调用 → LLM直接返回文本回答，循环结束
                String answer = aiMessage.text();
                if (answer == null || answer.isBlank()) {
                    answer = "抱歉，无法生成回答。";
                }

                listener.onStep(AgentStepEvent.builder()
                        .eventType(AgentStepTypes.FINAL)
                        .stepNumber(stepNumber)
                        .phase(AgentStepTypes.PHASE_ANSWER)
                        .answer(answer)
                        .finalStep(true)
                        .build());

                //final步骤不写DB，最终回答唯一存储在chat_messages中

                return AgentResult.builder()
                        .answer(answer)
                        .sourcesJson("[]")
                        .steps(recordedSteps)
                        .totalSteps(stepNumber)
                        .build();
            }
        }

        //超过最大步骤数，兜底返回
        log.warn("[AgentExecutor] 超过最大步骤数 {}!", MAX_STEPS);
        listener.onStep(AgentStepEvent.builder()
                .eventType(AgentStepTypes.ERROR)
                .stepNumber(stepNumber)
                .phase(AgentStepTypes.PHASE_THINKING)
                .error("超过最大步骤数 " + MAX_STEPS)
                .stepData(Map.of(AgentStepTypes.KEY_ERROR_CODE, AgentStepTypes.ERR_MAX_STEPS_EXCEEDED,
                        AgentStepTypes.KEY_STEP_COUNT, MAX_STEPS))
                .finalStep(true)
                .build());
        AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                AgentStepTypes.ERROR, "超过最大步骤数 " + MAX_STEPS,
                Map.of(AgentStepTypes.KEY_ERROR_CODE, AgentStepTypes.ERR_MAX_STEPS_EXCEEDED,
                        AgentStepTypes.KEY_STEP_COUNT, MAX_STEPS));
        agentStepMapper.insert(step);
        recordedSteps.add(toStepVO(step));

        return AgentResult.builder()
                .answer("抱歉，回答该问题需要过多的处理步骤，请尝试简化问题。")
                .sourcesJson("[]")
                .steps(recordedSteps)
                .totalSteps(stepNumber)
                .exceededMaxSteps(true)
                .build();
    }

    /**
     * 动态构建系统提示词，注入工具与技能目录信息
     * @param progressiveMode true=渐进披露模式，false=全量注入模式
     */
    private String buildSystemPrompt(boolean progressiveMode) {
        List<CatalogEntry> allEntries = new ArrayList<>();
        for (CatalogProvider provider : catalogProviders) {
            allEntries.addAll(provider.catalogEntries());
        }

        List<CatalogEntry> filtered = allEntries.stream()
                .filter(e -> !Catalog.META_TOOLS.contains(e.getName()))
                .collect(Collectors.toList());

        StringBuilder dynamicSection = new StringBuilder();

        if (!filtered.isEmpty()) {
            Catalog catalog = new Catalog(filtered);
            dynamicSection.append("【可用能力】\n").append(catalog.toPromptText()).append("\n\n");
        }

        if (!progressiveMode) {
            List<String> allSkillNames = skillRegistry.getAllNames();
            if (!allSkillNames.isEmpty()) {
                String resolved = skillRegistry.resolve(allSkillNames);
                if (resolved != null && !resolved.isBlank() && !resolved.startsWith("未找到")) {
                    dynamicSection.append("【可用技能完整说明】\n").append(resolved).append("\n\n");
                }
            }
            dynamicSection.append("所有工具和技能的完整定义已在上方提供，请直接使用。");
        } else {
            dynamicSection.append("由于可用工具和技能较多，请先查看上方目录了解可用能力。"
                    + "如需使用某个工具或技能，请调用 resolve 获取其完整定义。");
        }

        String template = progressiveMode ? SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE : SYSTEM_PROMPT_TEMPLATE_FULL;
        return String.format(template, dynamicSection.toString());
    }

    /**
     * 构建第一轮的 toolSpecifications 列表。
     * 全量模式返回所有已注册工具；渐进披露模式仅返回 resolve 元工具。
     */
    private List<ToolSpecification> buildInitialToolSpecs(boolean progressiveMode) {
        if (!progressiveMode) {
            return toolRegistry.getToolSpecifications();//全量注入
        }
        List<ToolSpecification> specs = new ArrayList<>();
        ToolSpecification resolveSpec = toolRegistry.getToolSpecification("resolve");//渐进披露模式，这一步的初始化只传入“工具之工具”——可用于获取其他工具定义的工具
        if (resolveSpec != null) {
            specs.add(resolveSpec);
        }
        return specs;
    }

    /**
     * 构建每轮对话的 toolSpecifications。
     * 全量模式始终返回初始规格；渐进披露模式在初始规格基础上追加已动态激活的工具。
     */
    private List<ToolSpecification> buildRoundToolSpecs(List<ToolSpecification> initialSpecs,
                                                        Set<String> activatedToolNames,
                                                        boolean progressiveMode) {
        if (!progressiveMode || activatedToolNames.isEmpty()) {
            return initialSpecs;
        }
        List<ToolSpecification> roundSpecs = new ArrayList<>(initialSpecs);
        List<ToolSpecification> activated = toolRegistry.getToolSpecifications(new ArrayList<>(activatedToolNames));
        roundSpecs.addAll(activated);
        return roundSpecs;
    }

    /**
     * 从 resolve 工具的 arguments JSON 中提取被解析的名称列表。
     * 用于渐进披露模式下解析 LLM 通过 resolve 请求了哪些工具/技能。
     */
    private List<String> parseResolvedNames(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            JsonNode namesNode = node.get("names");
            if (namesNode != null && namesNode.isArray()) {
                List<String> names = new ArrayList<>();
                namesNode.forEach(n -> names.add(n.asText()));
                return names;
            }
        } catch (Exception e) {
            log.warn("[AgentExecutor] 解析 resolve 参数失败: {}", arguments);
        }
        return List.of();
    }

    private AgentStep buildStep(Integer sessionId, Integer chatMessageId,
                                 int stepOrder, String stepType, String content,
                                 Map<String, Object> stepData) {
        return AgentStep.builder()
                .chatMessageId(chatMessageId)
                .sessionId(sessionId)
                .stepOrder(stepOrder)
                .stepType(stepType)
                .content(content)
                .stepData(stepData != null ? stepData : Map.of())
                .build();
    }

    private AgentStepVO toStepVO(AgentStep step) {
        return AgentStepVO.builder()
                .id(step.getId())
                .stepOrder(step.getStepOrder())
                .stepType(step.getStepType())
                .content(step.getContent())
                .stepData(step.getStepData())
                .createdAt(step.getCreatedAt())
                .build();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
