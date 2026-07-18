package org.linxing.linxing_agent.agent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.constant.RedisKeysPrefix;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 运行时镜像服务实现（thePlan P3 / nowRefact §4.2~§4.4）。
 * <p>
 * 沿用 {@code ChatMessageCacheServiceImpl} 同模式：{@link StringRedisTemplate} + 注入式
 * {@link ObjectMapper}（Jackson 3.x），手动 {@code writeValueAsString/readValue}，
 * {@code opsForHash} 操作 Hash。所有方法 try-catch + log.warn，绝不抛出（降级契约见接口）。
 * <p>
 * Mirror payload 复用 {@link ChatMessage}/{@link AgentStep} 实体直接序列化——二者均携带
 * Recovery 所需全字段（{@code nearestSummaryMessageId}/{@code chatMessageId}/{@code sessionId}），
 * 无需另建 DTO；OffsetDateTime + Map<String,Object> 经注入 ObjectMapper round-trip 已由旧
 * AgentStepServiceImpl lazy cache 验证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeMirrorServiceImpl implements IRuntimeMirrorService {

    private final StringRedisTemplate redisTemplate;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    @Override
    public List<ChatMessage> loadMessages(Integer sessionId) {
        String key = RedisKeysPrefix.MIRROR_MSGS + sessionId;
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries.isEmpty()) {
                return null;
            }
            List<ChatMessage> result = new ArrayList<>(entries.size());
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    ChatMessage msg = objectMapper.readValue((String) entry.getValue(), ChatMessage.class);
                    result.add(msg);
                } catch (Exception e) {
                    log.warn("[Mirror] 反序列化 mirror:msgs 失败, msgId={}: {}", entry.getKey(), e.getMessage());
                }
            }
            result.sort(Comparator.nullsFirst(Comparator.comparing(ChatMessage::getCreatedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()))));
            return result;
        } catch (Exception e) {
            log.warn("[Mirror] 读取 mirror:msgs 失败, sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<AgentStep> loadSteps(Integer sessionId) {
        String key = RedisKeysPrefix.MIRROR_STEPS + sessionId;
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries.isEmpty()) {
                return null;
            }
            List<AgentStep> result = new ArrayList<>(entries.size());
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    AgentStep step = objectMapper.readValue((String) entry.getValue(), AgentStep.class);
                    result.add(step);
                } catch (Exception e) {
                    log.warn("[Mirror] 反序列化 mirror:steps 失败, stepId={}: {}", entry.getKey(), e.getMessage());
                }
            }
            // 与 AgentStepMapper.selectBySessionId 排序一致：step_order ASC, id ASC
            result.sort(Comparator.comparing(AgentStep::getStepOrder, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(AgentStep::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
            return result;
        } catch (Exception e) {
            log.warn("[Mirror] 读取 mirror:steps 失败, sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public void appendMessage(Integer sessionId, ChatMessage message) {
        if (message == null || message.getId() == null) {
            return;
        }
        String key = RedisKeysPrefix.MIRROR_MSGS + sessionId;
        int ttl = ragProperties.getCache().getMirrorTtl();
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForHash().put(key, String.valueOf(message.getId()), json);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Mirror] 写入 mirror:msgs 失败, sessionId={}, msgId={}: {}",
                    sessionId, message.getId(), e.getMessage());
        }
    }

    @Override
    public void appendStep(Integer sessionId, AgentStep step) {
        if (step == null || step.getId() == null) {
            return;
        }
        String key = RedisKeysPrefix.MIRROR_STEPS + sessionId;
        int ttl = ragProperties.getCache().getMirrorTtl();
        try {
            String json = objectMapper.writeValueAsString(step);
            redisTemplate.opsForHash().put(key, String.valueOf(step.getId()), json);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Mirror] 写入 mirror:steps 失败, sessionId={}, stepId={}: {}",
                    sessionId, step.getId(), e.getMessage());
        }
    }

    @Override
    public void replaceAll(Integer sessionId, List<ChatMessage> messages, List<AgentStep> steps) {
        String msgsKey = RedisKeysPrefix.MIRROR_MSGS + sessionId;
        String stepsKey = RedisKeysPrefix.MIRROR_STEPS + sessionId;
        int ttl = ragProperties.getCache().getMirrorTtl();
        try {
            // 先 DEL 两 Hash，避免旧字段残留（cache-aside 全量重建语义）
            redisTemplate.delete(List.of(msgsKey, stepsKey));

            if (messages != null && !messages.isEmpty()) {
                Map<String, String> msgEntries = new HashMap<>(messages.size());
                for (ChatMessage msg : messages) {
                    if (msg == null || msg.getId() == null) continue;
                    msgEntries.put(String.valueOf(msg.getId()), objectMapper.writeValueAsString(msg));
                }
                if (!msgEntries.isEmpty()) {
                    redisTemplate.opsForHash().putAll(msgsKey, msgEntries);
                    redisTemplate.expire(msgsKey, ttl, TimeUnit.SECONDS);
                }
            }
            if (steps != null && !steps.isEmpty()) {
                Map<String, String> stepEntries = new HashMap<>(steps.size());
                for (AgentStep step : steps) {
                    if (step == null || step.getId() == null) continue;
                    stepEntries.put(String.valueOf(step.getId()), objectMapper.writeValueAsString(step));
                }
                if (!stepEntries.isEmpty()) {
                    redisTemplate.opsForHash().putAll(stepsKey, stepEntries);
                    redisTemplate.expire(stepsKey, ttl, TimeUnit.SECONDS);
                }
            }
        } catch (Exception e) {
            log.warn("[Mirror] replaceAll 失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void deleteSession(Integer sessionId) {
        String msgsKey = RedisKeysPrefix.MIRROR_MSGS + sessionId;
        String stepsKey = RedisKeysPrefix.MIRROR_STEPS + sessionId;
        try {
            redisTemplate.delete(List.of(msgsKey, stepsKey));
        } catch (Exception e) {
            log.warn("[Mirror] 删除会话镜像失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
