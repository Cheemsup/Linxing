package org.linxing.linxing_agent.rag.pipeline;

import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.constant.DocumentStatus;
import org.linxing.linxing_agent.rag.constant.OperationType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ActivityLog;
import org.linxing.linxing_agent.rag.entity.Chunk;
import org.linxing.linxing_agent.rag.entity.DocRecord;
import org.linxing.linxing_agent.rag.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.rag.mapper.ChunkMapper;
import org.linxing.linxing_agent.rag.mapper.DocumentMapper;
import org.linxing.linxing_agent.rag.mapper.EmbeddingMapper;
import org.linxing.linxing_agent.rag.pipeline.handler.EmbeddingPersist;
import org.linxing.linxing_agent.rag.strategy.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分块管线服务，协调整个文档分块流程：策略选择完成chunk→责任链后处理（分类、标题提取、向量化等）。
 */
@Slf4j
@Component
public class ChunkPipelineCoordinator {

    private final ChunkStrategyFactory strategyFactory;
    private final ChunkProcessingPipeline pipeline;
    private final EmbeddingPersist embeddingPersist;
    private final ChunkMapper chunkMapper;
    private final EmbeddingMapper embeddingMapper;
    private final DocumentMapper documentMapper;
    private final ActivityLogMapper activityLogMapper;
    private final RagProperties ragProperties;

    public ChunkPipelineCoordinator(
            ChunkStrategyFactory strategyFactory,
            ChunkProcessingPipeline pipeline,
            EmbeddingPersist embeddingPersist,
            ChunkMapper chunkMapper,
            EmbeddingMapper embeddingMapper,
            DocumentMapper documentMapper,
            ActivityLogMapper activityLogMapper,
            RagProperties ragProperties) {
        this.strategyFactory = strategyFactory;
        this.pipeline = pipeline;
        this.embeddingPersist = embeddingPersist;
        this.chunkMapper = chunkMapper;
        this.embeddingMapper = embeddingMapper;
        this.documentMapper = documentMapper;
        this.activityLogMapper = activityLogMapper;
        this.ragProperties = ragProperties;
    }

    @Transactional
    public int processDocument(DocRecord doc, String fullText, Document langChainDoc) {
        ChunkStrategyContext ctx = ChunkStrategyContext.builder()
                .fileType(doc.getFileType())
                .fileName(doc.getFileName())
                .fullText(fullText)
                .document(langChainDoc)
                .maxChunkSize(ragProperties.getEmbedding().getChunkSize())
                .chunkOverlap(ragProperties.getEmbedding().getChunkOverlap())
                .chunkThreshold(ragProperties.getEmbedding().getChunkThreshold())
                .build();

        // 获取分块策略执行器
        ChunkStrategy strategy = strategyFactory.getStrategy(ctx);
        // 执行分块策略，获取分块结果
        //如果长度 ≤ maxChunkSize（800字）→ 直接生成 Level 2 小块
        //如果长度 > maxChunkSize → 先创建 Level 1 大块 → 调用 refinementPipeline.refine(sectionText) 细分为子块 → 对每个子块创建 Level 2 小块，parentChunkId = level1_Index（results列表中的索引位置）
        List<ChunkResult> results = strategy.execute(ctx);

        if (results.isEmpty()) {
            documentMapper.updateStatus(doc.getId(), DocumentStatus.FAILED);
            log.warn("文档 {} 分块结果为空", doc.getId());
            return 0;
        }

        // 合并相邻过小的 Level2 chunk，减少碎块数量与 context_weak 背景增强调用
        results = mergeSmallChunks(results);

        // Pre-pass: 按 results 列表顺序（文档原始顺序）为所有 result 预分配全局 sort_order
        Map<Integer, Integer> resultIndexToSortOrder = new LinkedHashMap<>();
        for (int i = 0; i < results.size(); i++) {
            resultIndexToSortOrder.put(i, i + 1);
        }

        // Pass 1: 先将所有 ChunkLevel=1（大分块）插入到数据库
        Map<Integer, Integer> resultIndexToDbId = new LinkedHashMap<>();
        for (int i = 0; i < results.size(); i++) {
            ChunkResult r = results.get(i);
            if (r.getChunkLevel() != null && r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1) {
                Chunk chunk = buildChunk(r, doc, null, resultIndexToSortOrder.get(i));
                chunkMapper.insert(chunk);
                resultIndexToDbId.put(i, chunk.getId());
            }
        }

        // Pass 2: 插入所有 ChunkLevel=2（小分块），并收集所有分块
        List<Chunk> allChunks = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : resultIndexToDbId.entrySet()) {
            Chunk chunk = buildChunk(results.get(entry.getKey()), doc, null, resultIndexToSortOrder.get(entry.getKey()));
            chunk.setId(entry.getValue());
            allChunks.add(chunk);
        }

