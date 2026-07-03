package org.linxing.linxing_agent.rag.strategy;

import org.linxing.linxing_agent.rag.entity.ChunkResult;

import java.util.List;

/**
 * 分块策略接口，定义策略的匹配判断与分块执行方法，所有具体分块策略均实现此接口
 *
 * @deprecated 已废弃。所有文件类型已统一走 Node 体系（Python 解析 + NodeBasedChunkBuilder 装箱），
 *             旧的 strategy.supports/execute 路径无调用方。结构识别与超长拆分已迁移至 Python 侧
 *             parsers（markdown/html/code/linebased），保留仅供历史参考，后续应删除。
 */
@Deprecated
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
