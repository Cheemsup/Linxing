package org.linxing.linxing_agent.agent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;
import org.linxing.linxing_agent.rag.constant.RedisKeysPrefix;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * TODO:这个service的性质更倾向于utils，考虑重构
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStepServiceImpl {

    private final AgentStepMapper agentStepMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final int CACHE_TTL_SECONDS = 3600;

    /**
     * 按消息ID懒加载agent步骤，优先读缓存
     */
    public List<AgentStepVO> getStepsByMessageId(Integer messageId) {
        String key = RedisKeysPrefix.AGENT_STEPS + messageId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, AgentStepVO.class));
            }
        } catch (Exception e) {
            log.warn("读取步骤缓存失败, messageId={}: {}", messageId, e.getMessage());
        }

        List<AgentStepVO> steps = agentStepMapper.selectByChatMessageId(messageId)
                .stream()
                .map(this::toVO)
                .toList();

        try {
            String json = objectMapper.writeValueAsString(steps);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入步骤缓存失败, messageId={}: {}", messageId, e.getMessage());
        }

        return steps;
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
                .createdAt(step.getCreatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
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
