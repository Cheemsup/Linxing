package org.linxing.linxing_agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.enhancement.NeighborNodeRenderer;
import org.linxing.linxing_agent.rag.enhancement.SemanticContext;
import org.linxing.linxing_agent.rag.enhancement.SemanticContextBuilder;
import org.linxing.linxing_agent.rag.enhancement.SemanticEnhancementPrompts;
import org.linxing.linxing_agent.rag.node.CodeNode;
import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.linxing.linxing_agent.rag.node.HeadingNode;
import org.linxing.linxing_agent.rag.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全文路径语义增强上下文注入验证测试。
 *
 * 测试目标：验证 code/html 类文件走"全篇原文"上下文路径时，
 *   给到 LLM 的 prompt 中 [全篇文档背景] 注入的是整个文件原文（而非邻居占位符）。
 *
 * 验证点：
 *   1. SemanticContextBuilder.build(nodes, i, true) 走全文路径，返回 fullDocumentBackground 非空、邻居为空
 *   2. fullDocumentBackground == nodes 全体 backgroundContent 用 "\n\n" 拼接
 *      （Rich Node 取真实载体原文：CODE 取 code、TABLE 取 html、FORMULA 取 formula、IMAGE 取 caption）
 *   3. buildFullDocumentPrompt 产出的 prompt 含 [全篇文档背景] 段且内嵌完整代码全文
 *   4. 缓存：同一批 nodes 引用第二次调用返回同一实例（单次 enhance 调用内复用）
 *   5. 全文背景含真实代码原文，不含 Display 占位符 [[LINXING:CODE:*]]
 *
 * 测试文件：reference/TODOS/betterRAG/testFiles/AbstractStrategyChoose.java
 * 纯单元测试：不依赖 Spring 上下文 / Python 服务 / DB，直接手工构造 Node 序列与 RagProperties。
 */
@DisplayName("全文路径语义增强上下文注入验证")
class FullDocumentContextTest {

