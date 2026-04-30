package org.linxing.linxing_agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.CommonConstants;
import org.linxing.linxing_agent.result.Result;
import org.linxing.linxing_agent.service.IChunkService;
import org.linxing.linxing_agent.vo.ChunkContextVO;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/chunks")
@RequiredArgsConstructor
public class ChunkController {

    private final IChunkService chunkService;

    @GetMapping("/{id}/context")
    public Result<ChunkContextVO> getChunkContext(
            @PathVariable Integer id,
            @RequestParam(value = "userId", required = false, defaultValue = "1") Integer userId) {
        if (userId == null) {
            userId = CommonConstants.DEFAULT_USER_ID;
        }
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
}
