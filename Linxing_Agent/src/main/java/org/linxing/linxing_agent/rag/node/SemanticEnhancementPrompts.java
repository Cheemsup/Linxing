package org.linxing.linxing_agent.rag.node;

/**
 * 语义增强 Prompt 模板。
 * 统一处理待增强 Node（目前为IMAGE / CODE / TABLE 三类 ）的语义增强 prompt。
 *
 */
public final class SemanticEnhancementPrompts {

    private SemanticEnhancementPrompts() {
    }

    /**
     * 统一语义增强 Prompt 模板。
     * 目前兼容 Image / Table / Code 三类 Node。
     */
    public static final String UNIFIED_ENHANCEMENT_PROMPT = """
            你正在为某个文档节点生成语义增强描述，用于语义检索。

            当前节点是一篇更大文档的一部分。所提供的前置节点与后置节点仅作为上下文，用于帮助你理解当前节点。

            你的任务是为「当前节点」生成一段简洁、自包含的语义描述。

            要求：
            1. 聚焦于「当前节点」，仅利用周边上下文来消解指代、缩写、省略主语以及隐含含义。
            2. 不要概括整篇文档。
            3. 不要提及「前置节点」「后置节点」「上下文」等词，也不要描述文档结构。
            4. 不要编造当前节点及其上下文所不支持的信息。
            5. 输出内容应当即使被单独阅读也能被理解。
            6. 尽可能保留重要的实体、概念、专业术语、变量名、API、算法、文件名、表格字段及其相互关系。
            7. 若当前节点为：
               - 图片：描述其视觉内容，并结合其语义含义及在文档中的作用。
               - 表格：概括表格所表达的关键信息、重要取值、对比、趋势与结论。
               - 代码：概括代码的目的、核心逻辑、输入、输出、依赖，及其在周边文档中的作用。
            8. 仅返回一段自然段落（100–200 字）。不要使用 Markdown、项目符号、标题或额外说明。

            输入：

            [前置节点]
            {{previous_nodes}}

            [当前节点]
            {{current_node}}

            [后置节点]
            {{next_nodes}}
            """;

    /**
     * 构建完整的增强 Prompt。
     *
     * @param previousNodes 前置邻居文本
     * @param currentNode   当前 Node 内容描述
     * @param nextNodes     后置邻居文本
     * @return 完整的 prompt 字符串
     */
    public static String buildPrompt(String previousNodes, String currentNode, String nextNodes) {
        return UNIFIED_ENHANCEMENT_PROMPT
                .replace("{{previous_nodes}}", previousNodes != null && !previousNodes.isBlank() ? previousNodes : "无")
                .replace("{{current_node}}", currentNode != null ? currentNode : "无内容")
                .replace("{{next_nodes}}", nextNodes != null && !nextNodes.isBlank() ? nextNodes : "无");
    }

    /**
     * 渲染当前 Node 的内容描述（用于 prompt 中的 {{current_node}}）。
     */
    public static String renderCurrentNodeContent(DocumentNode node) {
        return switch (node.type()) {
            case IMAGE -> {
                ImageNode imageNode = (ImageNode) node;
                String caption = imageNode.getCaption();
                yield caption != null && !caption.isBlank()
                        ? "[图片标题: " + caption + "]\n[图片内容待描述]"
                        : "[图片内容待描述]";
            }
            case CODE -> {
                CodeNode codeNode = (CodeNode) node;
                String lang = codeNode.getLanguage() != null ? codeNode.getLanguage() : "未知语言";
                String code = codeNode.getCode();
                yield "[代码语言: " + lang + "]\n" + (code != null ? code : "无代码内容");
            }
            case TABLE -> {
                TableNode tableNode = (TableNode) node;
                String html = tableNode.getHtml();
                yield "[表格HTML]\n" + (html != null ? html : "无表格内容");
            }
            default -> node.originalContent();
        };
    }
}
