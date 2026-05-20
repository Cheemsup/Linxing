package org.linxing.linxing_agent.rag.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.constant.RedisKeysPrefix;
import org.linxing.linxing_agent.rag.vo.ChatMessageVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageCacheService {

    private final StringRedisTemplate redisTemplate;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public List<ChatMessageVO> getMessages(Integer sessionId) {
        String key = RedisKeysPrefix.SESSION_MSGS + sessionId;
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries.isEmpty()) {
                return null;
            }
            List<ChatMessageVO> result = new ArrayList<>(entries.size());
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    ChatMessageVO vo = objectMapper.readValue((String) entry.getValue(), ChatMessageVO.class);
                    result.add(vo);
                } catch (Exception e) {
                    log.warn("反序列化缓存消息失败, msgId={}: {}", entry.getKey(), e.getMessage());
                }
            }
            result.sort((a, b) -> {
                if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                return a.getCreatedAt().compareTo(b.getCreatedAt());
            });
            return result;
        } catch (Exception e) {
            log.warn("读取会话消息缓存失败, sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public void putMessages(Integer sessionId, List<ChatMessageVO> messages) {
        String key = RedisKeysPrefix.SESSION_MSGS + sessionId;
        int ttl = ragProperties.getCache().getSessionMessagesTtl();
        try {
            Map<String, String> hashEntries = new java.util.HashMap<>();
            for (ChatMessageVO msg : messages) {
                String json = objectMapper.writeValueAsString(msg);
                hashEntries.put(String.valueOf(msg.getId()), json);
            }
            redisTemplate.opsForHash().putAll(key, hashEntries);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入会话消息缓存失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    public void appendMessage(Integer sessionId, ChatMessageVO message) {
        String key = RedisKeysPrefix.SESSION_MSGS + sessionId;
        int ttl = ragProperties.getCache().getSessionMessagesTtl();
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForHash().put(key, String.valueOf(message.getId()), json);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("追加消息到缓存失败, sessionId={}, msgId={}: {}", sessionId, message.getId(), e.getMessage());
        }
    }

    public void appendMessages(Integer sessionId, List<ChatMessageVO> messages) {
        String key = RedisKeysPrefix.SESSION_MSGS + sessionId;
        int ttl = ragProperties.getCache().getSessionMessagesTtl();
        try {
            Map<String, String> hashEntries = new java.util.HashMap<>();
            for (ChatMessageVO msg : messages) {
                String json = objectMapper.writeValueAsString(msg);
                hashEntries.put(String.valueOf(msg.getId()), json);
            }
            redisTemplate.opsForHash().putAll(key, hashEntries);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("追加消息到缓存失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    public void deleteSession(Integer sessionId) {
        String key = RedisKeysPrefix.SESSION_MSGS + sessionId;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除会话消息缓存失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    public void deleteMessages(Integer sessionId, List<Integer> messageIds) {
        String key = RedisKeysPrefix.SESSION_MSGS + sessionId;
        try {
            Object[] fields = messageIds.stream().map(String::valueOf).toArray();
            redisTemplate.opsForHash().delete(key, fields);
        } catch (Exception e) {
            log.warn("从缓存删除消息失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
