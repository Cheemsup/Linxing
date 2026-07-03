package org.linxing.linxing_agent.rag.node;

import org.linxing.linxing_agent.rag.config.RagProperties;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 邻居节点文本渲染器。
 * 将邻居节点渲染为简短文本，用于构建 LLM prompt 的上下文部分。
 *
 * 渲染规则：
 * - TEXT/HEADING：返回原文（可选截断）
 * - IMAGE/CODE/TABLE/FORMULA：返回占位符（如 [图片]、[代码块]）
 *
 * TODO：对于相邻Node也是图片、code等需要语义增强的情况，处理方式还待定
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
                CodeNode codeNode = (CodeNode) node;
                String lang = codeNode.getLanguage() != null ? codeNode.getLanguage() : "";
                yield "[代码块" + (lang.isEmpty() ? "" : "(" + lang + ")") + "]";
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
