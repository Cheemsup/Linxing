package org.linxing.linxing_agent.agent.memory.window.projection.rewrite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 读性质工具白名单。
 *
 * <p>用于 {@link RewriteRuleAnalyzer} 判定哪些 tool 的结果可被 Rewrite 精简。
 * 读性质工具（检索/解析类）返回大段文本，结果在后续轮次可精简为占位符而不损失关键推理线索；
 * 写性质工具结果含状态/副作用，精简会破坏后续引用，不纳入。
 *
 * <p>白名单由 {@code agent.projection.snip.rewrite.read-only-tools} 列表注入，
 * yaml 缺省时回退到 {@link #DEFAULT_READ_ONLY_TOOLS}。
 */
@Component
public class RewriteRuleWhitelist {

    /** 默认读性质工具 name 集合（yaml 未配置时回退）。 */
    public static final Set<String> DEFAULT_READ_ONLY_TOOLS = Set.of(
            "search_knowledge_base",
            "web_search",
            "resolve"
    );

    private final Set<String> readOnlyTools;

    public RewriteRuleWhitelist(
            @Value("${agent.projection.snip.rewrite.read-only-tools:}") Set<String> readOnlyTools) {
        this.readOnlyTools = (readOnlyTools == null || readOnlyTools.isEmpty())
                ? DEFAULT_READ_ONLY_TOOLS
                : readOnlyTools;
    }

    /** 判定某 tool 是否为读性质工具（结果可被 Rewrite 精简）。 */
    public boolean contains(String toolName) {
        return toolName != null && readOnlyTools.contains(toolName);
    }
}
