package org.linxing.linxing_agent.service;

import org.linxing.linxing_agent.vo.ChunkContextVO;

public interface IChunkService {

    ChunkContextVO getChunkContext(Integer chunkId, Integer userId);
}
