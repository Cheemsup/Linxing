package org.linxing.linxing_agent.rag.utils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从用户问题中提取关键词，生成 PostgreSQL tsquery 格式字符串，供 BM25 全文检索使用。
 *
 * <p>分词复用 {@link ChineseSegmenter}（Jieba），与入库侧 {@code FullTextIndexer} 完全一致，
 * 保证 tsquery 词条与 {@code ts_content} 索引词条精确匹配——PG '@@' 要求词条完全一致，
 * 若查询侧另行逐字切分（旧实现）则中文单字永远匹配不到索引里的多字词，BM25 恒为 0 候选。
 */
public class KeywordExtractor {

    // 中文常见停用词，过滤后可提升检索精度
    private static final Set<String> CHINESE_STOP_WORDS = Set.of(
            "的", "了", "是", "在", "有", "和", "与", "或", "不", "也", "都",
            "这", "那", "个", "一", "我", "你", "他", "她", "它", "们",
            "什么", "怎么", "如何", "为什么", "哪", "哪些", "多少", "几",
            "可以", "能", "会", "要", "把", "被", "让", "给", "从", "到",
            "着", "过", "得", "地", "就", "还", "又", "再", "已", "曾",
            // 口语/语篇层高频词（长问句常见，参与 AND 会严重收窄召回，过滤后更贴近检索意图）
            "请", "请问", "告诉", "想要", "需要", "关于", "对于", "比如", "例如", "还有", "以及"
    );

    // 英文常见停用词
    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "can", "shall", "to", "of", "in", "for",
            "on", "with", "at", "by", "from", "as", "into", "through", "during",
            "before", "after", "above", "below", "between", "out", "off", "over",
            "under", "again", "further", "then", "once", "here", "there", "when",
            "where", "why", "how", "all", "both", "each", "few", "more", "most",
            "other", "some", "such", "no", "nor", "not", "only", "own", "same",
            "so", "than", "too", "very", "just", "because", "but", "and", "or",
            "if", "while", "about", "what", "which", "who", "whom", "this", "that",
            "these", "those", "it", "its", "i", "me", "my", "we", "our", "you",
            "your", "he", "him", "his", "she", "her", "they", "them", "their"
    );

    /** tsquery 关键词连接方式 */
    private enum JoinOperator {
        AND(" & "), OR(" | ");
        private final String symbol;

        JoinOperator(String symbol) {
            this.symbol = symbol;
        }
    }

    /**
     * 将用户问题提取为 tsquery，关键词之间用 & (AND) 连接。
     * AND 语义精确但较严：多关键词长问句可能无候选（由调用方以 OR 语义兜底）。
     */
    public static String extractToTsquery(String question) {
        return build(question, JoinOperator.AND);
    }

    /**
     * 将用户问题提取为 tsquery，关键词之间用 | (OR) 连接。
     * 用于 AND 返回 0 候选时的召回放宽兜底；配合 ts_rank 排序仍能保证多词命中者优先。
     */
    public static String extractToTsqueryOr(String question) {
        return build(question, JoinOperator.OR);
    }

    private static String build(String question, JoinOperator join) {
        if (question == null || question.isBlank()) {
            return "";
        }

        // 与索引侧同源分词（ChineseSegmenter/Jieba SEARCH），按空格还原词条列表
        List<String> tokens = splitSegmented(ChineseSegmenter.segment(question));

        List<String> keywords = tokens.stream()
                .filter(KeywordExtractor::shouldKeepToken)
                .filter(token -> !CHINESE_STOP_WORDS.contains(token))
                .filter(token -> !ENGLISH_STOP_WORDS.contains(token.toLowerCase()))
                .filter(token -> !isPunctuation(token))
                .distinct()
                .collect(Collectors.toList());

        if (keywords.isEmpty()) {
            return "";
        }

        return keywords.stream()
                .map(KeywordExtractor::sanitizeForTsquery)
                .collect(Collectors.joining(join.symbol));
    }

    /** 将 Jieba 输出的空格分隔词串还原为词条列表（null/空白 → 空列表） */
    private static List<String> splitSegmented(String segmented) {
        if (segmented == null || segmented.isBlank()) {
            return List.of();
        }
        return Arrays.stream(segmented.trim().split("\\s+"))
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 判断 token 是否应保留：中文字符单字保留，英文/数字需长度≥2。
     * 说明：Jieba 已把中文切成词/字，单字中文多为 Jieba 词典内的单字词，
     * 若索引侧同样切出该单字则可精确匹配，故直接保留。
     */
    private static boolean shouldKeepToken(String token) {
        if (token.isEmpty()) return false;
        char firstChar = token.charAt(0);
        if (isChineseChar(firstChar)) {
            return true;
        }
        return token.length() >= 2;
    }

    // 判断字符是否属于 CJK 统一汉字区块
    private static boolean isChineseChar(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    // 判断整个 token 是否全为标点
    private static boolean isPunctuation(String token) {
        if (token.length() == 1) {
            return isPunctuationChar(token.charAt(0));
        }
        return token.chars().allMatch(KeywordExtractor::isPunctuationChar);
    }

    // 判断单个字符是否为中英文标点
    private static boolean isPunctuationChar(int ch) {
        return ch == '，' || ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '：'
                || ch == '“' || ch == '”' || ch == '‘' || ch == '’'
                || ch == '（' || ch == '）' || ch == '【' || ch == '】'
                || ch == '《' || ch == '》' || ch == '、'
                || ch == ',' || ch == '.' || ch == '!' || ch == '?' || ch == ';'
                || ch == ':' || ch == '(' || ch == ')' || ch == '[' || ch == ']'
                || ch == '{' || ch == '}' || ch == '<' || ch == '>'
                || ch == '"' || ch == '\'' || ch == '|' || ch == '@'
                || ch == '#' || ch == '$' || ch == '%' || ch == '^'
                || ch == '&' || ch == '*' || ch == '+' || ch == '='
                || ch == '~' || ch == '`' || ch == '\\' || ch == '/';
    }

    // 移除 tsquery 语法中的特殊字符，防止注入
    private static String sanitizeForTsquery(String token) {
        return token.replaceAll("['&|!()<>]", "");
    }
}
