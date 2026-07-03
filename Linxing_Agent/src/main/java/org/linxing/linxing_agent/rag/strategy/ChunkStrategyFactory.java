package org.linxing.linxing_agent.rag.strategy;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.strategy.impl.CodeChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.impl.HtmlChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.impl.LineBasedChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.impl.MarkdownChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.impl.RecursiveChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.impl.StructureAwareChunkStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分块策略工厂，根据文件类型和内容特征按优先级自动选择合适的分块策略。
 * 策略选择完全由系统自动完成，用户无需也不应参与分块策略的指定。
 *
 * @deprecated 已废弃。所有文件类型已统一走 Node 体系（{@code ChunkPipelineCoordinator.processDocumentFromNodes}），
 *             旧 {@code strategy.execute} 路径无调用方，结构识别已迁移至 Python 侧 parsers。
 *             ChunkPipelineCoordinator 已移除对 strategyFactory 的依赖；保留仅供历史参考，后续应删除。
 */
@Deprecated
@Slf4j
@Component
public class ChunkStrategyFactory {

    private final List<ChunkStrategy> orderedStrategies;
    private final ChunkStrategy fallbackStrategy;

    public ChunkStrategyFactory(
            MarkdownChunkStrategy markdownChunkStrategy,
            HtmlChunkStrategy htmlChunkStrategy,
            CodeChunkStrategy codeChunkStrategy,
            StructureAwareChunkStrategy structureAwareChunkStrategy,
            LineBasedChunkStrategy lineBasedChunkStrategy,
            RecursiveChunkStrategy recursiveChunkStrategy) {

        this.orderedStrategies = List.of(
                markdownChunkStrategy,
                htmlChunkStrategy,
                codeChunkStrategy,
                structureAwareChunkStrategy,
                lineBasedChunkStrategy
        );
        this.fallbackStrategy = recursiveChunkStrategy;
    }

    @SuppressWarnings("deprecation")
    public ChunkStrategy getStrategy(ChunkStrategyContext context) {
        for (ChunkStrategy strategy : orderedStrategies) {
            if (strategy.supports(context)) {
                log.info("自动选择策略: {}", strategy.getClass().getSimpleName());
                return strategy;
            }
        }

        log.info("无适合策略匹配，使用通用兜底策略: RecursiveChunkStrategy");
        return fallbackStrategy;
    }
}
