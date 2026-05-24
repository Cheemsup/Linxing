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
        Integer userId = getCurrentUserId();
        int topK = request.getTopK() != null ? request.getTopK() : 0;
        boolean hybrid = request.getHybrid() != null ? request.getHybrid() : false;

        log.info("[搜索] 用户{} 查询: {}, hybrid={}", userId, truncate(request.getQuery(), 80), hybrid);

        List<SearchResult> results = searchService.search(userId, request.getQuery(), topK, hybrid);

        //将搜索结果进行处理，返回VO性质的最终结果
        //TODO：后续可考虑将这部分逻辑代码移动到更好的地方而不在controller
        List<SearchResultVO> vos = results.stream()
                .map(this::toVO)
                .toList();

        log.info("[搜索] 用户{} 返回{}条结果", userId, vos.size());
        return Result.success(vos);
    }

    private SearchResultVO toVO(SearchResult r) {
        return SearchResultVO.builder()
                .chunkId(r.getChunkId())
                .documentId(r.getDocumentId())
                .fileName(r.getFileName())
                .titlePath(r.getTitlePath())
                .chunkType(r.getChunkType())
                .chunkText(r.getChunkText())
                .score(Math.round(r.getScore() * 10000.0) / 10000.0)
                .build();
    }

    private Integer getCurrentUserId() {
        Long currentId = BaseContext.getCurrentId();
        if (currentId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return currentId.intValue();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
