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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对用户问题和回答的语义缓存。
 * 向量相似度搜索（VADD/VSIM）与缓存数据存储（SET/GET）分离
 *
 * TODO：后续考虑其是否还有存在的价值。假如用户希望定制两份难度不一的plan？
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheServiceImpl {

    static final String DATA_KEY_PREFIX = "semantic_cache:data:";

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
            Map<String, Double> results =
                    jedisPooled.vsimWithScores(key, queryEmbedding, params);

            if (results == null || results.isEmpty()) {
                log.info("[语义缓存] 用户{}未命中, key={}", userId, key);
                return CacheResult.miss();
            }

            Map.Entry<String, Double> top = results.entrySet().iterator().next();
            String elementId = top.getKey();
            double score = top.getValue();

            if (score < threshold) {
                log.info("[语义缓存] 用户{}相似度不足: score={}, threshold={}, element={}",
                        userId, score, threshold, elementId);
                return CacheResult.miss();
            }

            String dataKey = buildDataKey(elementId);
            String cacheJson = jedisPooled.get(dataKey);
            if (cacheJson == null) {
                log.warn("[语义缓存] 用户{}命中但缓存数据丢失, element={}", userId, elementId);
                jedisPooled.vrem(key, elementId);
                return CacheResult.miss();
            }

            CacheEntry entry = objectMapper.readValue(cacheJson, CacheEntry.class);
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

            String cacheJson = objectMapper.writeValueAsString(entry);

            VAddParams addParams = new VAddParams();
            String quantization = ragProperties.getCache().getSemanticCache().getQuantization();
            if ("Q8".equalsIgnoreCase(quantization)) {
                addParams.q8();
            } else if ("BIN".equalsIgnoreCase(quantization)) {
                addParams.bin();
            } else if ("NOQUANT".equalsIgnoreCase(quantization)) {
                addParams.noQuant();
            }

            jedisPooled.vadd(key, queryEmbedding, elementId, addParams);
            jedisPooled.set(buildDataKey(elementId), cacheJson);
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
            long count = jedisPooled.vcard(key);
            if (count > 0) {
                List<String> allIds = jedisPooled.vrandmember(key, (int) count);
                for (String id : allIds) {
                    jedisPooled.del(buildDataKey(id));
                }
            }
            jedisPooled.del(key);
            log.info("[语义缓存] 用户{}缓存已清除, 删除{}条", userId, count);
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
                    jedisPooled.del(buildDataKey(id));
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

    private String buildDataKey(String elementId) {
        return DATA_KEY_PREFIX + elementId;
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
