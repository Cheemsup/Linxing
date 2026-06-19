package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.agentic.Agent;

/**
 * 测验生成 Agent
 * <p>
 * 读取 AgenticScope 中的 topic / sourceType / materials，
 * 调用 LLM 生成结构化测验 JSON，写入 outputKey "exam_json"。
 */
public interface ExamGeneratorAgent {

    @Agent
    @SystemMessage(
            "你是一个测验出题专家。根据用户提供的学习素材，生成结构化的知识测验题目。" +
            "题目应覆盖核心知识点，难度适中，答案准确，解析清晰。"
    )
    @UserMessage(
            "请根据以下信息生成测验题目。\n\n" +
            "学习主题：{{topic}}\n" +
            "素材来源：{{sourceType}}\n\n" +
            "参考素材：\n{{materials}}\n\n" +
            "要求：\n" +
            "1. 必须输出合法 JSON，不要用 Markdown 代码块包裹\n" +
            "2. JSON 结构如下：\n" +
            "{\n" +
            "  \"title\": \"测验标题\",\n" +
            "  \"source_type\": \"notes|web_search|mixed\",\n" +
            "  \"questions\": [\n" +
            "    {\n" +
            "      \"type\": \"single_choice\",\n" +
            "      \"stem\": \"题干\",\n" +
            "      \"options\": [\"A. 选项1\", \"B. 选项2\", \"C. 选项3\", \"D. 选项4\"],\n" +
            "      \"answer\": \"A. 选项1\",\n" +
            "      \"explanation\": \"解析\",\n" +
            "      \"difficulty\": \"easy|medium|hard\"\n" +
            "    }\n" +
            "  ],\n" +
            "  \"source_refs\": [\"来源引用\"]\n" +
            "}\n" +
            "3. 每道题必须有 type / stem / answer 字段\n" +
            "4. 题型：single_choice / multi_choice / fill_blank / true_false / short_answer\n" +
            "5. 选择题（single_choice / multi_choice）必须有 options 数组\n" +
            "6. 单选题 answer 必须与 options 中某一项完全一致（含字母前缀和文本）\n" +
            "7. 多选题 answer 必须是数组，每个元素与 options 中某一项完全一致\n" +
            "8. 判断题 answer 必须是 \"正确\" 或 \"错误\"\n" +
            "9. 填空题 / 简答题 answer 必须是字符串\n" +
            "10. 默认生成 5 题，混合题型（单选2、多选1、填空1、判断/简答1）\n" +
            "11. 只输出 JSON，不要输出任何其他文字"
    )
    String generateExam(
            @V("topic") String topic,
            @V("sourceType") String sourceType,
            @V("materials") String materials
    );
}
