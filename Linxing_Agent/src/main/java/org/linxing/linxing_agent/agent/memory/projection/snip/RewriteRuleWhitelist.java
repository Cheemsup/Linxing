package org.linxing.linxing_agent.agent.memory.projection.snip;

import java.util.Set;

/**
 * 读性质工具白名单（thePlan P2-2 / nowRefact §5）。
 *
 * <p>用于 {@link RewriteRuleAnalyzer} 判定哪些 tool 的结果可被 Rewrite 精简。
 * 读性质工具（检索/解析类）返回大段文本，结果在后续轮次可精简为占位符而不损失关键推理线索；
 * 写性质工具（如 save_exam、create_container 等）结果含状态/副作用，精简会破坏后续引用，不纳入。
 *
 * <p>已核实三工具 {@code name()}：RagSearchTool={@code search_knowledge_base}、
 * WebSearchTool={@code web_search}、ResolveTool={@code resolve}（resolve 为元工具，结果为工具定义 JSON，可精简）。
 *
 * <p>后续可改为 application.yaml 列表注入；本期硬编码常量足够。
 */
public final class RewriteRuleWhitelist {

    /** 读性质工具 name 集合。 */
    public static final Set<String> READ_ONLY_TOOLS = Set.of(
            "search_knowledge_base",
            "web_search",
            "resolve"
    );

    private RewriteRuleWhitelist() {
    }
}