        for (int i = 0; i < results.size(); i++) {
            ChunkResult r = results.get(i);
            if (r.getChunkLevel() == null || r.getChunkLevel() != RagParameters.CHUNK_LEVEL_1) {
                Integer parentDbId = (r.getParentChunkId() != null)
                        ? resultIndexToDbId.get(r.getParentChunkId()) : null;
                Chunk chunk = buildChunk(r, doc, parentDbId, resultIndexToSortOrder.get(i));
                chunkMapper.insert(chunk);
                allChunks.add(chunk);
            }
        }

        // Pass 3: 对于allChunks中的每个分块，做后续的titlePath提取、tsContent设置、向量化（大块不参与）等等处理
        for (Chunk chunk : allChunks) {
            ChunkProcessingContext pCtx = ChunkProcessingContext.builder()
                    .chunk(chunk)
                    .document(doc)
                    .fullDocumentText(fullText)
                    .shouldPersist(true)
                    .build();
            pipeline.execute(pCtx);//责任链方式执行分块向量化等后续处理
            chunkMapper.update(chunk);
        }

        // 刷新嵌入数据
        embeddingPersist.flush();

        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setChunkStrategy(strategy.getClass().getSimpleName());
        // 更新文档状态
        documentMapper.update(doc);

        // 记录操作日志
        activityLogMapper.insert(ActivityLog.builder()
                .userId(doc.getUserId())
                .actionType(OperationType.ACTION_TYPE_UPLOAD)
                .targetType(RagParameters.TARGET_TYPE_DOCUMENT)
                .targetId(String.valueOf(doc.getId()))
                .details("{\"chunks\":" + allChunks.size()
                        + ",\"fileName\":\"" + escapeJson(doc.getFileName())
                        + "\",\"strategy\":\"" + strategy.getClass().getSimpleName() + "\"}")
                .createdAt(OffsetDateTime.now())
                .build());

        // 记录处理完成日志
        log.info("文档 {} 处理完成，策略: {}，生成 {} 个chunk",
                doc.getId(), strategy.getClass().getSimpleName(), allChunks.size());

