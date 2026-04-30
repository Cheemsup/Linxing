package org.linxing.linxing_agent.strategy;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.strategy.impl.CodeChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.HtmlChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.LineBasedChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.MarkdownChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.RecursiveChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.StructureAwareChunkStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分块策略工厂，根据文件类型和内容特征按优先级自动选择合适的分块策略。
 * 策略选择完全由系统自动完成，用户无需也不应参与分块策略的指定。
 */
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
