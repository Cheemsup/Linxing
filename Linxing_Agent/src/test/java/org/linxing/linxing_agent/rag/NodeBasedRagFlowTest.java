package org.linxing.linxing_agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.linxing.linxing_agent.rag.node.NeighborNodeRenderer;
import org.linxing.linxing_agent.rag.node.NodeBasedChunkBuilder;
import org.linxing.linxing_agent.rag.node.NodeType;
import org.linxing.linxing_agent.rag.node.SemanticContext;
import org.linxing.linxing_agent.rag.node.SemanticContextBuilder;
import org.linxing.linxing_agent.rag.node.SemanticEnhancementPrompts;
import org.linxing.linxing_agent.rag.service.DocumentAnalysisFacade;
import org.linxing.linxing_agent.rag.service.SemanticEnhancementService;
import org.linxing.linxing_agent.rag.strategy.ChunkResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Node-Based RAG 数据流验证测试。
 *
 * 测试目标：验证系统的动作是否符合预期，沿数据流打印各阶段中间状态：
 *   docx 文件 ──DocumentAnalysisFacade──▶ Node 序列 ──NodeBasedChunkBuilder──▶ Chunk 序列
 *
 * 测试文件：reference/TODOS/betterRAG/testFiles/TCP笔记.docx
 *
 * 前置条件（运行时需满足，编写时不启动验证）：
 *   - Spring 上下文可启动（PostgreSQL / Redis / pgvector 等依赖就绪，dev profile）
 *   - Python 文档解析服务（document_analysis_service）已在 http://localhost:8000 启动
 *     否则 Facade 会 fallback 到尚未实现的 Java 备用方案并抛出异常
 */
@SpringBootTest
@DisplayName("Node-Based RAG 数据流验证测试")
class NodeBasedRagFlowTest {

    @Autowired
    private DocumentAnalysisFacade documentAnalysisFacade;

    @Autowired
    private NodeBasedChunkBuilder nodeBasedChunkBuilder;

    @Autowired
    private SemanticEnhancementService semanticEnhancementService;

    @Autowired
    private RagProperties ragProperties;

    /**
     * 完整数据流：docx → Nodes → 语义增强 → Chunks，沿途打印中间状态。
     */
    @Test
    @DisplayName("完整数据流：docx → Nodes → 语义增强 → Chunks")
    void testFullFlow_DocxToNodesToChunks() {
        // ── Step 0: 定位测试文件 ──
        Path testFile = locateTestFile();
        System.out.println("=== Step 0: 测试文件定位 ===");
        System.out.println("文件路径: " + testFile.toAbsolutePath());
        System.out.println("文件存在: " + testFile.toFile().exists());
        assertTrue(testFile.toFile().exists(), "测试文件必须存在");

        // ── Step 1: 解析文档 → Node 序列 ──
        System.out.println("\n=== Step 1: 调用 DocumentAnalysisFacade.analyze() ===");
        int testDocumentId = 999;
        int testUserId = 1;
        List<DocumentNode> nodes = documentAnalysisFacade.analyze(
                testFile, testDocumentId, testUserId);

        // ── Step 2: 打印并校验 Node 序列 ──
        System.out.println("\n=== Step 2: Node 序列中间状态 ===");
        printNodeSequenceInfo(nodes);
        assertNotNull(nodes, "Node 序列不应为 null");
        assertFalse(nodes.isEmpty(), "Node 序列不应为空");

        // ── Step 3: 语义增强（VLM/LLM）──
        System.out.println("\n=== Step 3: 语义增强（VLM/LLM）===");
        System.out.println("\n--- 第一个 IMAGE Node 的增强 prompt（验证邻居上下文注入）---");
        printFirstImagePrompt(nodes);
        semanticEnhancementService.enhance(nodes);
        printEnhancedNodeInfo(nodes);

        // ── Step 4: Node 序列 → Chunk 序列 ──
        System.out.println("\n=== Step 4: 调用 NodeBasedChunkBuilder.build() ===");
        int maxTokens = 512; // 单个 Chunk 最大 Token 数
        List<ChunkResult> chunks = nodeBasedChunkBuilder.build(nodes, maxTokens);

        // ── Step 5: 打印并校验 Chunk 序列 ──
        System.out.println("\n=== Step 5: Chunk 序列中间状态 ===");
        printChunkSequenceInfo(chunks, maxTokens);
        assertNotNull(chunks, "Chunk 序列不应为 null");
        assertFalse(chunks.isEmpty(), "Chunk 序列不应为空");

        // ── 总结 ──
        System.out.println("\n=== 数据流总结 ===");
        System.out.println("输入文件: TCP笔记.docx");
        System.out.println("Node 数量: " + nodes.size());
        System.out.println("Chunk 数量: " + chunks.size());
        System.out.printf("平均每个 Chunk 包含 Node 数: %.2f%n",
                (double) nodes.size() / chunks.size());
    }

