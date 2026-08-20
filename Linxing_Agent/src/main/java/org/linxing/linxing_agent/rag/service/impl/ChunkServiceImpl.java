package org.linxing.linxing_agent.rag.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.entity.Chunk;
import org.linxing.linxing_agent.rag.entity.DocRecord;
import org.linxing.linxing_agent.rag.mapper.ChunkMapper;
import org.linxing.linxing_agent.rag.mapper.DocumentMapper;
import org.linxing.linxing_agent.rag.service.IChunkService;
import org.linxing.linxing_agent.rag.storage.ImagePathSigner;
import org.linxing.linxing_agent.rag.vo.ChunkContextVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkServiceImpl implements IChunkService {

    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final ImagePathSigner imagePathSigner;

    @Override
    public ChunkContextVO getChunkContext(Integer chunkId, Integer userId) {
        Chunk chunk = chunkMapper.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk不存在"));

        if (!chunk.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该Chunk");
        }

        DocRecord doc = documentMapper.findById(chunk.getDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("所属文档不存在"));

        ChunkContextVO.ChunkContextVOBuilder builder = ChunkContextVO.builder()
                .chunkId(chunk.getId())
                .chunkText(chunk.getChunkText())
                .nodeMetadata(signCopy(chunk.getNodeMetadata()))
                .documentId(doc.getId())
                .fileName(doc.getFileName());

        if (chunk.getParentChunkId() != null) {
            Chunk parent = chunkMapper.findById(chunk.getParentChunkId()).orElse(null);
            if (parent != null) {
                builder.parentChunk(ChunkContextVO.ParentChunkInfo.builder()
                        .chunkId(parent.getId())
                        .titlePath(parent.getTitlePath())
                        .chunkText(parent.getChunkText())
                        .nodeMetadata(signCopy(parent.getNodeMetadata()))
                        .build());

                List<Chunk> siblings = chunkMapper.findSiblingsByParentChunkId(chunk.getParentChunkId());
                List<ChunkContextVO.SiblingChunkInfo> siblingInfos = siblings.stream()
                        .map(s -> ChunkContextVO.SiblingChunkInfo.builder()
                                .chunkId(s.getId())
                                .textPreview(truncate(s.getChunkText(), 100))
                                .build())
                        .toList();
                builder.siblingChunks(siblingInfos);
            }
        } else {
            List<Chunk> children = chunkMapper.findSiblingsByParentChunkId(chunkId);
            if (!children.isEmpty()) {
                List<ChunkContextVO.SiblingChunkInfo> childInfos = children.stream()
                        .map(c -> ChunkContextVO.SiblingChunkInfo.builder()
                                .chunkId(c.getId())
                                .textPreview(truncate(c.getChunkText(), 100))
                                .build())
                        .toList();
                builder.siblingChunks(childInfos);
            }
        }

        if (builder.build().getSiblingChunks() == null) {
            builder.siblingChunks(List.of());
        }

        return builder.build();
    }

    /**
     * 深拷贝 nodeMetadata 后把 imagePath（imageKey）替换为签名 URL。
     * 拷贝避免写坏 chunkMapper 复用/缓存的共享对象（DB 存 key，线上才给签名 URL）。
     */
    private List<Map<String, Object>> signCopy(List<Map<String, Object>> nodeMetadata) {
        if (nodeMetadata == null || nodeMetadata.isEmpty()) {
            return nodeMetadata != null ? nodeMetadata : List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>(nodeMetadata.size());
        for (Map<String, Object> meta : nodeMetadata) {
            copy.add(meta == null ? null : new LinkedHashMap<>(meta));
        }
        imagePathSigner.signMetadataImages(copy, System.currentTimeMillis());
        return copy;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
