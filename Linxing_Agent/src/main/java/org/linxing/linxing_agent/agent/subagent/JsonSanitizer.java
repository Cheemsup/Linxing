package org.linxing.linxing_agent.agent.subagent;

/**
 * LLM 输出 JSON 清洗工具
 * <p>
 * Agent 生成的 JSON 常见污染：Markdown 代码块包裹（```json ... ```）、
 * 前后多余解释文字、尾随逗号等。本工具提取最外层 JSON 对象，提升解析容错性。
 */
public final class JsonSanitizer {

    private JsonSanitizer() {
    }

    /**
     * 清洗 LLM 输出，提取最外层 JSON 对象字符串。
     * <ol>
     *   <li>去除 Markdown 代码块围栏（```json / ```）</li>
     *   <li>定位第一个 '{'，按字符串/转义感知匹配对应的最后一个 '}'</li>
     *   <li>返回截取后的子串；找不到合法对象时返回原文本 trim 后的结果</li>
     * </ol>
     *
     * @param raw LLM 原始输出，可为 null
     * @return 清洗后的 JSON 字符串；输入为 null/空白时返回空串
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = stripCodeFence(raw).trim();
        int end = findJsonObjectEnd(text);
        if (end > 0) {
            return text.substring(0, end + 1);
        }
        // 找不到合法对象边界，返回 trim 后的原文交由 ObjectMapper 报错
        return text;
    }

    /**
     * 去除 Markdown 代码块围栏。仅处理首尾成对的围栏，避免误删内容。
     */
    private static String stripCodeFence(String raw) {
        String text = raw.trim();
        // 形如 ```json\n...\n``` 或 ```\n...\n```
        if (text.startsWith("```")) {
            // 去掉开头的 ```json / ``` 行
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            // 去掉结尾的 ```
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
        }
        return text;
    }

    /**
     * 定位最外层 JSON 对象的结束索引（第一个 '{' 对应的匹配 '}'）。
     * 感知字符串字面量与转义字符，避免被字符串内的括号干扰。
     *
     * @return 结束 '}' 的索引；若不存在合法对象返回 -1
     */
    private static int findJsonObjectEnd(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        // 括号不匹配，返回 -1
        return -1;
    }
}
