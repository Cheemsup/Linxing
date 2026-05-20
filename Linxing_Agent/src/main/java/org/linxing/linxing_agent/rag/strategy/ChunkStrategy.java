package org.linxing.linxing_agent.rag.strategy;

import java.util.List;

/**
 * 分块策略接口，定义策略的匹配判断与分块执行方法，所有具体分块策略均实现此接口
 */
public interface ChunkStrategy {

    /**
     * 用于判断文档内容是否符合某类别的执行器的方法
     * @param context
     * @return
     */
    boolean supports(ChunkStrategyContext context);

    /**
     * 执行chunk的方法
     * @param context
     * @return
     */
    List<ChunkResult> execute(ChunkStrategyContext context);
}
