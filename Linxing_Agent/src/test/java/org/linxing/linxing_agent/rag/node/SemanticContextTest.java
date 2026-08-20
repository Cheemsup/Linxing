package org.linxing.linxing_agent.rag.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.enhancement.NeighborNodeRenderer;
import org.linxing.linxing_agent.rag.enhancement.SemanticContext;
import org.linxing.linxing_agent.rag.enhancement.SemanticContextBuilder;
import org.linxing.linxing_agent.rag.enhancement.SemanticEnhancementPrompts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SemanticContextBuilder 与 Prompt 单元测试。
 * 不依赖真实 LLM，仅验证邻居上下文构造与 prompt 注入逻辑。
 */
@DisplayName("语义增强上下文构造测试")
class SemanticContextTest {

    /**
     * 验证 Builder 能正确截取前后邻居（默认各 2 个）。
     */
    @Test
    @DisplayName("构造目标节点前后邻居上下文")
    void testBuildContextWithNeighbors() {
        RagProperties properties = new RagProperties();
        SemanticContextBuilder builder = new SemanticContextBuilder(properties);

        List<DocumentNode> nodes = List.of(
                textNode("n0", "开头文本"),
                textNode("n1", "前前文本"),
                textNode("n2", "前一个文本"),
                imageNode("n3", "1/documents/1/images/p001_01.png"),
                textNode("n4", "后一个文本"),
                textNode("n5", "后后文本"),
                textNode("n6", "结尾文本")
        );

        SemanticContext ctx = builder.build(nodes, 3);

        assertEquals("n3", ctx.getTarget().getId());
        assertEquals(2, ctx.getPreviousNodes().size());
        assertEquals("n1", ctx.getPreviousNodes().get(0).getId());
        assertEquals("n2", ctx.getPreviousNodes().get(1).getId());
        assertEquals(2, ctx.getNextNodes().size());
        assertEquals("n4", ctx.getNextNodes().get(0).getId());
        assertEquals("n5", ctx.getNextNodes().get(1).getId());
    }

    /**
     * 验证序列头部/尾部节点的边界处理。
     */
    @Test
    @DisplayName("边界节点上下文不越界")
    void testBuildContextAtBoundaries() {
        RagProperties properties = new RagProperties();
        SemanticContextBuilder builder = new SemanticContextBuilder(properties);

        List<DocumentNode> nodes = List.of(
                imageNode("n0", "1/documents/1/images/p001_01.png"),
                textNode("n1", "第二段")
        );

        SemanticContext first = builder.build(nodes, 0);
        assertTrue(first.getPreviousNodes().isEmpty());
        assertEquals(1, first.getNextNodes().size());

        SemanticContext last = builder.build(nodes, 1);
        assertEquals(1, last.getPreviousNodes().size());
        assertTrue(last.getNextNodes().isEmpty());
    }

    /**
     * 验证统一 prompt 包含邻居上下文与当前节点内容。
     */
    @Test
    @DisplayName("统一 prompt 注入邻居上下文")
    void testUnifiedPromptIncludesNeighbors() {
        List<DocumentNode> prev = List.of(
                headingNode("h1", "TCP 协议概述", 1),
                textNode("t1", "TCP 是面向连接的可靠传输协议。")
        );
        ImageNode target = imageNode("img1", "1/documents/1/images/p001_03.png");
        List<DocumentNode> next = List.of(
                textNode("t2", "三次握手建立了全双工通信信道。")
        );

        SemanticContext ctx = new SemanticContext(target, prev, next);
        NeighborNodeRenderer renderer = new NeighborNodeRenderer(
                new RagProperties.SemanticEnhancement.Context());

        String previousText = renderer.renderNeighbors(ctx.getPreviousNodes());
        String currentText = SemanticEnhancementPrompts.renderCurrentNodeContent(ctx.getTarget());
        String nextText = renderer.renderNeighbors(ctx.getNextNodes());
        String prompt = SemanticEnhancementPrompts.buildPrompt(previousText, currentText, nextText);

        assertTrue(prompt.contains("[Previous Nodes]"));
        assertTrue(prompt.contains("[Current Node]"));
        assertTrue(prompt.contains("[Next Nodes]"));
        assertTrue(prompt.contains("TCP 协议概述"), "前置邻居标题应出现在 prompt 中");
        assertTrue(prompt.contains("TCP 是面向连接的可靠传输协议"), "前置邻居文本应出现在 prompt 中");
        assertTrue(prompt.contains("三次握手建立了全双工通信信道"), "后置邻居文本应出现在 prompt 中");
        assertTrue(prompt.contains("[图片内容待描述]"), "当前图片节点内容应出现在 prompt 中");
        assertTrue(prompt.contains("You are enriching a document node"), "应使用统一模板");
    }

    /**
     * 验证邻居占位符渲染：图片/代码/表格不被原文撑爆。
     */
    @Test
    @DisplayName("邻居节点占位符渲染")
    void testNeighborRendererUsesPlaceholders() {
        RagProperties.SemanticEnhancement.Context ctxConfig =
                new RagProperties.SemanticEnhancement.Context();
        ctxConfig.setMaxNeighborChars(50);
        NeighborNodeRenderer renderer = new NeighborNodeRenderer(ctxConfig);

        List<DocumentNode> neighbors = List.of(
                imageNode("img1", "1/documents/1/images/p001_02.png"),
                codeNode("code1", "public static void main(String[] args) { ... }", "java"),
                tableNode("tbl1", "<table><tr><td>...</td></tr></table>")
        );

        String rendered = renderer.renderNeighbors(neighbors);
        assertTrue(rendered.contains("[图片]"), "图片邻居应渲染为占位符");
        assertTrue(rendered.contains("[代码块(java)]"), "代码邻居应渲染为语言占位符");
        assertTrue(rendered.contains("[表格]"), "表格邻居应渲染为占位符");
        assertFalse(rendered.contains("public static void main"), "代码原文不应直接出现在邻居上下文");
        assertFalse(rendered.contains("<table>"), "表格 HTML 不应直接出现在邻居上下文");
    }

    private TextNode textNode(String id, String text) {
        TextNode node = TextNode.builder().text(text).build();
        node.metadata().put("id", id);
        return node;
    }

    private HeadingNode headingNode(String id, String text, int level) {
        HeadingNode node = HeadingNode.builder().text(text).level(level).build();
        node.metadata().put("id", id);
        return node;
    }

    private ImageNode imageNode(String id, String path) {
        ImageNode node = ImageNode.builder().imagePath(path).build();
        node.metadata().put("id", id);
        return node;
    }

    private CodeNode codeNode(String id, String code, String language) {
        CodeNode node = CodeNode.builder().code(code).language(language).build();
        node.metadata().put("id", id);
        return node;
    }

    private TableNode tableNode(String id, String html) {
        TableNode node = TableNode.builder().html(html).build();
        node.metadata().put("id", id);
        return node;
    }
}