    /**
     * 主用例：java 文件全文路径下，prompt 注入的是代码全文而非邻居占位符。
     */
    @Test
    @DisplayName("java 文件全文路径：prompt [全篇文档背景] 注入代码全文")
    void testFullDocumentContext_InjectsFullSourceForJavaFile() throws Exception {
        // ── 读取测试源文件 ──
        String source = readTestFile("AbstractStrategyChoose.java");
        System.out.println("=== 源文件长度: " + source.length() + " 字符 ===");

        // ── 构造 Node 序列：模拟 Python 解析 java 文件的结果 ──
        // 按方法/区块切，每个 CODE Node 是一段代码原文；前面带一个 HEADING（类注释/类声明）与 TEXT（方法间说明）
        List<DocumentNode> nodes = buildJavaNodes(source);
        System.out.println("\n=== 构造 Node 序列: " + nodes.size() + " 个 Node ===");
        for (int i = 0; i < nodes.size(); i++) {
            DocumentNode n = nodes.get(i);
            System.out.printf("[%d] type=%s, id=%s, originalContent 长度=%d%n",
                    i, n.type(), n.getId(), n.originalContent().length());
        }

        // ── 构造 RagProperties（用真实 fullContextFileTypes 默认值，含 "java"）──
        RagProperties ragProperties = newRagPropertiesWithDefaultFullContextTypes();
        SemanticContextBuilder ctxBuilder = new SemanticContextBuilder(ragProperties);

        // ── 全文路径判定：fileType=java 命中 fullContextFileTypes ──
        // 模拟 SemanticEnhancementServiceImpl.shouldUseFullDocumentContext 的判定逻辑
        boolean useFull = shouldUseFullDocumentContext(ragProperties, "java");
        System.out.println("\n=== 全文路径判定: fileType=java, useFullDocumentContext=" + useFull + " ===");
        assertTrue(useFull, "java 必须命中 fullContextFileTypes 走全文路径");

        // ── 取第一个 CODE Node 作为待增强目标，构造全文上下文 ──
        int targetIdx = firstCodeIndex(nodes);
        assertNotEquals(-1, targetIdx, "Node 序列中必须存在 CODE Node");
        SemanticContext ctx = ctxBuilder.build(nodes, targetIdx, useFull);

        // 验证点 1：全文路径下 fullDocumentBackground 非空、邻居为空
        System.out.println("\n=== 验证点 1: 全文路径下 fullDocumentBackground 非空、邻居置空 ===");
        System.out.println("useFullDocumentContext()=" + ctx.useFullDocumentContext());
        System.out.println("previousNodes.size()=" + ctx.getPreviousNodes().size());
        System.out.println("nextNodes.size()=" + ctx.getNextNodes().size());
        System.out.println("fullDocumentBackground 长度=" + ctx.getFullDocumentBackground().length());
        assertTrue(ctx.useFullDocumentContext(), "全文路径下 useFullDocumentContext() 必须为 true");
        assertEquals(0, ctx.getPreviousNodes().size(), "全文路径下前置邻居必须置空");
        assertEquals(0, ctx.getNextNodes().size(), "全文路径下后置邻居必须置空");
        assertNotNull(ctx.getFullDocumentBackground(), "全文路径下 fullDocumentBackground 不应为 null");
        assertFalse(ctx.getFullDocumentBackground().isBlank(), "全文路径下 fullDocumentBackground 不应为空串");

        // 验证点 2：fullDocumentBackground == nodes 全体 backgroundContent 用 "\n\n" 拼接
        // Rich Node（CODE）的 backgroundContent() 返回真实代码原文（带 [代码语言: lang] 前缀），
        // 而非 originalContent() 的 [[LINXING:CODE:id]] 占位符
        System.out.println("\n=== 验证点 2: fullDocumentBackground == nodes backgroundContent 拼接 ===");
        String expectedBackground = expectedFullBackground(nodes);
        System.out.println("预期拼接长度: " + expectedBackground.length());
        // fullDocumentBackground 的实际内容前 120 字符预览
        System.out.println("实际全文前 120 字符:\n" + preview(ctx.getFullDocumentBackground(), 120));
        assertEquals(expectedBackground, ctx.getFullDocumentBackground(),
                "fullDocumentBackground 必须等于 nodes 全体 backgroundContent 用 \\n\\n 拼接的结果");

        // ── 构造 prompt 并验证注入的是代码全文 ──
        String currentText = SemanticEnhancementPrompts.renderCurrentNodeContent(ctx.getTarget());
        String prompt = SemanticEnhancementPrompts.buildFullDocumentPrompt(
                ctx.getFullDocumentBackground(), currentText);

        System.out.println("\n=== 完整 prompt 长度: " + prompt.length() + " 字符 ===");

        // 验证点 3：prompt 含 [全篇文档背景] 段标记
        System.out.println("\n=== 验证点 3: prompt 含 [全篇文档背景] 段 ===");
        assertTrue(prompt.contains("[全篇文档背景]"), "prompt 必须含 [全篇文档背景] 段标记");
        assertFalse(prompt.contains("[前置节点]"), "全文路径 prompt 不应含 [前置节点] 段标记");
        assertFalse(prompt.contains("[后置节点]"), "全文路径 prompt 不应含 [后置节点] 段标记");

        // 验证点 4：[全篇文档背景] 段内嵌 nodes 全体 backgroundContent 拼接结果
        // 方案 A 的核心：CODE Node 的 backgroundContent() 返回真实代码原文，
        // 故全文背景应含代码全文本身，而非 Display 占位符。
        System.out.println("\n=== 验证点 4: [全篇文档背景] 段内嵌 nodes 全体 backgroundContent 拼接结果（真实代码原文） ===");
        assertTrue(prompt.contains(ctx.getFullDocumentBackground()),
                "prompt 必须内嵌完整的 fullDocumentBackground（非片段截断）");
        // 验证 HEADING 原文（originalContent 与 backgroundContent 等价，均为原文）
        assertTrue(prompt.contains("public class AbstractStrategyChoose"),
                "prompt 必须含类声明 HEADING 原文");
        // 验证点 5：全文背景含真实代码原文（CODE.backgroundContent 返回 code 字段）
        // CODE Node 的 backgroundContent 带前缀 "[代码语言: java]"，证明走的是真实原文路径
        assertTrue(prompt.contains("[代码语言: java]"),
                "prompt 全文背景必须含 CODE.backgroundContent 的 [代码语言: java] 前缀（证明取真实代码原文）");
        assertFalse(prompt.contains("[[LINXING:CODE:"),
                "prompt 全文背景不应再含 CODE Display 占位符 [[LINXING:CODE:*]]（方案 A 已替换为真实代码）");
        System.out.println("✓ [全篇文档背景] 已内嵌 nodes 全体 backgroundContent 拼接结果（含 HEADING 原文 + 真实代码原文）");
        System.out.println("  方案 A：CodeNode.backgroundContent() 返回 code 字段（带 [代码语言] 前缀），");
        System.out.println("  不再是 originalContent() 的 [[LINXING:CODE:id]] 占位符——LLM 现在能拿到真实代码背景。");
    }

