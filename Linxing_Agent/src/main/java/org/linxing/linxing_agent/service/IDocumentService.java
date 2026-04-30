package org.linxing.linxing_agent.service;

import org.linxing.linxing_agent.vo.ChunkTreeVO;
import org.linxing.linxing_agent.vo.DocumentPreviewVO;
import org.linxing.linxing_agent.vo.DocumentVO;
import org.linxing.linxing_agent.dto.PageResult;

import java.util.List;

public interface IDocumentService {

    PageResult<DocumentVO> listDocuments(Integer userId, int page, int size);

    DocumentVO getDocumentDetail(Integer id, Integer userId);

    boolean deleteDocument(Integer id, Integer userId);

    DocumentPreviewVO previewDocument(Integer id, Integer userId);

    String getFilePath(Integer id, Integer userId);

    List<ChunkTreeVO> getChunkTree(Integer id, Integer userId);
}
