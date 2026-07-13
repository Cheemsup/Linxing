package org.linxing.linxing_agent.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.rag.service.IChunkService;
import org.linxing.linxing_agent.rag.vo.ChunkContextVO;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class ChunkController {

    private final IChunkService chunkService;

    @GetMapping("/chunks/{id}/context")
    public Result<ChunkContextVO> getChunkContext(@PathVariable Integer id) {
        Integer userId = getCurrentUserId();
        ChunkContextVO context = chunkService.getChunkContext(id, userId);
        return Result.success(context);
    }

    private static Integer getCurrentUserId() {
        return BaseContext.requireCurrentUserId();
    }
}