    /**
     * 对照用例：fileType=null（如改造前的 docx 测试）走邻居路径，prompt 不含 [全篇文档背景]。
     */
    @Test
    @DisplayName("对照：fileType 未命中时走邻居路径，prompt 不含 [全篇文档背景]")
    void testNeighborPathWhenFileTypeNotMatched() throws Exception {
        String source = readTestFile("AbstractStrategyChoose.java");
        List<DocumentNode> nodes = buildJavaNodes(source);
        RagProperties ragProperties = newRagPropertiesWithDefaultFullContextTypes();
        SemanticContextBuilder ctxBuilder = new SemanticContextBuilder(ragProperties);

        // fileType=null 不命中全文路径
        boolean useFull = shouldUseFullDocumentContext(ragProperties, null);
        assertFalse(useFull, "fileType=null 必须走邻居路径");

        int targetIdx = firstCodeIndex(nodes);
        SemanticContext ctx = ctxBuilder.build(nodes, targetIdx, useFull);

        System.out.println("=== 邻居路径: useFullDocumentContext()=" + ctx.useFullDocumentContext() + " ===");
        assertFalse(ctx.useFullDocumentContext(), "邻居路径下 useFullDocumentContext() 必须为 false");
        assertNull(ctx.getFullDocumentBackground(), "邻居路径下 fullDocumentBackground 必须为 null");
        assertTrue(ctx.getPreviousNodes().size() > 0 || ctx.getNextNodes().size() > 0,
                "邻居路径下至少应有一侧邻居（除非目标 Node 在序列首尾）");

        // 邻居路径 prompt 不含 [全篇文档背景]
        NeighborNodeRenderer renderer = new NeighborNodeRenderer(
                ragProperties.getSemanticEnhancement().getContext());
        String previousText = renderer.renderNeighbors(ctx.getPreviousNodes());
        String currentText = SemanticEnhancementPrompts.renderCurrentNodeContent(ctx.getTarget());
        String nextText = renderer.renderNeighbors(ctx.getNextNodes());
        String prompt = SemanticEnhancementPrompts.buildPrompt(previousText, currentText, nextText);

        assertFalse(prompt.contains("[全篇文档背景]"), "邻居路径 prompt 不应含 [全篇文档背景]");
        assertTrue(prompt.contains("[前置节点]") || prompt.contains("[后置节点]"),
                "邻居路径 prompt 应含 [前置节点]/[后置节点]");
        System.out.println("✓ 邻居路径 prompt 含 [前置节点]/[后置节点]，不含 [全篇文档背景]");
    }

    /**
     * 缓存验证：同一批 nodes 引用第二次调用 buildFullDocumentBackground 返回同一实例。
     */
    @Test
    @DisplayName("全文背景缓存：同一批 nodes 引用复用，不重复拼接")
    void testFullDocumentBackgroundCacheReusesSameInstance() throws Exception {
        String source = readTestFile("AbstractStrategyChoose.java");
        List<DocumentNode> nodes = buildJavaNodes(source);
        RagProperties ragProperties = newRagPropertiesWithDefaultFullContextTypes();
        SemanticContextBuilder ctxBuilder = new SemanticContextBuilder(ragProperties);

        String first = ctxBuilder.buildFullDocumentBackground(nodes);
        String second = ctxBuilder.buildFullDocumentBackground(nodes);

        System.out.println("=== 第一次拼接长度: " + first.length() + " ===");
        System.out.println("=== 第二次拼接长度: " + second.length() + " ===");
        // 缓存命中：同一实例（引用相等），证明未重新拼接
        assertSame(first, second, "同一批 nodes 引用第二次调用必须返回同一实例（缓存命中）");
        System.out.println("✓ 缓存命中：第二次调用返回同一实例（== 相等）");

        // 换一批 nodes：缓存应 miss 重新拼接
        List<DocumentNode> otherNodes = buildJavaNodes(source); // 新引用
        String third = ctxBuilder.buildFullDocumentBackground(otherNodes);
        assertNotSame(first, third, "换一批 nodes 引用必须 miss 缓存重新拼接");
        System.out.println("✓ 缓存 miss：换一批 nodes 引用后重新拼接（!= 相等）");
    }

