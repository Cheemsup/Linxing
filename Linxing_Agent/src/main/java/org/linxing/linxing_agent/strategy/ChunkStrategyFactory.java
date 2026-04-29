package org.linxing.linxing_agent.strategy;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.strategy.impl.CodeChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.HtmlChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.LineBasedChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.MarkdownChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.RecursiveChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.SemanticChunkStrategy;
import org.linxing.linxing_agent.strategy.impl.StructureAwareChunkStrategy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块策略工厂，根据文件类型和内容特征按优先级自动选择合适的分块策略，也支持用户显式指定策略名称。
 */
@Slf4j
@Component
public class ChunkStrategyFactory {

    // 策略映射表，用于根据名称获取策略执行器
    private final Map<String, ChunkStrategy> strategyByName;
    // 策略有序列表，用于根据优先级获取策略执行器
    private final List<ChunkStrategy> orderedStrategies;

    public ChunkStrategyFactory(
            MarkdownChunkStrategy markdownChunkStrategy,
            HtmlChunkStrategy htmlChunkStrategy,
            CodeChunkStrategy codeChunkStrategy,
            StructureAwareChunkStrategy structureAwareChunkStrategy,
            LineBasedChunkStrategy lineBasedChunkStrategy,
            RecursiveChunkStrategy recursiveChunkStrategy,
            SemanticChunkStrategy semanticChunkStrategy) {

        // 初始化策略映射表
        // 所有策略执行器均为 Spring @Component 单例 bean，容器启动时完成唯一初始化，无需额外缓存层
        this.strategyByName = new HashMap<>();
        strategyByName.put("MarkdownChunkStrategy", markdownChunkStrategy);
        strategyByName.put("markdown", markdownChunkStrategy);
        strategyByName.put("HtmlChunkStrategy", htmlChunkStrategy);
        strategyByName.put("html", htmlChunkStrategy);
        strategyByName.put("CodeChunkStrategy", codeChunkStrategy);
        strategyByName.put("code", codeChunkStrategy);
        strategyByName.put("StructureAwareChunkStrategy", structureAwareChunkStrategy);
        strategyByName.put("structure", structureAwareChunkStrategy);
        strategyByName.put("LineBasedChunkStrategy", lineBasedChunkStrategy);
        strategyByName.put("line", lineBasedChunkStrategy);
        strategyByName.put("RecursiveChunkStrategy", recursiveChunkStrategy);
        strategyByName.put("recursive", recursiveChunkStrategy);
        strategyByName.put("SemanticChunkStrategy", semanticChunkStrategy);
        strategyByName.put("semantic", semanticChunkStrategy);

        //按照优先级编排策略选择器的检查和启用顺序。如果某个策略选择器匹配上了则直接使用它而不继续检查后面的选择器是否合适
        this.orderedStrategies = List.of(
                markdownChunkStrategy,       // P2，P1暂且是用户显式指定的策略，后续应该会删除P1
                htmlChunkStrategy,           // P3
                codeChunkStrategy,           // P4
                structureAwareChunkStrategy, // P5
                lineBasedChunkStrategy       // P6，最后还有P7最为兜底，不列在此处
        );
    }

    public ChunkStrategy getStrategy(ChunkStrategyContext context) {
        // 用户显式指定策略，优先级最高
        // TODO:这个设计合理的前提是用户懂得自己上传的文件类型、同时需要正确做出指定。这样的设计对于用户要求太苛刻，系统上线应该需要去除。
        if (context.getExtra() != null && context.getExtra().containsKey("chunkStrategy")) {
            String strategyName = (String) context.getExtra().get("chunkStrategy");
            ChunkStrategy explicit = strategyByName.get(strategyName);
            if (explicit != null) {
                log.info("使用用户指定的策略: {}", strategyName);
                return explicit;
            }
            if (strategyByName.containsKey(strategyName)) {
                log.info("使用用户指定的策略: {}", strategyName);
                return strategyByName.get(strategyName);
            }
            log.warn("用户指定的策略 '{}' 未注册，回退到自动探测", strategyName);
        }

        // 遍历优先级列表，选择第一个匹配的策略执行器
        for (ChunkStrategy strategy : orderedStrategies) {
            if (strategy.supports(context)) {
                log.info("自动选择策略: {}", strategy.getClass().getSimpleName());
                return strategy;
            }
        }

        // 如果所有策略都不匹配，使用 RecursiveChunkStrategy 作为兜底策略
        ChunkStrategy fallback = strategyByName.get("RecursiveChunkStrategy");
        log.info("无适合策略匹配，使用通用兜底策略: RecursiveChunkStrategy");
        return fallback;
    }

    public ChunkStrategy getStrategyByName(String name) {
        return strategyByName.get(name);
    }
}
