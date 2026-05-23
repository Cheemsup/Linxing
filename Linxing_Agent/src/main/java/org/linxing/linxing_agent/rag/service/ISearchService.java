package org.linxing.linxing_agent.rag.service;

import org.linxing.linxing_agent.rag.dto.SearchResult;

import java.util.List;

public interface ISearchService {

    List<SearchResult> search(Integer userId, String query, int topK, boolean hybrid);
}
