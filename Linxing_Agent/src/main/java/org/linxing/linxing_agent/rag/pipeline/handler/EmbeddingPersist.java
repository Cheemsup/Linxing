package org.linxing.linxing_agent.rag.pipeline.handler;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.entity.Chunk;
import org.linxing.linxing_agent.rag.entity.DocRecord;
import org.linxing.linxing_agent.rag.entity.FullEmbeddingRecord;
import org.linxing.linxing_agent.rag.mapper.EmbeddingMapper;
import org.linxing.linxing_agent.rag.pipeline.ChunkProcessingContext;
import org.linxing.linxing_agent.rag.pipeline.ChunkProcessingHandler;
import org.linxing.linxing_agent.rag.utils.VectorUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量嵌入持久化处理器（Order=5），对可检索 Chunk 生成向量嵌入并批量写入数据库，支持缓冲区自动刷新。
 */
@Slf4j
@Component
@Order(5)
public class EmbeddingPersist implements ChunkProcessingHandler {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingMapper embeddingMapper;

    private final List<FullEmbeddingRecord> buffer = new ArrayList<>();
    private static final int BATCH_SIZE = 20; // 批量写入阈值，累积满 20 条即刷新到数据库

    public EmbeddingPersist(EmbeddingModel embeddingModel, EmbeddingMapper embeddingMapper) {
        this.embeddingModel = embeddingModel;
        this.embeddingMapper = embeddingMapper;
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public boolean handle(ChunkProcessingContext context) {
        Chunk chunk = context.getChunk();

        if (!Boolean.TRUE.equals(chunk.getIsSearchable())) {
            log.debug("Chunk {} 不参与检索，跳过嵌入生成", chunk.getId());
            return true;
        }

        try {
            String embeddingText = buildEmbeddingText(chunk);
            // 调用嵌入模型将文本转为 512 维向量（BGE-small-zh）
            Embedding embedding = embeddingModel.embed(embeddingText).content();
            // 将 float[] 向量序列化为 PostgreSQL vector 兼容格式，如 [0.1,0.2,...]
            String vectorString = VectorUtils.toArray(embedding.vector());
            //构造向量记录的元数据 JSON
            String metadataJson = buildMetadataJson(chunk, context.getDocument());

            FullEmbeddingRecord record = new FullEmbeddingRecord(
                    null,
                    chunk.getUserId(),
                    chunk.getDocumentId(),
                    chunk.getId(),
                    vectorString,
                    embeddingText,
                    metadataJson
            );

            buffer.add(record);

            // 达到批处理阈值时自动刷新，减少数据库 IO 次数
            if (buffer.size() >= BATCH_SIZE) {
                flush();
            }

            log.debug("Chunk {} 嵌入生成完成，向量维度: {}", chunk.getId(), embedding.vector().length);
        } catch (Exception e) {
            log.error("Chunk {} 嵌入生成失败: {}", chunk.getId(), e.getMessage());
        }

        return true;
    }

    // 批量刷新缓冲区中的向量记录到数据库
    public void flush() {
        if (!buffer.isEmpty()) {
            try {
                embeddingMapper.batchInsertEmbeddings(new ArrayList<>(buffer));
                log.debug("批量持久化 {} 条向量记录", buffer.size());
            } catch (Exception e) {
                log.error("批量持久化向量失败: {}", e.getMessage());
            }
            buffer.clear();
        }
    }

    // 拼装向量化文本: contextPrefix + titlePath + chunkText
    // contextPrefix 由 LLM 对弱上下文片段补充的背景描述，titlePath 为标题路径，共同增强语义密度
    private String buildEmbeddingText(Chunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (chunk.getContextPrefix() != null && !chunk.getContextPrefix().isEmpty()) {
            sb.append(chunk.getContextPrefix()).append(" ");
        }
        if (chunk.getTitlePath() != null && !chunk.getTitlePath().isEmpty()) {
            sb.append(chunk.getTitlePath()).append(" ");
        }
        sb.append(chunk.getChunkText());
        return sb.toString();
    }

    // 构造向量记录的元数据 JSON，便于检索时还原上下文信息（类型、路径、来源文件等）
    private String buildMetadataJson(Chunk chunk, DocRecord document) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"user_id\":\"").append(chunk.getUserId()).append("\",");
        sb.append("\"document_id\":\"").append(chunk.getDocumentId()).append("\",");
        sb.append("\"chunk_id\":\"").append(chunk.getId()).append("\",");
        sb.append("\"chunk_type\":\"").append(escapeJson(chunk.getChunkType())).append("\",");
        if (chunk.getParentChunkId() != null) {
            sb.append("\"parent_chunk_id\":\"").append(chunk.getParentChunkId()).append("\",");
        }
        if (chunk.getTitlePath() != null) {
            sb.append("\"title_path\":\"").append(escapeJson(chunk.getTitlePath())).append("\",");
        }
        if (chunk.getSourceStrategy() != null) {
            sb.append("\"strategy\":\"").append(escapeJson(chunk.getSourceStrategy())).append("\",");
        }
        if (document != null && document.getFileName() != null) {
            sb.append("\"file_name\":\"").append(escapeJson(document.getFileName())).append("\",");
        }
        sb.append("\"chunk_level\":").append(chunk.getChunkLevel());
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
