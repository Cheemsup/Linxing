package org.linxing.linxing_agent.rag.enhancement;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.node.CodeNode;
import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.linxing.linxing_agent.rag.node.ImageNode;
import org.linxing.linxing_agent.rag.node.NodeType;
import org.linxing.linxing_agent.rag.node.TableNode;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Node语义增强服务实现。
 *
 * 增强策略：
 * - IMAGE: 调用 VLM发送图片字节 + 上下文 prompt 生成图片描述
 * - CODE: 调用 LLM（DeepSeek）生成代码解释
 * - TABLE: 调用 LLM（DeepSeek）生成表格总结
 * - TEXT/HEADING/FORMULA: 不增强，semanticText 已为原文
 *
 * TODO:需要检查是否所有参与语义增强的Node类型都已经有“临近上下文”一起打包发送的步骤
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticEnhancementServiceImpl implements SemanticEnhancementService {

    private static final Pattern THINK_TAG = Pattern.compile("-thinking[\\s\\S]*?-thinking", Pattern.CASE_INSENSITIVE);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    private final LlmManager llmManager;
    private final RagProperties ragProperties;
    private final SemanticContextBuilder semanticContextBuilder;//打包临近上下文

    @Override
    public void enhance(List<DocumentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        NeighborNodeRenderer renderer = new NeighborNodeRenderer(getContext());

        for (int i = 0; i < nodes.size(); i++) {
            DocumentNode node = nodes.get(i);
            if (!needsEnhancement(node.type())) {
                log.debug("Node {} 类型 {} 无需语义增强", node.getId(), node.type());
                continue;
            }
            try {
                // 通过 ContextBuilder 构造包含前后邻居的上下文（打包），然后一同发送到模型以增强语义
                SemanticContext ctx = semanticContextBuilder.build(nodes, i);
                enhanceNode(ctx, renderer);//根据Node类型进行模型调用、语义丰富
            } catch (Exception e) {
                // 增强彻底失败（重试耗尽或不可重试异常）——Node 会 fallback 到默认 semanticText，
                // 但这意味着该 Node 的语义描述缺失、向量化质量下降，需 error 级别便于监控告警
                log.error("Node {} 语义增强失败，将回退到原文参与向量化: {}", node.getId(), e.getMessage(), e);
            }
        }

        log.info("语义增强完成，处理 {} 个 Node", nodes.size());
    }

    /**
     * 判断 Node 类型是否需要语义增强。
     * 仅 IMAGE/CODE/TABLE 需要调用 LLM 生成描述。
     * TODO：后续应该将此改为列表判断，列表内是需要语义增强的类型
     */
    private boolean needsEnhancement(NodeType type) {
        return type == NodeType.IMAGE || type == NodeType.CODE || type == NodeType.TABLE;
    }

    /**
     * 单个 Node 语义增强（基于上下文），接收 SemanticContext（注入邻居上下文）
     *
     * TODO：本方法无返回值，所以在最外层的pipeline中无法直接感知Node内容的变化，需要对此进行风险分析
     */
    private void enhanceNode(SemanticContext ctx, NeighborNodeRenderer renderer) {
        NodeType type = ctx.getTarget().type();

        switch (type) {
            case IMAGE -> enhanceImageNode(ctx, renderer);
            case CODE -> enhanceCodeNode(ctx, renderer);
            case TABLE -> enhanceTableNode(ctx, renderer);
            default -> {
                // TEXT/HEADING/FORMULA 不需要增强
            }
        }
    }

    /**
     * 图片 Node 增强：调用 VLM（Vision Language Model）生成图片描述。
     *
     * 多模态输入：发送图片字节 + 文本 prompt（包含邻居上下文）给 VLM，
     * 让模型直接"看到"图片内容，结合上下文生成准确的语义描述。
     */
    private void enhanceImageNode(SemanticContext ctx, NeighborNodeRenderer renderer) {
        ImageNode node = (ImageNode) ctx.getTarget();//TODO：不理解此处的强转设计
        String imagePath = node.getImagePath();
        if (imagePath == null || imagePath.isBlank()) {
            log.warn("ImageNode {} 缺少 imagePath，跳过增强", node.getId());
            return;
        }

        // 解析图片完整路径
        Path fullPath = resolveImagePath(imagePath);
        if (!Files.exists(fullPath)) {
            log.warn("ImageNode {} 图片文件不存在: {}", node.getId(), fullPath);
            return;
        }

        try {
            // 构建多模态 UserMessage：图片 + 文本 prompt
            String mimeType = inferMimeType(fullPath);//获取图片MIME类型（保险措施）
            ImageContent imageContent = ImageContent.from(fullPath, mimeType);

            String textPrompt = buildUnifiedPrompt(ctx, renderer);
            TextContent textContent = TextContent.from(textPrompt);

            UserMessage userMessage = UserMessage.from(imageContent, textContent);//利用图片原件和指导提示词（包含临近上下文）构建出userMessage

            // 调用 VLM（多模态模型，带指数退避重试以应对偶发 GOAWAY/超时）
            log.debug("ImageNode {} 调用 VLM，图片: {}, MIME: {}", node.getId(), fullPath, mimeType);
            ChatModel model = llmManager.getModel(LlmType.VISION_MODEL);
            ChatResponse response = callWithRetry(() -> model.chat(userMessage), "VLM");

            String description = response.aiMessage().text();

            // 设置语义描述
            if (description != null && !description.isBlank()) {
                node.setSemanticDescription(cleanResponse(description));//设置 VLM 生成的语义描述到当前节点中（数据封装对象），属于直接修改共享变量的做法使得其他链路能够感知变化
                log.debug("ImageNode {} VLM 描述: {}", node.getId(), truncateForLog(node.getSemanticDescription()));
            }
        } catch (Exception e) {
            log.warn("ImageNode {} VLM 调用失败: {}", node.getId(), e.getMessage());
            // 增强失败不影响整体流程，Node 会 fallback 到默认 semanticText
        }
    }

    /**
     * 代码 Node 增强：基于上下文调用 LLM 生成代码解释。
     */
    private void enhanceCodeNode(SemanticContext ctx, NeighborNodeRenderer renderer) {
        CodeNode node = (CodeNode) ctx.getTarget();
        if (node.getCode() == null || node.getCode().isBlank()) {
            log.warn("CodeNode {} 缺少代码内容，跳过增强", node.getId());
            return;
        }

        String prompt = buildUnifiedPrompt(ctx, renderer);
        String explanation = callLlm(LlmType.CODE_ENHANCE_MODEL, prompt);

        if (explanation != null && !explanation.isBlank()) {
            node.setSemanticExplanation(cleanResponse(explanation));//设置 VLM 生成的语义描述到当前节点中（数据封装对象），属于直接修改共享变量的做法使得其他链路能够感知变化
            log.debug("CodeNode {} LLM 解释: {}", node.getId(), truncateForLog(node.getSemanticExplanation()));
        }
    }

    /**
     * 表格 Node 增强：基于上下文调用 LLM 生成表格总结。
     */
    private void enhanceTableNode(SemanticContext ctx, NeighborNodeRenderer renderer) {
        TableNode node = (TableNode) ctx.getTarget();
        if (node.getHtml() == null || node.getHtml().isBlank()) {
            log.warn("TableNode {} 缺少表格内容，跳过增强", node.getId());
            return;
        }

        String prompt = buildUnifiedPrompt(ctx, renderer);
        String summary = callLlm(LlmType.TABLE_ENHANCE_MODEL, prompt);

        if (summary != null && !summary.isBlank()) {
            node.setSemanticSummary(cleanResponse(summary));//设置 VLM 生成的语义描述到当前节点中（数据封装对象），属于直接修改共享变量的做法使得其他链路能够感知变化
            log.debug("TableNode {} LLM 总结: {}", node.getId(), truncateForLog(node.getSemanticSummary()));
        }
    }

    /**
     * 构建统一增强 Prompt（注入邻居上下文 + 当前 Node 内容）。
     * 各类 Node 共用同一模板，仅 current_node 内容渲染方式不同。
     */
    private String buildUnifiedPrompt(SemanticContext ctx, NeighborNodeRenderer renderer) {
        String previousText = renderer.renderNeighbors(ctx.getPreviousNodes());
        String currentText = SemanticEnhancementPrompts.renderCurrentNodeContent(ctx.getTarget());
        String nextText = renderer.renderNeighbors(ctx.getNextNodes());
        return SemanticEnhancementPrompts.buildPrompt(previousText, currentText, nextText);//结合上下文情况、本节点情况、指导提示词，生成最终的发往大模型的内容
    }

    /**
     * 调用 LLM（统一入口，带指数退避重试以应对偶发 GOAWAY/超时）。
     */
    private String callLlm(String provider, String prompt) {
        try {
            ChatModel model = llmManager.getModel(provider);
            String result = callWithRetry(() -> model.chat(prompt), provider);
            return result;
        } catch (Exception e) {
            log.warn("LLM 调用失败 (provider={}): {}", provider, e.getMessage());
            return null;
        }
    }

    /**
     * 带指数退避重试的调用包装。
     * 应对 VLM/LLM 服务偶发的 GOAWAY、连接重置、请求超时等瞬时故障：
     * 最多重试 {@value #MAX_RETRY_ATTEMPTS} 次，退避间隔按 2 的幂增长（1s/2s/4s...）。
     * 超时类与 IO 异常视为可重试，其他异常直接抛出。
     *
     * TODO：将此方法与callLlm()合并。不必再套一层
     */
    private <T> T callWithRetry(java.util.function.Supplier<T> action, String label) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                lastException = e;
                if (!isRetryable(e)) {
                    throw e;
                }
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                    log.warn("{} 调用第 {}/{} 次失败，{}ms 后重试: {}",
                            label, attempt, MAX_RETRY_ATTEMPTS, backoff, e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                }
            }
        }
        throw new RuntimeException(label + " 调用失败（重试 " + MAX_RETRY_ATTEMPTS + " 次仍失败）", lastException);
    }

    /**
     * 判断异常是否值得重试。
     * 超时、GOAWAY、连接重置等瞬时故障可重试；鉴权/参数类错误不重试。
     */
    private boolean isRetryable(Exception e) {
        if (e instanceof dev.langchain4j.exception.TimeoutException) {
            return true;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            if (cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 解析图片完整路径。
     * imagePath 格式：/chunk_images/{userId}/{documentId}/img_xxx.png 或相对路径
     */
    private Path resolveImagePath(String imagePath) {
        String storePath = ragProperties.getStorePath();
        String normalizedPath = imagePath.startsWith("/") ? imagePath.substring(1) : imagePath;
        return Paths.get(storePath, normalizedPath);
    }

    /**
     * 根据文件扩展名推断 MIME 类型（VLM 调用需要显式指定图片 MIME，否则部分供应商会拒绝多模态请求）
     *
     * @param imagePath 图片文件路径
     * @return MIME 类型，未知扩展名默认 image/png
     */
    private String inferMimeType(Path imagePath) {
        String name = imagePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    /**
     * 从配置获取上下文设置（可能为 null，使用默认值）。
     */
    private RagProperties.SemanticEnhancement.Context getContext() {
        RagProperties.SemanticEnhancement enhancement = ragProperties.getSemanticEnhancement();
        return enhancement != null ? enhancement.getContext() : null;
    }

    /**
     * 清理 LLM 响应（移除 thinking 标签、多余空格）。
     */
    private String cleanResponse(String response) {
        if (response == null) {
            return null;
        }
        String cleaned = THINK_TAG.matcher(response).replaceAll("").trim();
        return cleaned.replaceAll("\\s+", " ");
    }

    /**
     * 截断日志输出（避免日志过长）。
     */
    private String truncateForLog(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }
}
