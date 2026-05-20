package org.linxing.linxing_agent.rag.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.entity.Chunk;
import org.linxing.linxing_agent.rag.entity.DocRecord;
import org.linxing.linxing_agent.rag.mapper.ChunkMapper;
import org.linxing.linxing_agent.rag.mapper.DocumentMapper;
import org.linxing.linxing_agent.rag.service.IChunkService;
import org.linxing.linxing_agent.rag.vo.ChunkContextVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkServiceImpl implements IChunkService {

    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

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
                .documentId(doc.getId())
                .fileName(doc.getFileName());

        if (chunk.getParentChunkId() != null) {
            Chunk parent = chunkMapper.findById(chunk.getParentChunkId()).orElse(null);
            if (parent != null) {
                builder.parentChunk(ChunkContextVO.ParentChunkInfo.builder()
                        .chunkId(parent.getId())
                        .titlePath(parent.getTitlePath())
                        .chunkText(parent.getChunkText())
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

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