    /**
     * 定位测试文件。兼容从项目根目录或 Linxing_Agent 模块目录启动测试两种场景。
     */
    private Path locateTestFile() {
        String relPath = "reference/TODOS/betterRAG/testFiles/TCP笔记.docx";
        // 候选工作目录：项目根 / 模块根
        Path fromRoot = Paths.get(relPath);
        Path fromModule = Paths.get("..", relPath.replace("/", java.io.File.separator));
        if (fromRoot.toFile().exists()) {
            return fromRoot;
        }
        if (fromModule.toFile().exists()) {
            return fromModule;
        }
        // 默认返回 fromRoot，让上层的 exists() 断言给出明确失败
        return fromRoot;
    }

    /**
     * 打印 Node 序列详细信息：类型分布、前若干个 Node 详情、标题层级结构。
     */
    private void printNodeSequenceInfo(List<DocumentNode> nodes) {
        System.out.println("Node 总数: " + nodes.size());

        // 类型分布
        Map<NodeType, Long> typeCount = nodes.stream()
                .collect(Collectors.groupingBy(DocumentNode::type, Collectors.counting()));
        System.out.println("\n--- Node 类型分布 ---");
        typeCount.forEach((type, count) ->
                System.out.printf("  %s: %d 个 (%.1f%%)%n",
                        type, count, count * 100.0 / nodes.size()));

        // 前 10 个 Node 详情
        int previewCount = Math.min(10, nodes.size());
        System.out.println("\n--- 前 " + previewCount + " 个 Node 详情 ---");
        for (int i = 0; i < previewCount; i++) {
            DocumentNode node = nodes.get(i);
            System.out.printf("[%d] type=%s, id=%s%n", i, node.type(), node.getId());

            String original = node.originalContent();
            if (original != null && !original.isEmpty()) {
                System.out.println("    originalContent: "
                        + preview(original, 80).replace("\n", "\\n") + "...");
            }
            String semantic = node.semanticText();
            if (semantic != null && !semantic.isEmpty()) {
                System.out.println("    semanticText: "
                        + preview(semantic, 80).replace("\n", "\\n") + "...");
            }
            Map<String, Object> meta = node.metadata();
            if (meta != null && !meta.isEmpty()) {
                System.out.println("    metadata: " + formatMetadata(meta));
            }
        }

        // 标题层级结构
        System.out.println("\n--- 标题层级结构 ---");
        List<DocumentNode> headings = nodes.stream()
                .filter(n -> n.type() == NodeType.HEADING)
                .toList();
        if (headings.isEmpty()) {
            System.out.println("  (无 HEADING 节点)");
        } else {
            for (DocumentNode heading : headings) {
                Object levelObj = heading.metadata().get("level");
                int level = levelObj instanceof Integer ? (Integer) levelObj : 1;
                String indent = "  ".repeat(Math.max(0, level - 1));
                System.out.printf("%s[H%d] %s%n", indent, level, heading.originalContent());
            }
        }
    }

