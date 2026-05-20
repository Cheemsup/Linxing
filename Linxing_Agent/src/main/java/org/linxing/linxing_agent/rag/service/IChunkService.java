package org.linxing.linxing_agent.rag.service;

import org.linxing.linxing_agent.rag.vo.ChunkContextVO;

public interface IChunkService {

    ChunkContextVO getChunkContext(Integer chunkId, Integer userId);
}
