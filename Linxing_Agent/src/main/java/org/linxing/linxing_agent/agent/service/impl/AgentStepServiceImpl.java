package org.linxing.linxing_agent.agent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * P3 起 steps 读取改为 session 粒度 Mirror（mirror:steps:{sessionId}），旧 agent:steps:{messageId} String 停用。
 * <p>端点保持 messageId 入参不变：内部 selectById 解析 sessionId → HGETALL mirror:steps → 按 chatMessageId 内存过滤；
 * miss/异常 → DB selectByChatMessageId 兜底（correctness 不依赖 Redis）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStepServiceImpl {

    private final AgentStepMapper agentStepMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final IRuntimeMirrorService runtimeMirrorService;

    /**
     * 按消息ID加载 agent 步骤：优先读 session 粒度 Mirror，按 chatMessageId 内存过滤；miss → DB 兜底。
     */
    public List<AgentStepVO> getStepsByMessageId(Integer messageId) {
        org.linxing.linxing_agent.agent.entity.ChatMessage msg = chatMessageMapper.selectById(messageId);
        if (msg == null || msg.getSessionId() == null) {
            return List.of();
        }
        Integer sessionId = msg.getSessionId();

        // Mirror：HGETALL mirror:steps:{sessionId}，按 chatMessageId 内存过滤
        List<AgentStep> all = runtimeMirrorService.loadSteps(sessionId);
        if (all != null) {
            return all.stream()
                    .filter(s -> messageId.equals(s.getChatMessageId()))
                    .map(this::toVO)
                    .toList();
        }

        // Mirror miss/异常 → DB selectByChatMessageId 兜底
        return agentStepMapper.selectByChatMessageId(messageId)
                .stream()
                .map(this::toVO)
                .toList();
    }

    private AgentStepVO toVO(org.linxing.linxing_agent.agent.entity.AgentStep step) {
        Map<String, Object> stepData = step.getStepData();
        String label = extractDisplayLabel(stepData);
        return AgentStepVO.builder()
                .id(step.getId())
                .stepOrder(step.getStepOrder())
                .stepType(step.getStepType())
                .content(step.getContent())
                .label(label)
                .stepData(stepData)
                // 0724 修复：回填层级字段——前端 buildStepTree 据此重建 sub_agent/工具调用父子树，
                // 缺失会导致历史回看全部扁平化（所有节点 parentStepId 为 null 进 roots）。
                .parentStepId(step.getParentStepId())
                .agentId(step.getAgentId())
                .createdAt(step.getCreatedAt())
                .build();
    }

    private String extractDisplayLabel(Map<String, Object> stepData) {
        if (stepData == null) {
            return null;
        }
        Object value = stepData.get(StepRecorder.KEY_DISPLAY_LABEL);
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }
}
