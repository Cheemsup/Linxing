package org.linxing.linxing_agent.utils;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.ChunkTypeConstants;
import org.linxing.linxing_agent.constant.DocumentStatusConstants;
import org.linxing.linxing_agent.constant.RagConstants;
import org.linxing.linxing_agent.config.RagProperties;
import org.linxing.linxing_agent.entity.ActivityLog;
import org.linxing.linxing_agent.entity.Chunk;
import org.linxing.linxing_agent.entity.FullEmbeddingRecord;
import org.linxing.linxing_agent.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.mapper.ChunkMapper;
import org.linxing.linxing_agent.mapper.DocumentMapper;
import org.linxing.linxing_agent.mapper.EmbeddingMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingHelper {

    private final EmbeddingModel embeddingModel;
    private final RagProperties ragProperties;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final ActivityLogMapper activityLogMapper;
    private final EmbeddingMapper embeddingMapper;

    @Transactional
    public int embedDocument(Integer userId, Integer documentId, String fileName, Document document) {
        DocumentSplitter splitter = DocumentSplitters.recursive(
                ragProperties.getEmbedding().getChunkSize(),
                ragProperties.getEmbedding().getChunkOverlap()
        );

        List<TextSegment> segments = splitter.split(document);
        log.info("文档分块完成，共 {} 个片段", segments.size());

        if (segments.isEmpty()) {
            documentMapper.updateStatus(documentId, DocumentStatusConstants.FAILED);
            return 0;
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        List<FullEmbeddingRecord> embedRecords = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);

            Chunk chunk = Chunk.builder()
                    .userId(userId)
                    .documentId(documentId)
                    .chunkText(segment.text())
                    .chunkLevel(RagConstants.CHUNK_LEVEL_2)
                    .chunkType(ChunkTypeConstants.GENERAL)
                    .sourceStrategy("RecursiveChunkStrategy")
                    .isSearchable(true)
                    .createdAt(OffsetDateTime.now())
                    .build();
            chunkMapper.insert(chunk);

            String metadataJson = buildMetadataJson(userId, documentId, fileName, segment, chunk.getId());

            embedRecords.add(new FullEmbeddingRecord(
                    null,
                    userId,
                    documentId,
                    chunk.getId(),
                    VectorUtils.toArray(embeddings.get(i).vector()),
                    segment.text(),
                    metadataJson
            ));
        }

        if (!embedRecords.isEmpty()) {
            embeddingMapper.batchInsertEmbeddings(embedRecords);
        }

        documentMapper.updateStatus(documentId, DocumentStatusConstants.COMPLETED);

        activityLogMapper.insert(ActivityLog.builder()
                .userId(userId)
                .actionType(RagConstants.ACTION_TYPE_UPLOAD)
                .targetType(RagConstants.TARGET_TYPE_DOCUMENT)
                .targetId(String.valueOf(documentId))
                .details("{\"chunks\":" + embedRecords.size() + ",\"fileName\":\"" + fileName + "\"}")
                .createdAt(OffsetDateTime.now())
                .build());

        log.info("向量存储完成，已存入 {} 条向量，关联文档ID: {}", embedRecords.size(), documentId);
        return embedRecords.size();
    }

    @Transactional
    public void deleteByDocumentId(Integer userId, Integer documentId) {
        List<Chunk> chunks = chunkMapper.findByDocumentId(documentId);
        if (!chunks.isEmpty()) {
            List<Integer> chunkIds = chunks.stream().map(Chunk::getId).toList();
            embeddingMapper.deleteByChunkIds(chunkIds);
            chunkMapper.deleteByDocumentId(documentId);
        }
        documentMapper.deleteById(documentId);

        activityLogMapper.insert(ActivityLog.builder()
                .userId(userId)
                .actionType(RagConstants.ACTION_TYPE_DELETE)
                .targetType(RagConstants.TARGET_TYPE_DOCUMENT)
                .targetId(String.valueOf(documentId))
                .createdAt(OffsetDateTime.now())
                .build());
    }

    private String buildMetadataJson(Integer userId, Integer documentId, String fileName, TextSegment segment, Integer chunkId) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"user_id\":\"").append(userId).append("\",");
        sb.append("\"document_id\":\"").append(documentId).append("\",");
        sb.append("\"file_name\":\"").append(escapeJson(fileName)).append("\",");
        sb.append("\"chunk_id\":\"").append(chunkId).append("\",");
        sb.append("\"index\":\"").append(segment.metadata().getString("index") != null ? segment.metadata().getString("index") : "0").append("\"");
        sb.append("}");
        return sb.toString();
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
