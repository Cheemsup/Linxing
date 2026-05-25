package org.linxing.linxing_agent.agent.service.impl;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.constant.RedisKeysPrefix;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.VAddParams;
import redis.clients.jedis.params.VSimParams;
import redis.clients.jedis.resps.VSimScoreAttribs;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对用户问题的语义缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final JedisPooled jedisPooled;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public CacheResult lookup(Integer userId, float[] queryEmbedding) {
        if (!isEnabled()) {
            return CacheResult.miss();
        }

        String key = buildKey(userId);
        double threshold = ragProperties.getCache().getSemanticCache().getThreshold();

        try {
            VSimParams params = new VSimParams().count(1);
            Map<String, VSimScoreAttribs> results =
                    jedisPooled.vsimWithScoresAndAttribs(key, queryEmbedding, params);

            if (results == null || results.isEmpty()) {
                log.info("[语义缓存] 用户{}未命中, key={}", userId, key);
                return CacheResult.miss();
            }

            Map.Entry<String, VSimScoreAttribs> top = results.entrySet().iterator().next();
            String elementId = top.getKey();
            double score = top.getValue().getScore();

            if (score < threshold) {
                log.info("[语义缓存] 用户{}相似度不足: score={}, threshold={}, element={}",
                        userId, score, threshold, elementId);
                return CacheResult.miss();
            }

            String attrJson = top.getValue().getAttributes();
            if (attrJson == null || attrJson.isBlank()) {
                log.warn("[语义缓存] 用户{}命中但属性为空, element={}", userId, elementId);
                return CacheResult.miss();
            }

            CacheEntry entry = objectMapper.readValue(attrJson, CacheEntry.class);
            log.info("[语义缓存] 用户{}命中! score={}, query={}, element={}",
                    userId, score, truncate(entry.getQueryText(), 50), elementId);
            return CacheResult.hit(entry, score);

        } catch (Exception e) {
            log.warn("[语义缓存] 查询失败, userId={}: {}", userId, e.getMessage());
            return CacheResult.miss();
        }
    }

    public void store(Integer userId, float[] queryEmbedding, String queryText,
                      String answer, String sourcesJson) {
        if (!isEnabled()) {
            return;
        }

        String key = buildKey(userId);
        String elementId = UUID.randomUUID().toString().replace("-", "");

        try {
            CacheEntry entry = new CacheEntry();
            entry.setQueryText(queryText);
            entry.setAnswer(answer);
            entry.setSources(sourcesJson);
            entry.setCreatedAt(System.currentTimeMillis());

            String attrJson = objectMapper.writeValueAsString(entry);

            VAddParams addParams = new VAddParams().setAttr(attrJson);
            String quantization = ragProperties.getCache().getSemanticCache().getQuantization();
            if ("Q8".equalsIgnoreCase(quantization)) {
                addParams.q8();
            } else if ("BIN".equalsIgnoreCase(quantization)) {
                addParams.bin();
            } else if ("NOQUANT".equalsIgnoreCase(quantization)) {
                addParams.noQuant();
            }

            jedisPooled.vadd(key, queryEmbedding, elementId, addParams);
            log.info("[语义缓存] 用户{}写入缓存, element={}, query={}",
                    userId, elementId, truncate(queryText, 50));

            enforceQuota(userId);

        } catch (Exception e) {
            log.warn("[语义缓存] 写入失败, userId={}: {}", userId, e.getMessage());
        }
    }

    public void clearUserCache(Integer userId) {
        if (!isEnabled()) {
            return;
        }

        String key = buildKey(userId);
        try {
            jedisPooled.del(key);
            log.info("[语义缓存] 用户{}缓存已清除", userId);
        } catch (Exception e) {
            log.warn("[语义缓存] 清除失败, userId={}: {}", userId, e.getMessage());
        }
    }

    private void enforceQuota(Integer userId) {
        String key = buildKey(userId);
        int maxEntries = ragProperties.getCache().getSemanticCache().getQuotaPerUser();

        try {
            long count = jedisPooled.vcard(key);
            if (count > maxEntries) {
                long toEvict = count - maxEntries;
                List<String> victims = jedisPooled.vrandmember(key, (int) toEvict);
                for (String id : victims) {
                    jedisPooled.vrem(key, id);
                }
                log.info("[语义缓存] 用户{}超额淘汰: count={}, max={}, 淘汰{}条",
                        userId, count, maxEntries, victims.size());
            }
        } catch (Exception e) {
            log.warn("[语义缓存] 额度检查失败, userId={}: {}", userId, e.getMessage());
        }
    }

    private boolean isEnabled() {
        return ragProperties.getCache().getSemanticCache().isEnabled();
    }

    private String buildKey(Integer userId) {
        return RedisKeysPrefix.SEMANTIC_CACHE + userId;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    @Data
    public static class CacheEntry {
        private String queryText;
        private String answer;
        private String sources;
        private long createdAt;
    }

    @Data
    public static class CacheResult {
        private boolean hit;
        private CacheEntry entry;
        private double score;

        public static CacheResult miss() {
            CacheResult r = new CacheResult();
            r.setHit(false);
            return r;
        }

        public static CacheResult hit(CacheEntry entry, double score) {
            CacheResult r = new CacheResult();
            r.setHit(true);
            r.setEntry(entry);
            r.setScore(score);
            return r;
        }
    }
}
