package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.WindowMemory;
import org.linxing.linxing_agent.agent.memory.SummaryMemory;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.catalog.Catalog;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
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
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AgentExecutor {

    /**
     * Agent 循环最大迭代次数，防止死循环
     */
    private static final int MAX_STEPS = 20;

    /**
     * 渐进式披露阈值：tool + skill 注册总数超过此值时启用渐进披露模式，低于等于阈值时采用全量注入模式
     */
    @Value("${agent.disclosure.threshold:5}")
    private int disclosureThreshold;

    private static final String SYSTEM_PROMPT_TEMPLATE =
            "你是一个智能知识库助手，可以搜索用户的个人笔记和文档来回答问题。\n\n"
            + "工作流程：\n"
            + "1. 先思考用户的问题需要哪些信息\n"
            + "2. 查看下方【可用能力】目录，确认是否有匹配的工具或技能\n"
            + "3. 如需了解工具或技能的详细用法，使用 resolve 获取完整定义\n"
            + "4. 基于获取的信息给出准确、完整的回答\n"
            + "5. 仅依据获取的信息回答，不要编造信息\n\n"
            + "回答时务必标注信息来源（文件名和标题路径）。\n\n"
            + "%s";

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

    public AgentResult execute(AgentContext context, OpenAiChatModel chatModel, AgentStepListener listener) {
        List<AgentStepVO> recordedSteps = new ArrayList<>();

        int totalCount = toolRegistry.size() + skillRegistry.size();
        boolean progressiveMode = totalCount > disclosureThreshold;
        log.info("[AgentExecutor] 模式={} (tool={}, skill={}, threshold={})",
                progressiveMode ? "渐进披露" : "全量注入",
                toolRegistry.size(), skillRegistry.size(), disclosureThreshold);

        SystemMessage systemMessage = SystemMessage.from(buildSystemPrompt(progressiveMode));
        if (context.getMemory() instanceof WindowMemory wm) {
            wm.setSystemMessage(systemMessage);
        } else {
            context.getMemory().add(systemMessage);
        }

        List<ToolSpecification> initialSpecs = buildInitialToolSpecs(progressiveMode);//通过progressiveMode影响初始提供的工具内容
        Set<String> activatedToolNames = new HashSet<>();

        int stepNumber = 0;
        AiMessage lastAiMessage = null;

        // 主循环：LLM 对话 → 工具调用 → 结果注入 → 下一轮
        while (stepNumber < MAX_STEPS) {
            stepNumber++;
            log.info("[AgentExecutor] 步骤 {}/{} — 用户{} 会话{}",
                    stepNumber, MAX_STEPS, context.getUserId(), context.getSessionId());

            listener.onStep(AgentStepEvent.builder()
                    .eventType("thinking")
                    .stepNumber(stepNumber)
                    .build());

            List<ToolSpecification> roundSpecs = buildRoundToolSpecs(initialSpecs, activatedToolNames, progressiveMode);

            List<ChatMessage> currentMessages = context.getMemory().messages();
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(currentMessages)
                    .toolSpecifications(roundSpecs)
                    .build();

            ChatResponse response;
            try {
                response = chatModel.chat(chatRequest);
            } catch (Exception e) {
                log.error("[AgentExecutor] LLM调用失败: {}", e.getMessage(), e);
                listener.onStep(AgentStepEvent.builder()
                        .eventType("error")
                        .stepNumber(stepNumber)
                        .error(e.getMessage())
                        .finalStep(true)
                        .build());
                AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                        "error", "LLM调用失败: " + e.getMessage(), null);
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

            // ===== 4. 工具调用处理 =====
            if (aiMessage.hasToolExecutionRequests()) {
                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
                log.info("[AgentExecutor] LLM请求调用 {} 个工具", toolRequests.size());

                // 将 LLM 的工具调用消息加入记忆，供后续轮次参考
                context.getMemory().add(aiMessage);

                List<ToolExecutionResultMessage> toolResults = new ArrayList<>();
                for (ToolExecutionRequest toolReq : toolRequests) {
                    log.info("[AgentExecutor] 执行工具: {} args={}",
                            toolReq.name(), toolReq.arguments());

                    listener.onStep(AgentStepEvent.builder()
                            .eventType("tool_call")
                            .stepNumber(stepNumber)
                            .toolName(toolReq.name())
                            .toolArguments(toolReq.arguments())
                            .build());

                    ToolCallRequest toolCallRequest = ToolCallRequest.builder()
                            .toolCallId(toolReq.id())
                            .toolName(toolReq.name())
                            .arguments(toolReq.arguments())
                            .build();

                    ToolSpec toolSpec = toolRegistry.getTool(toolReq.name());
                    ToolCallResult toolResult;
                    if (toolSpec == null) {
                        toolResult = ToolCallResult.failure(toolReq.id(), toolReq.name(),
                                "未知工具: " + toolReq.name());
                    } else {
                        toolResult = toolSpec.execute(toolCallRequest, context);
                    }

                    // 渐进披露模式：拦截 resolve 调用，提取被解析的工具名并动态激活
                    if (progressiveMode && "resolve".equals(toolReq.name()) && toolResult.isSuccess()) {
                        List<String> resolvedNames = parseResolvedNames(toolReq.arguments());
                        for (String name : resolvedNames) {
                            if (toolRegistry.getTool(name) != null) {
                                activatedToolNames.add(name);
                                log.info("[AgentExecutor] 渐进披露激活工具: {}", name);
                            }
                        }
                    }

                    // 工具执行结果注入记忆，供 LLM 参考
                    ToolExecutionRequest execReq = ToolExecutionRequest.builder()
                            .id(toolReq.id())
                            .name(toolReq.name())
                            .arguments(toolReq.arguments())
                            .build();
                    String resultText = toolResult.isSuccess()
                            ? toolResult.getResult()
                            : "Error: " + toolResult.getError();

                    listener.onStep(AgentStepEvent.builder()
                            .eventType("tool_result")
                            .stepNumber(stepNumber)
                            .toolName(toolReq.name())
                            .toolResult(resultText)
                            .build());

                    ToolExecutionResultMessage resultMsg = ToolExecutionResultMessage.from(execReq, resultText);
                    toolResults.add(resultMsg);
                    context.getMemory().add(resultMsg);

                    // 记录工具调用步骤到数据库
                    String stepContent = toolResult.isSuccess()
                            ? toolReq.arguments()
                            : "Error: " + toolResult.getError();
                    AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                            "tool_call", stepContent, toolReq.name());
                    agentStepMapper.insert(step);
                    recordedSteps.add(toStepVO(step));

                    // 记录工具返回结果步骤
                    AgentStep obsStep = buildStep(context.getSessionId(), null, stepNumber,
                            "tool_result",
                            toolResult.isSuccess() ? toolResult.getResult() : toolResult.getError(),
                            toolReq.name());
                    agentStepMapper.insert(obsStep);
                    recordedSteps.add(toStepVO(obsStep));
                }

                if (context.getMemory() instanceof SummaryMemory sm) {
                    sm.summarizeIfNeeded();
                }
            } else {
                // ===== 5. 无工具调用 → LLM 直接返回文本回答，循环结束 =====
                String answer = aiMessage.text();
                if (answer == null || answer.isBlank()) {
                    answer = "抱歉，无法生成回答。";
                }

                log.info("[AgentExecutor] 完成，共{}步，答案长度: {}字符",
                        stepNumber, answer.length());

                lastAiMessage = aiMessage;

                listener.onStep(AgentStepEvent.builder()
                        .eventType("final")
                        .stepNumber(stepNumber)
                        .answer(answer)
                        .finalStep(true)
                        .build());

                AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                        "final", truncate(answer, 2000), null);
                agentStepMapper.insert(step);
                recordedSteps.add(toStepVO(step));

                return AgentResult.builder()
                        .answer(answer)
                        .sourcesJson("[]")
                        .steps(recordedSteps)
                        .totalSteps(stepNumber)
                        .build();
            }
        }

        // ===== 6. 超过最大步骤数，兜底返回 =====
        log.warn("[AgentExecutor] 超过最大步骤数 {}!", MAX_STEPS);
        listener.onStep(AgentStepEvent.builder()
                .eventType("error")
                .stepNumber(stepNumber)
                .error("超过最大步骤数 " + MAX_STEPS)
                .finalStep(true)
                .build());
        AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                "error", "超过最大步骤数 " + MAX_STEPS, null);
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
            if (!filtered.isEmpty()) {
                dynamicSection.append("你可以先查看目录了解可用能力，再决定使用哪些工具或技能。");
            }
        } else {
            dynamicSection.append("由于可用工具和技能较多，请先查看上方目录了解可用能力。"
                    + "如需使用某个工具或技能，请调用 resolve 获取其完整定义。");
        }

        return String.format(SYSTEM_PROMPT_TEMPLATE, dynamicSection.toString());
    }

    /**
     * 构建第一轮的 toolSpecifications 列表。
     * 全量模式返回所有已注册工具；渐进披露模式仅返回 resolve 元工具。
     */
    private List<ToolSpecification> buildInitialToolSpecs(boolean progressiveMode) {
        if (!progressiveMode) {
            return toolRegistry.getToolSpecifications();
        }
        List<ToolSpecification> specs = new ArrayList<>();
        ToolSpecification resolveSpec = toolRegistry.getToolSpecification("resolve");
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
                                 int stepOrder, String stepType, String content, String toolName) {
        return AgentStep.builder()
                .chatMessageId(chatMessageId)
                .sessionId(sessionId)
                .stepOrder(stepOrder)
                .stepType(stepType)
                .content(content)
                .toolName(toolName)
                .build();
    }

    private AgentStepVO toStepVO(AgentStep step) {
        return AgentStepVO.builder()
                .id(step.getId())
                .stepOrder(step.getStepOrder())
                .stepType(step.getStepType())
                .content(step.getContent())
                .toolName(step.getToolName())
                .createdAt(step.getCreatedAt())
                .build();
    }

    //TODO：考虑是否需要保留这样的截断设计
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