        return allChunks.size();
    }

    @Transactional
    public void deleteByDocumentId(Integer userId, Integer documentId) {
        // 查询文档下的所有分块
        List<Chunk> chunks = chunkMapper.findByDocumentId(documentId);
        if (!chunks.isEmpty()) {
            // 提取分块ID列表
            List<Integer> chunkIds = chunks.stream().map(Chunk::getId).toList();
            // 删除分块关联的嵌入数据
            embeddingMapper.deleteByChunkIds(chunkIds);
            // 删除文档下的所有分块
            chunkMapper.deleteByDocumentId(documentId);
        }
        // 删除文档记录
        documentMapper.deleteById(documentId);

        // 记录删除操作日志
        activityLogMapper.insert(ActivityLog.builder()
                .userId(userId)
                .actionType(OperationType.ACTION_TYPE_DELETE)
                .targetType(RagParameters.TARGET_TYPE_DOCUMENT)
                .targetId(String.valueOf(documentId))
                .createdAt(OffsetDateTime.now())
                .build());
    }

    /**
     * 合并相邻的过小 Level2 chunk，减少碎块数量与 context_weak 背景增强调用。
     *
     * 合并约束：
     * - 仅 Level2（小块）且 chunkText 长度 < minChunkSize 的候选参与合并
     * - 相邻且 parentChunkId 相同（同源：同一父块下，或都无父块）才能合并
     * - 合并后总长度 ≤ maxChunkSize，否则切分多个合并块
     * - Level1（大块）原样保留，作为 parentChunkId 引用锚点
     *
     * 合并会重建列表，并修正所有 parentChunkId 的 index 引用（old index → new index）。
     */
    private List<ChunkResult> mergeSmallChunks(List<ChunkResult> results) {
        int minChunkSize = ragProperties.getEmbedding().getMinChunkSize();
        int maxChunkSize = ragProperties.getEmbedding().getChunkSize();
        // minChunkSize <= 0 视为关闭合并
        if (minChunkSize <= 0) {
            return results;
        }

        List<ChunkResult> merged = new ArrayList<>();
        Map<Integer, Integer> oldToNew = new HashMap<>(); // 原 index → 新 index，用于修正 parentChunkId
        List<IndexedResult> buffer = new ArrayList<>(); // 待合并候选（连续、同源的小 Level2）

        for (int i = 0; i < results.size(); i++) {
            ChunkResult r = results.get(i);
            boolean mergeable = isMergeable(r, minChunkSize);
            // parentChunkId 变化或候选不可合并时，先刷新缓冲区
            boolean parentChanged = !buffer.isEmpty()
                    && !Objects.equals(buffer.get(0).result().getParentChunkId(), r.getParentChunkId());
            if (!buffer.isEmpty() && (!mergeable || parentChanged)) {
                flushMergeBuffer(buffer, maxChunkSize, merged, oldToNew);
                buffer = new ArrayList<>();
            }

            if (mergeable) {
                buffer.add(new IndexedResult(i, r));
            } else {
                // 不可合并（Level1 或 ≥ minChunkSize 的 Level2）原样保留，记录 index 映射
                oldToNew.put(i, merged.size());
                merged.add(r);
            }
        }
        if (!buffer.isEmpty()) {
            flushMergeBuffer(buffer, maxChunkSize, merged, oldToNew);
        }

        // 修正所有 parentChunkId：原 index → 新 index
        for (ChunkResult r : merged) {
            if (r.getParentChunkId() != null) {
                Integer newIdx = oldToNew.get(r.getParentChunkId());
                if (newIdx != null) {
                    r.setParentChunkId(newIdx);
                } else {
                    // 理论不会发生（被引用的 parent 是 Level1，必已记录映射）；防御性置空避免越界
                    log.warn("合并 chunk 时 parent index {} 映射缺失，置为无父块", r.getParentChunkId());
                    r.setParentChunkId(null);
                }
            }
        }

        log.debug("文档小 chunk 合并：{} → {}", results.size(), merged.size());
        return merged;
    }

    /** 判断 chunk 是否可作为合并候选：Level2（非 Level1）且文本长度小于 minChunkSize */
    private boolean isMergeable(ChunkResult r, int minChunkSize) {
        // Level1（大块）不参与合并，需作为 parentChunkId 引用锚点保留
        if (r.getChunkLevel() != null && r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1) {
            return false;
        }
        int len = r.getChunkText() == null ? 0 : r.getChunkText().length();
        return len > 0 && len < minChunkSize;
    }

    /**
     * 刷新待合并缓冲区：贪心累积候选，合并后超 maxChunkSize 则切出当前合并块。
     * 合并块复用首候选对象（直接 setChunkText），元数据（titlePath/sourceStrategy 等）保留首候选的。
     * 仅记录每个合并块首候选的 old index 到 oldToNew（被合并的其他候选不会被 parentChunkId 引用）。
     */
    private void flushMergeBuffer(List<IndexedResult> buffer, int maxChunkSize,
                                  List<ChunkResult> merged, Map<Integer, Integer> oldToNew) {
        final int sepLen = 2; // 合并分隔符 "\n\n" 长度，用于容量估算
        ChunkResult head = null; // 当前合并块（复用首候选对象）
        StringBuilder text = null;
        int headOldIdx = -1;

        for (IndexedResult ir : buffer) {
            ChunkResult r = ir.result();
            int chunkLen = r.getChunkText() == null ? 0 : r.getChunkText().length();
            int addLen = (text == null ? 0 : sepLen) + chunkLen;

            if (head != null && text.length() + addLen > maxChunkSize) {
                // 加入当前候选会超长，先切出已累积的合并块
                head.setChunkText(text.toString());
                oldToNew.put(headOldIdx, merged.size());
                merged.add(head);
                // 以当前候选开启新合并块
                head = r;
                text = new StringBuilder(r.getChunkText());
                headOldIdx = ir.oldIdx();
            } else if (head == null) {
                head = r;
                text = new StringBuilder(r.getChunkText());
                headOldIdx = ir.oldIdx();
            } else {
                text.append("\n\n").append(r.getChunkText());
            }
        }
        // 输出最后一个合并块
        if (head != null) {
            head.setChunkText(text.toString());
            oldToNew.put(headOldIdx, merged.size());
            merged.add(head);
        }
    }

    /** 合并过程内部用：携带候选在原 results 列表中的 old index */
    private record IndexedResult(int oldIdx, ChunkResult result) {
    }

    private Chunk buildChunk(ChunkResult r, DocRecord doc, Integer parentDbId, int sortOrder) {
        return Chunk.builder()
                .userId(doc.getUserId())
                .documentId(doc.getId())
                .chunkText(r.getChunkText())
                .parentChunkId(parentDbId)
                .chunkLevel(r.getChunkLevel() != null ? r.getChunkLevel() : RagParameters.CHUNK_LEVEL_2)
                .chunkType(r.getChunkType() != null ? r.getChunkType() : ChunkType.GENERAL)
                .titlePath(r.getTitlePath())
                .sourceStrategy(r.getSourceStrategy())
                .isSearchable(r.getChunkLevel() == null || r.getChunkLevel() == RagParameters.CHUNK_LEVEL_2)
                .sortOrder(sortOrder)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
