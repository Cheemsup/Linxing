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
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.linxing.linxing_agent.agent.tool.ToolSpec;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class AgentExecutor {

    private static final int MAX_STEPS = 10;
    private static final String AGENT_SYSTEM_PROMPT =
            "你是一个智能知识库助手，可以搜索用户的个人笔记和文档来回答问题。你的能力包括：\n"
                    + "- search_knowledge_base: 搜索用户知识库中的笔记内容\n\n"
                    + "工作流程：\n"
                    + "1. 先思考用户的问题需要哪些信息\n"
                    + "2. 如果问题涉及用户笔记中的内容，使用 search_knowledge_base 搜索\n"
                    + "3. 基于搜索结果给出准确、完整的回答\n"
                    + "4. 如果搜索结果不相关或不足，可以调整关键词再次搜索\n"
                    + "5. 仅依据搜索结果回答，不要编造信息\n\n"
                    + "回答时务必标注信息来源（文件名和标题路径）。";

    private final ToolRegistry toolRegistry;
    private final AgentStepMapper agentStepMapper;

    public AgentExecutor(ToolRegistry toolRegistry, AgentStepMapper agentStepMapper) {
        this.toolRegistry = toolRegistry;
        this.agentStepMapper = agentStepMapper;
    }

    /**
     * 执行Agent循环，驱动LLM与工具调用直到获得最终回答或超过最大步骤数
     * @param context
     * @param chatModel
     * @return
     */
    public AgentResult execute(AgentContext context, OpenAiChatModel chatModel) {
        List<AgentStepVO> recordedSteps = new ArrayList<>();

        context.getMemory().add(SystemMessage.from(AGENT_SYSTEM_PROMPT));//注入系统提示词

        int stepNumber = 0;
        AiMessage lastAiMessage = null;

        while (stepNumber < MAX_STEPS) {
            stepNumber++;
            log.info("[AgentExecutor] 步骤 {}/{} — 用户{} 会话{}",
                    stepNumber, MAX_STEPS, context.getUserId(), context.getSessionId());

            List<ToolSpecification> toolSpecs = toolRegistry.getToolSpecifications();//获取所有已注册工具的规格描述

            List<ChatMessage> currentMessages = context.getMemory().messages();
            //在request中包装系统提示词、对话上下文以及tool信息
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(currentMessages)
                    .toolSpecifications(toolSpecs)
                    .build();

            ChatResponse response;
            try {
                response = chatModel.chat(chatRequest);//调用LLM
            } catch (Exception e) {
                log.error("[AgentExecutor] LLM调用失败: {}", e.getMessage(), e);
                AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                        "error", "LLM调用失败: " + e.getMessage(), null);
                agentStepMapper.insert(step);//记录错误步骤
                recordedSteps.add(toStepVO(step));

                return AgentResult.builder()
                        .answer("抱歉，处理您的问题时出现了错误，请稍后重试。")
                        .sourcesJson("[]")
                        .steps(recordedSteps)
                        .totalSteps(stepNumber)
                        .build();
            }

            AiMessage aiMessage = response.aiMessage();

            if (aiMessage.hasToolExecutionRequests()) {//LLM在前面的chat结果中请求了调用工具，最后进入下一轮循环
                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
                log.info("[AgentExecutor] LLM请求调用 {} 个工具", toolRequests.size());

                context.getMemory().add(aiMessage);//将LLM的工具调用消息加入记忆

                List<ToolExecutionResultMessage> toolResults = new ArrayList<>();
                for (ToolExecutionRequest toolReq : toolRequests) {//对选中的工具逐一执行、获取结果
                    log.info("[AgentExecutor] 执行工具: {} args={}",
                            toolReq.name(), toolReq.arguments());

                    ToolCallRequest toolCallRequest = ToolCallRequest.builder()
                            .toolCallId(toolReq.id())
                            .toolName(toolReq.name())
                            .arguments(toolReq.arguments())
                            .build();

                    ToolSpec toolSpec = toolRegistry.getTool(toolReq.name());//从注册表查找工具规格
                    ToolCallResult toolResult;
                    if (toolSpec == null) {
                        toolResult = ToolCallResult.failure(toolReq.id(), toolReq.name(),
                                "未知工具: " + toolReq.name());
                    } else {
                        toolResult = toolSpec.execute(toolCallRequest);//委托执行工具调用
                    }

                    ToolExecutionRequest execReq = ToolExecutionRequest.builder()
                            .id(toolReq.id())
                            .name(toolReq.name())
                            .arguments(toolReq.arguments())
                            .build();
                    String resultText = toolResult.isSuccess()
                            ? toolResult.getResult()
                            : "Error: " + toolResult.getError();
                    ToolExecutionResultMessage resultMsg = ToolExecutionResultMessage.from(execReq, resultText);
                    toolResults.add(resultMsg);
                    context.getMemory().add(resultMsg);//工具结果加入记忆，供下一轮LLM参考

                    //记录工具调用步骤
                    String stepContent = toolResult.isSuccess()
                            ? toolReq.arguments()
                            : "Error: " + toolResult.getError();
                    AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                            "tool_call", stepContent, toolReq.name());
                    agentStepMapper.insert(step);
                    recordedSteps.add(toStepVO(step));

                    //记录工具返回结果步骤
                    AgentStep obsStep = buildStep(context.getSessionId(), null, stepNumber,
                            "tool_result",
                            toolResult.isSuccess() ? truncate(toolResult.getResult(), 2000) : toolResult.getError(),
                            toolReq.name());
                    agentStepMapper.insert(obsStep);
                    recordedSteps.add(toStepVO(obsStep));
                }
            } else {//无工具调用，表明已经获取了所有必要的信息内容，LLM直接返回文本回答，循环结束
                String answer = aiMessage.text();
                if (answer == null || answer.isBlank()) {
                    answer = "抱歉，无法生成回答。";
                }

                log.info("[AgentExecutor] 完成，共{}步，答案长度: {}字符",
                        stepNumber, answer.length());

                lastAiMessage = aiMessage;

                AgentStep step = buildStep(context.getSessionId(), null, stepNumber,
                        "final", truncate(answer, 2000), null);
                agentStepMapper.insert(step);//记录最终回答步骤
                recordedSteps.add(toStepVO(step));

                return AgentResult.builder()
                        .answer(answer)
                        .sourcesJson("[]")
                        .steps(recordedSteps)
                        .totalSteps(stepNumber)
                        .build();
            }
        }

        //如下是执行步骤超过限制的处理
        log.warn("[AgentExecutor] 超过最大步骤数 {}!", MAX_STEPS);
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
