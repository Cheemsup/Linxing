package org.linxing.linxing_agent.strategy.impl;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.ChunkType;
import org.linxing.linxing_agent.constant.RagParameters;
import org.linxing.linxing_agent.strategy.RecursiveTextSplitter;
import org.linxing.linxing_agent.strategy.ChunkResult;
import org.linxing.linxing_agent.strategy.ChunkStrategy;
import org.linxing.linxing_agent.strategy.ChunkStrategyContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 分块策略，按 h1-h6 标题或 section/article 标签拆分，自动剥离 HTML 标签后输出纯文本块。
 * HTML 去标签后语义密度较低，使用比通用策略更大的 overlap 以保留 section 间的过渡上下文。
 */
@Slf4j
@Component("htmlChunkStrategy")
public class HtmlChunkStrategy implements ChunkStrategy {

    private static final int DEFAULT_MAX_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "<(h[1-6])[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "<(section|article)[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public boolean supports(ChunkStrategyContext context) {
        String ext = context.getFileType();
        if (ext != null && (ext.equalsIgnoreCase("html") || ext.equalsIgnoreCase("htm"))) {
            return true;
        }
        return false;
    }

    @Override
    public List<ChunkResult> execute(ChunkStrategyContext context) {
        int maxChunkSize = context.getMaxChunkSize() != null ? context.getMaxChunkSize() : DEFAULT_MAX_CHUNK_SIZE;
        int chunkOverlap = context.getChunkOverlap() != null ? context.getChunkOverlap() : DEFAULT_CHUNK_OVERLAP;
        String fullText = context.getFullText();

        RecursiveTextSplitter refinementPipeline = new RecursiveTextSplitter(maxChunkSize, chunkOverlap);

        List<HtmlBlock> blocks = splitByHeadingsOrSections(fullText);

        if (blocks.size() <= 1) {
            blocks = fallbackSplit(fullText, maxChunkSize);
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

                List<String> subChunks = refinementPipeline.refine(stripHtmlTags(blockText));
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

    private List<HtmlBlock> splitByHeadingsOrSections(String html) {
        List<HtmlBlock> blocks = new ArrayList<>();

        List<HeadingMatch> headings = new ArrayList<>();
        Matcher headingMatcher = HEADING_PATTERN.matcher(html);
        while (headingMatcher.find()) {
            headings.add(new HeadingMatch(headingMatcher.start(), headingMatcher.end(),
                    headingMatcher.group(1), headingMatcher.group(2)));
        }

        Matcher sectionMatcher = SECTION_PATTERN.matcher(html);
        boolean hasSections = sectionMatcher.find();

        if (hasSections && headings.isEmpty()) {
            sectionMatcher.reset();
            while (sectionMatcher.find()) {
                String sectionContent = sectionMatcher.group(2);
                String sectionTag = sectionMatcher.group(1);
                String innerTitle = extractFirstHeading(sectionContent);
                String titlePath = innerTitle != null ? innerTitle : sectionTag;
                blocks.add(new HtmlBlock(sectionContent, titlePath));
            }
            return blocks;
        }

        if (hasSections) {
            sectionMatcher.reset();
            while (sectionMatcher.find()) {
                String sectionContent = sectionMatcher.group(2);
                String sectionTag = sectionMatcher.group(1);
                List<HtmlBlock> innerBlocks = splitSectionByHeadings(sectionContent, sectionTag, headings);
                if (innerBlocks.isEmpty()) {
                    String innerTitle = extractFirstHeading(sectionContent);
                    String titlePath = innerTitle != null ? innerTitle : sectionTag;
                    blocks.add(new HtmlBlock(sectionContent, titlePath));
                } else {
                    blocks.addAll(innerBlocks);
                }
            }
            return blocks;
        }

        if (headings.isEmpty()) {
            blocks.add(new HtmlBlock(html, null));
            return blocks;
        }

        if (headings.get(0).start() > 0) {
            String preamble = stripHtmlTags(html.substring(0, headings.get(0).start())).trim();
            if (!preamble.isEmpty()) {
                blocks.add(new HtmlBlock(preamble, null));
            }
        }

        String[] titleStack = new String[6];
        for (int i = 0; i < headings.size(); i++) {
            HeadingMatch h = headings.get(i);
            int level = Integer.parseInt(h.tag().substring(1)) - 1;
            String headingText = stripHtmlTags(h.text());

            titleStack[level] = headingText;
            for (int j = level + 1; j < 6; j++) {
                titleStack[j] = null;
            }

            StringBuilder titlePath = new StringBuilder();
            for (int j = 0; j < 6; j++) {
                if (titleStack[j] != null) {
                    if (titlePath.length() > 0) {
                        titlePath.append(" > ");
                    }
                    titlePath.append(titleStack[j]);
                }
            }

            int contentEnd = (i + 1 < headings.size()) ? headings.get(i + 1).start() : html.length();
            String blockText = stripHtmlTags(html.substring(h.start(), contentEnd)).trim();
            if (!blockText.isEmpty()) {
                blocks.add(new HtmlBlock(blockText, titlePath.toString()));
            }
        }

        return blocks;
    }

    private List<HtmlBlock> splitSectionByHeadings(String sectionContent, String sectionTag, List<HeadingMatch> docHeadings) {
        List<HtmlBlock> blocks = new ArrayList<>();
        List<HeadingMatch> innerHeadings = new ArrayList<>();
        Matcher headingMatcher = HEADING_PATTERN.matcher(sectionContent);
        while (headingMatcher.find()) {
            innerHeadings.add(new HeadingMatch(headingMatcher.start(), headingMatcher.end(),
                    headingMatcher.group(1), headingMatcher.group(2)));
        }

        if (innerHeadings.isEmpty()) {
            return blocks;
        }

        String[] titleStack = new String[6];
        for (int i = 0; i < innerHeadings.size(); i++) {
            HeadingMatch h = innerHeadings.get(i);
            int level = Integer.parseInt(h.tag().substring(1)) - 1;
            String headingText = stripHtmlTags(h.text());

            titleStack[level] = headingText;
            for (int j = level + 1; j < 6; j++) {
                titleStack[j] = null;
            }

            StringBuilder titlePath = new StringBuilder();
            for (int j = 0; j < 6; j++) {
                if (titleStack[j] != null) {
                    if (titlePath.length() > 0) {
                        titlePath.append(" > ");
                    }
                    titlePath.append(titleStack[j]);
                }
            }

            int contentEnd = (i + 1 < innerHeadings.size()) ? innerHeadings.get(i + 1).start() : sectionContent.length();
            String blockText = stripHtmlTags(sectionContent.substring(h.start(), contentEnd)).trim();
            if (!blockText.isEmpty()) {
                blocks.add(new HtmlBlock(blockText, titlePath.toString()));
            }
        }

        return blocks;
    }

    private String extractFirstHeading(String html) {
        Matcher m = HEADING_PATTERN.matcher(html);
        if (m.find()) {
            return stripHtmlTags(m.group(2));
        }
        return null;
    }

    private List<HtmlBlock> fallbackSplit(String html, int maxChunkSize) {
        List<HtmlBlock> blocks = new ArrayList<>();
        String plainText = stripHtmlTags(html);
        int start = 0;
        while (start < plainText.length()) {
            int end = Math.min(start + maxChunkSize, plainText.length());
            blocks.add(new HtmlBlock(plainText.substring(start, end), null));
            start = end;
        }
        return blocks;
    }

    private String stripHtmlTags(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("\\s+", " ");
    }

    private record HeadingMatch(int start, int end, String tag, String text) {}
    private record HtmlBlock(String text, String titlePath) {}
}
