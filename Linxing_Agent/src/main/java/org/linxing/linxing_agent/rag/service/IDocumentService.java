package org.linxing.linxing_agent.rag.service;

import org.linxing.linxing_agent.rag.vo.DocumentPreviewVO;
import org.linxing.linxing_agent.rag.vo.DocumentVO;
import org.linxing.linxing_agent.common.result.PageResult;

public interface IDocumentService {

    PageResult<DocumentVO> listDocuments(Integer userId, int page, int size);

    DocumentVO getDocumentDetail(Integer id, Integer userId);

    boolean deleteDocument(Integer id, Integer userId);

    DocumentPreviewVO previewDocument(Integer id, Integer userId);

    String getFilePath(Integer id, Integer userId);
}