    /**
     * 打印 Chunk 序列详细信息：每个 Chunk 的 Node 数/类型/文本预览，以及大小分布。
     */
    private void printChunkSequenceInfo(List<ChunkResult> chunks, int maxTokens) {
        System.out.println("Chunk 总数: " + chunks.size());
        System.out.println("配置 maxTokens: " + maxTokens);

        System.out.println("\n--- Chunk 详情 ---");
        for (int i = 0; i < chunks.size(); i++) {
            ChunkResult chunk = chunks.get(i);
            List<DocumentNode> chunkNodes = chunk.getNodes();

            System.out.printf("%n[Chunk %d]%n", i);
            System.out.println("  包含 Node 数: " + (chunkNodes != null ? chunkNodes.size() : 0));
            System.out.println("  chunkType: " + chunk.getChunkType());
            System.out.println("  sourceStrategy: " + chunk.getSourceStrategy());

            String chunkText = chunk.getChunkText();
            if (chunkText != null) {
                System.out.println("  chunkText 长度: " + chunkText.length() + " 字符");
                System.out.println("  chunkText 预览: "
                        + preview(chunkText, 100).replace("\n", "\\n") + "...");
            }

            if (chunkNodes != null && !chunkNodes.isEmpty()) {
                Map<NodeType, Long> nodeTypes = chunkNodes.stream()
                        .collect(Collectors.groupingBy(DocumentNode::type, Collectors.counting()));
                System.out.println("  Node 类型分布: " + nodeTypes);
            }
        }

        // Chunk 大小分布（按包含 Node 数）
        System.out.println("\n--- Chunk 大小分布 ---");
        Map<Integer, Long> sizeDistribution = chunks.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getNodes() != null ? c.getNodes().size() : 0,
                        Collectors.counting()));
        sizeDistribution.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("  %d 个 Node: %d 个 Chunk%n",
                        e.getKey(), e.getValue()));
    }

    /**
     * 截取字符串前 maxLen 个字符。
     */
    private String preview(String s, int maxLen) {
        return s.substring(0, Math.min(maxLen, s.length()));
    }

    /**
     * 打印第一个 IMAGE Node 的增强 prompt，用于验证邻居上下文是否注入。
     */
    private void printFirstImagePrompt(List<DocumentNode> nodes) {
        int firstImageIndex = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).type() == NodeType.IMAGE) {
                firstImageIndex = i;
                break;
            }
        }
        if (firstImageIndex < 0) {
            System.out.println("(文档中无 IMAGE Node)");
            return;
        }

        SemanticContextBuilder ctxBuilder = new SemanticContextBuilder(ragProperties);
        SemanticContext ctx = ctxBuilder.build(nodes, firstImageIndex);
        NeighborNodeRenderer renderer = new NeighborNodeRenderer(
                ragProperties.getSemanticEnhancement().getContext());

        String previousText = renderer.renderNeighbors(ctx.getPreviousNodes());
        String currentText = SemanticEnhancementPrompts.renderCurrentNodeContent(ctx.getTarget());
        String nextText = renderer.renderNeighbors(ctx.getNextNodes());
        String prompt = SemanticEnhancementPrompts.buildPrompt(previousText, currentText, nextText);

        System.out.println("目标节点: " + ctx.getTarget().getId());
        System.out.println("前置邻居:\n" + previousText);
        System.out.println("\n当前节点:\n" + currentText);
        System.out.println("\n后置邻居:\n" + nextText);
        System.out.println("\n完整 prompt 长度: " + prompt.length() + " 字符");
        System.out.println("完整 prompt:\n" + prompt);
    }

    /**
     * 打印语义增强后的 IMAGE/CODE/TABLE 节点状态。
     */
    private void printEnhancedNodeInfo(List<DocumentNode> nodes) {
        List<DocumentNode> enhanced = nodes.stream()
                .filter(n -> n.type() == NodeType.IMAGE
                        || n.type() == NodeType.CODE
                        || n.type() == NodeType.TABLE)
                .toList();

        System.out.println("待增强 Node 数量: " + enhanced.size());
        if (enhanced.isEmpty()) {
            System.out.println("  (无 IMAGE/CODE/TABLE 节点)");
            return;
        }

        for (DocumentNode node : enhanced) {
            System.out.printf("\n[%s] id=%s%n", node.type(), node.getId());
            System.out.println("  originalContent: " + preview(node.originalContent(), 60));
            System.out.println("  semanticText:    " + preview(node.semanticText(), 200));
            if (node.semanticText() == null || node.semanticText().isBlank()
                    || node.semanticText().startsWith("[")) {
                System.out.println("  ⚠ 未获得有效语义增强结果");
            } else {
                System.out.println("  ✓ 已获得语义增强结果");
            }
        }
    }

    /**
     * 格式化 metadata 为简洁字符串（最多 5 个字段）。
     */
    private String formatMetadata(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return "{}";
        }
        return meta.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .limit(5)
                .collect(Collectors.joining(", ", "{", "}"));
    }
}
