package org.linxing.linxing_agent.rag.utils;

import java.util.regex.Pattern;

/**
 * 思维链（Chain-of-Thought）内容清洗工具。
 * <p>LLM 在生成语义增强描述（图片/代码/表格）时，可能把内部推理一并输出为
 * {@code <think>...</think>} 等标记块（DeepSeek 等开启思考的模型尤为常见），这些内容
 * 会污染向量化文本与入库的语义描述。本工具在向量化/入库前剥除这些标记块。
 * <p>支持的常见标记：
 * <ul>
 *   <li>尖括号：{@code <think>} / {@code <thought>} / {@code <thinking>} / {@code <reasoning>}（大小写不敏感、跨行）</li>
 *   <li>方括号：{@code [think]…[/think]} 等（Cohere/Jina 风格）</li>
 *   <li>历史变体：{@code -thinking-}（旧 SemanticEnhancementServiceImpl 兼容）</li>
 * </ul>
 * 未闭合的块按"从开始标记到文本末尾"剥除（模型输出中断时剩余部分几乎都是推理内容）。
 */
public final class ChainOfThoughtStripper {

    /** 思维链标记名集合（含 common 变体） */
    private static final String TAGS = "(?:think|thinking|thought|reasoning)";

    /** 成对尖括号块：{@code <think>...</think>} */
    private static final Pattern COT_ANGLE_PAIRED = Pattern.compile(
            "(?is)<\\s*" + TAGS + "\\b[^>]*>.*?</\\s*" + TAGS + "\\s*>");
    /** 成对方括号块：{@code [think]...[/think]} */
    private static final Pattern COT_BRACKET_PAIRED = Pattern.compile(
            "(?is)\\[\\s*" + TAGS + "\\b[^\\]]*\\].*?\\[/\\s*" + TAGS + "\\s*\\]");
    /** 历史变体：{@code -thinking...-thinking} */
    private static final Pattern COT_DASH_PAIRED = Pattern.compile(
            "(?is)-thinking[\\s\\S]*?-thinking");
    /** 未闭合尖括号块：{@code <think>...} 至文本末尾 */
    private static final Pattern COT_ANGLE_UNCLOSED = Pattern.compile(
            "(?is)<\\s*" + TAGS + "\\b[^>]*>.*$");
    /** 未闭合方括号块：{@code [think]...} 至文本末尾 */
    private static final Pattern COT_BRACKET_UNCLOSED = Pattern.compile(
            "(?is)\\[\\s*" + TAGS + "\\b[^\\]]*\\].*$");

    /** 先剥成对块，再剥未闭合块，避免未闭合模式误吞后续成对块 */
    private static final Pattern[] PATTERNS = {
            COT_ANGLE_PAIRED, COT_BRACKET_PAIRED, COT_DASH_PAIRED,
            COT_ANGLE_UNCLOSED, COT_BRACKET_UNCLOSED
    };

    private ChainOfThoughtStripper() {
    }

    /**
     * 剥除文本中的思维链标记块，并去除首尾空白。
     *
     * @param text 原始文本（可为 null）
     * @return 清洗后的文本；入参为 null 时返回 null
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll("");
        }
        return result.trim();
    }
}
