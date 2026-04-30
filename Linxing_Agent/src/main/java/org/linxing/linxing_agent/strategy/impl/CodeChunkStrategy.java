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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码分块策略，按类和函数定义拆分源代码文件，生成带有类名/函数名标题路径的代码块。
 * 代码函数/类应尽量保持完整不截断，因此使用更大的 maxChunkSize 且不需要 overlap。
 */
@Slf4j
@Component("codeChunkStrategy")
public class CodeChunkStrategy implements ChunkStrategy {

    private static final int DEFAULT_MAX_CHUNK_SIZE = 1500;
    private static final int DEFAULT_CHUNK_OVERLAP = 0;

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "py", "js", "ts", "go", "rs", "c", "cpp", "cs", "kt",
            "rb", "php", "swift", "scala", "hs", "lua", "r", "sh", "bash", "sql"
    );

    private static final Pattern CODE_INDICATOR = Pattern.compile(
            "\\b(package|import|class|public class|private class|def |function |fn |func |int main|void main)\\b");

    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
            "^(\\s*)((?:public|private|protected|static|final|abstract|synchronized|async|def|fn|func|function)\\s+)+" +
                    "\\S+\\s+\\w+\\s*\\([^)]*\\)\\s*\\{?",
            Pattern.MULTILINE);

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^(\\s*)((?:public|private|protected|abstract|final|sealed|open|data)?\\s*)" +
                    "(class|interface|enum|object|trait|struct|impl)\\s+(\\w+)",
            Pattern.MULTILINE);

    @Override
    public boolean supports(ChunkStrategyContext context) {
        String ext = context.getFileType();
        if (ext != null && CODE_EXTENSIONS.contains(ext.toLowerCase())) {
            return true;
        }

        String text = context.getFullText();
        if (text != null) {
            String sample = text.substring(0, Math.min(500, text.length()));
            return CODE_INDICATOR.matcher(sample).find();
        }

        return false;
    }

    @Override
    public List<ChunkResult> execute(ChunkStrategyContext context) {
        int maxChunkSize = context.getMaxChunkSize() != null ? context.getMaxChunkSize() : DEFAULT_MAX_CHUNK_SIZE;
        int chunkOverlap = context.getChunkOverlap() != null ? context.getChunkOverlap() : DEFAULT_CHUNK_OVERLAP;
        String fullText = context.getFullText();

        RecursiveTextSplitter refinementPipeline = new RecursiveTextSplitter(maxChunkSize, chunkOverlap);

        List<CodeBlock> blocks = splitByClassOrFunction(fullText);
        List<ChunkResult> results = new ArrayList<>();

        for (CodeBlock block : blocks) {
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
                        .chunkType(ChunkType.CODE)
                        .sourceStrategy("CodeChunkStrategy")
                        .build());
            } else {
                List<String> subChunks = refinementPipeline.refine(blockText);
                for (String subText : subChunks) {
                    if (!subText.isBlank()) {
                        results.add(ChunkResult.builder()
                                .parentChunkId(null)
                                .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                                .chunkText(subText)
                                .titlePath(block.titlePath())
                                .chunkType(ChunkType.CODE)
                                .sourceStrategy("CodeChunkStrategy")
                                .build());
                    }
                }
            }
        }

        log.info("CodeChunkStrategy 分块完成，共 {} 个片段", results.size());
        return results;
    }

    private List<CodeBlock> splitByClassOrFunction(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return blocks;
        }

        List<Match> matches = new ArrayList<>();
        Matcher classMatcher = CLASS_PATTERN.matcher(text);
        while (classMatcher.find()) {
            matches.add(new Match(classMatcher.start(), "class", classMatcher.group(4)));
        }

        Matcher funcMatcher = FUNCTION_PATTERN.matcher(text);
        while (funcMatcher.find()) {
            matches.add(new Match(funcMatcher.start(), "function", extractFuncName(funcMatcher.group())));
        }

        if (matches.isEmpty()) {
            blocks.add(new CodeBlock(text, null));
            return blocks;
        }

        matches.sort((a, b) -> Integer.compare(a.start(), b.start()));

        if (matches.get(0).start() > 0) {
            String preamble = text.substring(0, matches.get(0).start()).trim();
            if (!preamble.isEmpty()) {
                blocks.add(new CodeBlock(preamble, null));
            }
        }

        String currentClassName = null;
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            String blockTitlePath;
            if ("class".equals(m.type())) {
                currentClassName = m.name();
                blockTitlePath = m.name();
            } else if (currentClassName != null) {
                blockTitlePath = currentClassName + " > " + m.name();
            } else {
                blockTitlePath = m.name();
            }

            int end = (i + 1 < matches.size()) ? matches.get(i + 1).start() : text.length();
            String blockText = text.substring(m.start(), end).trim();
            if (!blockText.isEmpty()) {
                blocks.add(new CodeBlock(blockText, blockTitlePath));
            }
        }

        return blocks;
    }

    private String extractFuncName(String signature) {
        Pattern namePattern = Pattern.compile("\\s+(\\w+)\\s*\\(");
        Matcher m = namePattern.matcher(signature);
        if (m.find()) {
            return m.group(1);
        }
        return signature.replaceAll("\\s+", " ").substring(0, Math.min(30, signature.length()));
    }

    private record Match(int start, String type, String name) {}
    private record CodeBlock(String text, String titlePath) {}
}
