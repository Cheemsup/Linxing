package org.linxing.linxing_agent.rag.service;

import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.linxing.linxing_agent.rag.vo.SearchResultVO;

import java.util.List;

public interface ISearchService {

    List<SearchResult> search(Integer userId, String query, int topK, boolean hybrid);

    /**
     * 将搜索结果DTO列表转换为VO列表（含score四位小数精度处理）
     * @param results 搜索结果DTO
     * @return VO列表
     */
    List<SearchResultVO> toVOList(List<SearchResult> results);
}
