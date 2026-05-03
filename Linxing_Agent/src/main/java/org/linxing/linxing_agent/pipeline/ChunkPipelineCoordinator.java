package org.linxing.linxing_agent.pipeline;

import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.config.RagProperties;
import org.linxing.linxing_agent.constant.ChunkType;
import org.linxing.linxing_agent.constant.DocumentStatus;
import org.linxing.linxing_agent.constant.OperationType;
import org.linxing.linxing_agent.constant.RagParameters;
import org.linxing.linxing_agent.entity.ActivityLog;
import org.linxing.linxing_agent.entity.Chunk;
import org.linxing.linxing_agent.entity.DocRecord;
import org.linxing.linxing_agent.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.mapper.ChunkMapper;
import org.linxing.linxing_agent.mapper.DocumentMapper;
import org.linxing.linxing_agent.mapper.EmbeddingMapper;
import org.linxing.linxing_agent.pipeline.handler.EmbeddingPersist;
import org.linxing.linxing_agent.strategy.ChunkResult;
import org.linxing.linxing_agent.strategy.ChunkStrategy;
import org.linxing.linxing_agent.strategy.ChunkStrategyContext;
import org.linxing.linxing_agent.strategy.ChunkStrategyFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // Pass 1: 先将所有 ChunkLevel=1（大分块）插入到数据库
        int sortOrderCounter = 1;
        Map<Integer, Integer> resultIndexToDbId = new LinkedHashMap<>();
        for (int i = 0; i < results.size(); i++) {
            ChunkResult r = results.get(i);
            if (r.getChunkLevel() != null && r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1) {
                Chunk chunk = buildChunk(r, doc, null, sortOrderCounter++);
                chunkMapper.insert(chunk);
                resultIndexToDbId.put(i, chunk.getId());
            }
        }

        // Pass 2: 插入所有 ChunkLevel=2（小分块），并收集所有分块
        List<Chunk> allChunks = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : resultIndexToDbId.entrySet()) {
            Chunk chunk = buildChunk(results.get(entry.getKey()), doc, null, sortOrderCounter++);
            chunk.setId(entry.getValue());
            allChunks.add(chunk);
        }

        for (int i = 0; i < results.size(); i++) {
            ChunkResult r = results.get(i);
            if (r.getChunkLevel() == null || r.getChunkLevel() != RagParameters.CHUNK_LEVEL_1) {
                Integer parentDbId = (r.getParentChunkId() != null)
                        ? resultIndexToDbId.get(r.getParentChunkId()) : null;
                Chunk chunk = buildChunk(r, doc, parentDbId, sortOrderCounter++);
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
