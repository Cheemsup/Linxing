package org.linxing.linxing_agent.rag.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 分块后处理责任链，按 order 排序依次执行所有注册的 ChunkProcessingHandler，任一 Handler 返回 false 则终止链路。
 */
@Slf4j
@Component
public class ChunkProcessingPipeline {

    private final List<ChunkProcessingHandler> handlers;

    public ChunkProcessingPipeline(List<ChunkProcessingHandler> handlers) {
        this.handlers = handlers.stream()
                .sorted(Comparator.comparingInt(ChunkProcessingHandler::order))
                .toList();
        log.info("ChunkProcessingPipeline 初始化完成，注册 {} 个处理器", this.handlers.size());
    }

    public void execute(ChunkProcessingContext context) {
        for (ChunkProcessingHandler handler : handlers) {
            try {
                if (!handler.handle(context)) {
                    log.debug("处理器 {} 终止了流水线", handler.getClass().getSimpleName());
                    break;
                }
            } catch (Exception e) {
                log.error("处理器 {} 执行异常: {}", handler.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
