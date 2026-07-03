package org.linxing.linxing_agent.rag.strategy.impl;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * HTML 分块策略，使用 Jsoup 解析 DOM 树，按 h1-h6 标题及 section/article 标签拆分。
 * 深度优先遍历确保零内容丢失，自动提取纯文本并构造标题路径。
 *
 * @deprecated 已废弃。HTML DOM 遍历已迁移至 Python 侧
 *             {@code document_analysis_service/parsers/html_parser.py}（beautifulsoup4），
 *             由 NodeBasedChunkBuilder 装箱。保留仅供历史参考，后续应删除。
 */
@Deprecated
@Slf4j
@Component("htmlChunkStrategy")
public class HtmlChunkStrategy implements ChunkStrategy {

    private static final int DEFAULT_MAX_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;

    @Override
    public boolean supports(ChunkStrategyContext context) {
        String ext = context.getFileType();
        return ext != null && (ext.equalsIgnoreCase("html") || ext.equalsIgnoreCase("htm"));
    }

    @Override
    public List<ChunkResult> execute(ChunkStrategyContext context) {
        int maxChunkSize = context.getMaxChunkSize() != null ? context.getMaxChunkSize() : DEFAULT_MAX_CHUNK_SIZE;
        int chunkOverlap = context.getChunkOverlap() != null ? context.getChunkOverlap() : DEFAULT_CHUNK_OVERLAP;
        String fullText = context.getFullText();

        RecursiveTextSplitter refinementPipeline = new RecursiveTextSplitter(maxChunkSize, chunkOverlap);

        List<HtmlBlock> blocks = splitByDom(fullText);

        if (blocks.size() <= 1) {
            String plainText = Jsoup.parse(fullText).text();
            blocks = fallbackSplit(plainText, maxChunkSize);
        }

        List<ChunkResult> results = new ArrayList<>();
        for (HtmlBlock block : blocks) {
            String blockText = block.text().trim();
            if (blockText.isEmpty()) {
                continue;
            }

            if (blockText.length() <= maxChunkSize) {
                results.add(ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                        .chunkText(blockText)
                        .titlePath(block.titlePath())
                        .chunkType(ChunkType.SECTION)
                        .sourceStrategy("HtmlChunkStrategy")
                        .build());
            } else {
                int level1Index = results.size();
                results.add(ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagParameters.CHUNK_LEVEL_1)
                        .chunkText(blockText)
                        .titlePath(block.titlePath())
                        .chunkType(ChunkType.SECTION)
                        .sourceStrategy("HtmlChunkStrategy")
                        .build());

                List<String> subChunks = refinementPipeline.refine(blockText);
                for (String subText : subChunks) {
                    if (!subText.isBlank()) {
                        results.add(ChunkResult.builder()
                                .parentChunkId(level1Index)
                                .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                                .chunkText(subText)
                                .titlePath(block.titlePath())
                                .chunkType(ChunkType.SECTION)
                                .sourceStrategy("HtmlChunkStrategy")
                                .build());
                    }
                }
            }
        }

        log.info("HtmlChunkStrategy 分块完成，共 {} 个片段", results.size());
        return results;
    }

    private List<HtmlBlock> splitByDom(String html) {
        List<HtmlBlock> blocks = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        doc.select("script, style, noscript, head, meta, link").remove();

        Element startNode = doc.body() != null ? doc.body() : doc;

        StringBuilder buffer = new StringBuilder();
        Deque<String> titleStack = new ArrayDeque<>();

        walkDom(startNode, blocks, titleStack, buffer);
        flushBuffer(buffer, blocks, titleStack);

        return blocks;
    }

    private void walkDom(Element node, List<HtmlBlock> blocks,
                         Deque<String> titleStack, StringBuilder buffer) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                buffer.append(textNode.getWholeText());
            } else if (child instanceof Element el) {
                String tag = normalizeTag(el);

                if (tag.matches("h[1-6]")) {
                    flushBuffer(buffer, blocks, titleStack);

                    int level = Integer.parseInt(tag.substring(1));
                    String headingText = el.text().trim();
                    if (!headingText.isEmpty()) {
                        while (titleStack.size() >= level) {
                            titleStack.pollLast();
                        }
                        titleStack.offerLast(headingText);
                    }
                    buffer.append(headingText).append(" ");
                } else if ("section".equals(tag) || "article".equals(tag)) {
                    flushBuffer(buffer, blocks, titleStack);
                    int savedStackSize = titleStack.size();

                    walkDom(el, blocks, titleStack, buffer);

                    while (titleStack.size() > savedStackSize) {
                        titleStack.pollLast();
                    }
                    flushBuffer(buffer, blocks, titleStack);
                } else {
                    walkDom(el, blocks, titleStack, buffer);
                }
            }
        }
    }

    private void flushBuffer(StringBuilder buffer, List<HtmlBlock> blocks,
                             Deque<String> titleStack) {
        String text = buffer.toString().trim();
        if (!text.isEmpty()) {
            String titlePath = titleStack.isEmpty() ? null : String.join(" > ", titleStack);
            blocks.add(new HtmlBlock(text, titlePath));
        }
        buffer.setLength(0);
    }

    private static String normalizeTag(Element el) {
        return el.tagName().toLowerCase();
    }

    private List<HtmlBlock> fallbackSplit(String plainText, int maxChunkSize) {
        List<HtmlBlock> blocks = new ArrayList<>();
        RecursiveTextSplitter splitter = new RecursiveTextSplitter(maxChunkSize, 0);
        List<String> subChunks = splitter.refine(plainText);
        for (String subText : subChunks) {
            String trimmed = subText.trim();
            if (!trimmed.isEmpty()) {
                blocks.add(new HtmlBlock(trimmed, null));
            }
        }
        return blocks;
    }

    private record HtmlBlock(String text, String titlePath) {}
}