    // ── 辅助方法 ──

    /**
     * 读取 testFiles 目录下的测试文件。
     */
    private String readTestFile(String fileName) throws Exception {
        String relPath = "reference/TODOS/betterRAG/testFiles/" + fileName;
        Path fromRoot = Paths.get(relPath);
        Path fromModule = Paths.get("..", relPath.replace("/", java.io.File.separator));
        Path target = fromRoot.toFile().exists() ? fromRoot : fromModule;
        if (!target.toFile().exists()) {
            // 兜底：从模块 test 资源相对路径找
            target = Paths.get("..", "..", relPath.replace("/", java.io.File.separator));
        }
        assertTrue(target.toFile().exists(), "测试文件必须存在: " + target.toAbsolutePath());
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    /**
     * 构造一个模拟 java 文件解析结果的 Node 序列。
     * 简化处理：把源文件按双换行切块，前几块作为 TEXT/HEADING，其余作为 CODE Node。
     * 目的是让 fullDocumentBackground 的拼接内容包含原文形态（这里直接用原文块）。
     */
    private List<DocumentNode> buildJavaNodes(String source) {
        List<DocumentNode> nodes = new ArrayList<>();
        // HEADING：类声明行
        String classDecl = "public class AbstractStrategyChoose implements ApplicationListener<ApplicationInitializingEvent> {";
        nodes.add(HeadingNode.builder()
                .text(classDecl)
                .level(1)
                .metadata(new HashMap<>(Map.of("id", "H_1", "level", 1)))
                .build());

        // 把源文件按空行切成片段，每片段作为一个 CODE Node（模拟按方法/区块切）
        String[] blocks = source.split("\\n\\s*\\n");
        int codeIdx = 1;
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nodes.add(CodeNode.builder()
                    .code(trimmed)
                    .language("java")
                    .metadata(new HashMap<>(Map.of("id", "CODE_" + codeIdx, "language", "java")))
                    .build());
            codeIdx++;
        }
        return nodes;
    }

    /**
     * 期望的"全篇原文"背景：nodes 全体 backgroundContent 用 "\n\n" 拼接。
     * Rich Node（CODE）的 backgroundContent() 返回真实代码原文，而非 Display 占位符。
     */
    private String expectedFullBackground(List<DocumentNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(nodes.get(i).backgroundContent());
        }
        return sb.toString();
    }

    /**
     * 模拟 SemanticEnhancementServiceImpl.shouldUseFullDocumentContext 的判定逻辑。
     */
    private boolean shouldUseFullDocumentContext(RagProperties ragProperties, String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return false;
        }
        RagProperties.SemanticEnhancement.Context context = ragProperties.getSemanticEnhancement().getContext();
        if (context == null || context.getFullContextFileTypes() == null) {
            return false;
        }
        String normalized = fileType.trim().toLowerCase();
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return context.getFullContextFileTypes().contains(normalized);
    }

    /**
     * 第一个 CODE Node 的索引。
     */
    private int firstCodeIndex(List<DocumentNode> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).type() == org.linxing.linxing_agent.rag.node.NodeType.CODE) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 构造 RagProperties，使用 fullContextFileTypes 的默认值（含 "java"）。
     * new RagProperties() 会触发 SemanticEnhancement.Context 默认初始化。
     */
    private RagProperties newRagPropertiesWithDefaultFullContextTypes() {
        RagProperties ragProperties = new RagProperties();
        // 触发默认配置对象创建（@Data 默认值在对象构造时已初始化，这里仅保险性断言）
        assertNotNull(ragProperties.getSemanticEnhancement(), "SemanticEnhancement 必须有默认实例");
        assertNotNull(ragProperties.getSemanticEnhancement().getContext(), "Context 必须有默认实例");
        assertNotNull(ragProperties.getSemanticEnhancement().getContext().getFullContextFileTypes(),
                "fullContextFileTypes 必须有默认集合");
        return ragProperties;
    }

    /**
     * 字符串预览。
     */
    private String preview(String s, int maxLen) {
        if (s == null) return "null";
        return s.substring(0, Math.min(maxLen, s.length())) + (s.length() > maxLen ? "..." : "");
    }
}
