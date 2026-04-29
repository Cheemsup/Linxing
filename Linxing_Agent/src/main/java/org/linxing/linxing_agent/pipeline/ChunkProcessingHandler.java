package org.linxing.linxing_agent.pipeline;

/**
 * 分块后处理 Handler 接口，定义单个处理步骤的执行方法与排序优先级，所有 Handler 按 order 升序组成责任链。
 */
public interface ChunkProcessingHandler {

    boolean handle(ChunkProcessingContext context);

    int order();
}
