package org.linxing.linxing_agent.rag.enhancement;

import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.node.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 邻居节点文本渲染器。
 * 将邻居节点渲染为简短文本，用于构建 LLM prompt 的上下文部分。
 *
 * 渲染规则：
 * - TEXT/HEADING：返回原文（按 maxNeighborChars 截断）
 * - IMAGE：返回占位符（[图片: caption]），图片字节由 VLM 处理当前 Node 时直接消费，邻居不重复送图
 * - CODE：返回实际代码片段（按 maxNeighborChars 截断），消解当前 Node 对相邻代码的指代
 * - TABLE：返回占位符（[表格]），避免把相邻表格 HTML 全量塞入上下文
 * - FORMULA：返回公式文本（截断）
 *
 * TODO：对于相邻Node也是图片、code等需要语义增强的情况，处理方式还待定
 * （当前策略：邻居图片只送 caption 占位符、邻居代码送截断片段，不送原始二进制/全量内容）
 */
public class NeighborNodeRenderer {

    private final int maxNeighborChars;

    public NeighborNodeRenderer(RagProperties.SemanticEnhancement.Context context) {
        this.maxNeighborChars = context != null ? context.getMaxNeighborChars() : 200;
    }

    /**
     * 渲染邻居节点列表为文本。
     *
     * @param nodes 邻居节点列表
     * @return 渲染后的文本，节点间用换行分隔
     */
    public String renderNeighbors(List<DocumentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "无";
        }

        return nodes.stream()
                .map(this::renderSingleNode)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 渲染单个邻居节点。
     */
    private String renderSingleNode(DocumentNode node) {
        String content = renderContent(node);
        return truncateIfNeeded(content);
    }

    /**
     * 根据 Node 类型渲染内容。
     */
    private String renderContent(DocumentNode node) {
        return switch (node.type()) {
            case TEXT -> {
                TextNode textNode = (TextNode) node;
                yield textNode.getText() != null ? textNode.getText() : "[空文本]";
            }
            case HEADING -> {
                HeadingNode headingNode = (HeadingNode) node;
                String prefix = "#".repeat(headingNode.getLevel() != null ? headingNode.getLevel() : 1);
                yield headingNode.getText() != null
                        ? prefix + " " + headingNode.getText()
                        : "[空标题]";
            }
            case IMAGE -> {
                ImageNode imageNode = (ImageNode) node;
                yield imageNode.getCaption() != null
                        ? "[图片: " + imageNode.getCaption() + "]"
                        : "[图片]";
            }
            case CODE -> {
                // 渲染实际代码片段（而非仅占位符），消解当前 Node 对相邻代码符号/调用的指代；
                // 按 maxNeighborChars 截断避免邻居代码过长挤占上下文
                CodeNode codeNode = (CodeNode) node;
                String lang = codeNode.getLanguage() != null ? codeNode.getLanguage() : "";
                String code = codeNode.getCode();
                yield code != null && !code.isBlank()
                        ? "[代码块" + (lang.isEmpty() ? "" : "(" + lang + ")") + "]\n" + code
                        : "[代码块" + (lang.isEmpty() ? "" : "(" + lang + ")") + "]";
            }
            case TABLE -> "[表格]";
            case FORMULA -> {
                FormulaNode formulaNode = (FormulaNode) node;
                yield formulaNode.getFormula() != null
                        ? "[公式: " + truncateIfNeeded(formulaNode.getFormula(), 50) + "]"
                        : "[公式]";
            }
        };
    }

    /**
     * 截断文本到配置的最大长度。
     */
    private String truncateIfNeeded(String content) {
        return truncateIfNeeded(content, maxNeighborChars);
    }

    /**
     * 截断文本到指定最大长度。
     */
    private String truncateIfNeeded(String content, int maxLen) {
        if (maxLen <= 0 || content == null || content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "...";
    }
}
