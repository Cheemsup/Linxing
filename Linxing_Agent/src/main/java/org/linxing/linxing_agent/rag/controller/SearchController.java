package org.linxing.linxing_agent.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.rag.dto.SearchRequest;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.linxing.linxing_agent.rag.service.ISearchService;
import org.linxing.linxing_agent.rag.vo.SearchResultVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class SearchController {

    private final ISearchService searchService;

    @PostMapping("/search")
    public Result<List<SearchResultVO>> search(@RequestBody SearchRequest request) {
        Integer userId = BaseContext.requireCurrentUserId();
        int topK = request.getTopK() != null ? request.getTopK() : 0;
        boolean hybrid = Boolean.TRUE.equals(request.getHybrid());

        log.info("[搜索] 用户{} 查询: {}, hybrid={}", userId, truncate(request.getQuery(), 80), hybrid);

        List<SearchResult> results = searchService.search(userId, request.getQuery(), topK, hybrid);
        List<SearchResultVO> vos = searchService.toVOList(results);

        log.info("[搜索] 用户{} 返回{}条结果", userId, vos.size());
        return Result.success(vos);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
