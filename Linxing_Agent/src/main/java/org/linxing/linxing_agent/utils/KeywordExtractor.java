package org.linxing.linxing_agent.utils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从用户问题中提取关键词，生成 PostgreSQL tsquery 格式字符串，供 BM25 全文检索使用。
 */
public class KeywordExtractor {

    // 中文常见停用词，过滤后可提升检索精度
    private static final Set<String> CHINESE_STOP_WORDS = Set.of(
            "的", "了", "是", "在", "有", "和", "与", "或", "不", "也", "都",
            "这", "那", "个", "一", "我", "你", "他", "她", "它", "们",
            "什么", "怎么", "如何", "为什么", "哪", "哪些", "多少", "几",
            "可以", "能", "会", "要", "把", "被", "让", "给", "从", "到",
            "着", "过", "得", "地", "就", "还", "又", "再", "已", "曾"
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

    /**
     * 将用户问题提取为 tsquery 字符串，关键词之间用 & (AND) 连接。
     */
    public static String extractToTsquery(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }

        // 分词 → 过滤停用词/标点/短token → 去重 → 用 & 拼接为 tsquery
        List<String> tokens = tokenize(question);

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
                .collect(Collectors.joining(" & "));
    }

    /**
     * 判断 token 是否应保留：中文字符单字保留，英文/数字需长度≥2。
     */
    private static boolean shouldKeepToken(String token) {
        if (token.isEmpty()) return false;
        char firstChar = token.charAt(0);
        if (isChineseChar(firstChar)) {
            return true;
        }
        return token.length() >= 2;
    }

    /**
     * 简易分词：中文逐字切分，英文按空白和标点切分为词，中英文交界处自动断开。
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean lastWasChinese = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (isChineseChar(ch)) {
                if (current.length() > 0 && !lastWasChinese) {
                    addSplitTokens(current.toString(), tokens);
                    current.setLength(0);
                }
                tokens.add(String.valueOf(ch));
                lastWasChinese = true;
            } else if (Character.isWhitespace(ch) || isPunctuationChar(ch)) {
                if (current.length() > 0) {
                    addSplitTokens(current.toString(), tokens);
                    current.setLength(0);
                }
                lastWasChinese = false;
            } else {
                if (lastWasChinese && current.length() > 0) {
                    addSplitTokens(current.toString(), tokens);
                    current.setLength(0);
                }
                current.append(ch);
                lastWasChinese = false;
            }
        }

        if (current.length() > 0) {
            addSplitTokens(current.toString(), tokens);
        }

        return tokens;
    }

    // 按常见分隔符进一步拆分非中文文本片段
    private static void addSplitTokens(String text, List<String> tokens) {
        String[] parts = text.split("[\\s\\-_/.,;:!?()\\[\\]{}\"'<>|@#$%^&*+=~`\\\\]+");
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
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
        return ch == '\uFF0C' || ch == '\u3002' || ch == '\uFF01' || ch == '\uFF1F' || ch == '\uFF1B' || ch == '\uFF1A'
                || ch == '\u201C' || ch == '\u201D' || ch == '\u2018' || ch == '\u2019'
                || ch == '\uFF08' || ch == '\uFF09' || ch == '\u3010' || ch == '\u3011'
                || ch == '\u300A' || ch == '\u300B' || ch == '\u3001'
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
