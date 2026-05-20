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
@RequestMapping("/chunks")
@RequiredArgsConstructor
public class ChunkController {

    private final IChunkService chunkService;

    @GetMapping("/{id}/context")
    public Result<ChunkContextVO> getChunkContext(@PathVariable Integer id) {
        Integer userId = getCurrentUserId();
        try {
            ChunkContextVO context = chunkService.getChunkContext(id, userId);
            return Result.success(context);
        } catch (IllegalArgumentException e) {
            log.warn("获取chunk上下文失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("获取chunk上下文异常: {}", e.getMessage(), e);
            return Result.error("获取chunk上下文失败: " + e.getMessage());
        }
    }

    private static Integer getCurrentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return userId.intValue();
    }
}
