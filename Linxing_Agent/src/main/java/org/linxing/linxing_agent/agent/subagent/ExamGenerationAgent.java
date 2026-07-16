package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.agentic.Agent;

/**
 * 测验生成 Agent
 * 读取 AgenticScope 中的 topic / sourceType / materials，
 * 调用 LLM 通过JSON容器工具分批构建结构化测验题目，最终返回 container_id 写入 outputKey "exam_container_id"。
 */
public interface ExamGenerationAgent {

    @Agent
    @SystemMessage(
            "你是一个测验出题专家。根据用户提供的学习素材，生成结构化的知识测验题目。" +
            "题目应覆盖核心知识点，难度适中，答案准确，解析清晰。\n\n" +
            "【重要】你必须使用容器工具分批构建测验题目，严禁一次性输出完整 JSON，" +
            "否则可能被 max_tokens 限制截断导致解析失败。\n" +
            "所有题目追加完毕后，必须调用 save_exam 工具将容器保存到数据库。"
    )
    @UserMessage(
            "请根据以下信息生成测验题目。\n\n" +
            "学习主题：{{topic}}\n" +
            "素材来源：{{sourceType}}\n\n" +
            "参考素材：\n{{materials}}\n\n" +
            "【操作步骤】\n" +
            "1. 调用 create_container 创建容器：\n" +
            "   - container_type = \"exam\"\n" +
            "   - metadata 为 JSON 对象字符串，包含字段：title（测验标题）、" +
            "source_type（notes/web_search/mixed 之一）、source_refs（来源引用字符串数组）\n" +
            "   - array_paths 为 JSON 数组字符串：[\"questions\"]\n" +
            "2. 根据题目内容，多次调用 append_to_container 分批追加题目，每次 1-3 题：\n" +
            "   - container_id 为第 1 步返回的容器ID\n" +
            "   - array_path = \"questions\"\n" +
            "   - items 为 question 对象的 JSON 数组字符串\n" +
            "3. 所有题目追加完毕后，必须调用 save_exam 工具保存测验：\n" +
            "   - container_id 为第 1 步返回的容器ID\n" +
            "4. 调用 save_exam 成功后，最终回复只包含第 1 步返回的容器ID字符串本身，不要输出任何其他内容\n\n" +
            "【question 对象结构】\n" +
            "{\n" +
            "  \"type\": \"single_choice\",\n" +
            "  \"stem\": \"题干\",\n" +
            "  \"options\": [\"A. 选项1\", \"B. 选项2\", \"C. 选项3\", \"D. 选项4\"],\n" +
            "  \"answer\": \"A. 选项1\",\n" +
            "  \"explanation\": \"解析\",\n" +
            "  \"difficulty\": \"easy|medium|hard\"\n" +
            "}\n\n" +
            "【要求】\n" +
            "1. 每道题必须有 type / stem / answer 字段\n" +
            "2. 题型：single_choice / multi_choice / fill_blank / true_false / short_answer\n" +
            "3. 选择题（single_choice / multi_choice）必须有 options 数组\n" +
            "4. 单选题 answer 必须与 options 中某一项完全一致（含字母前缀和文本）\n" +
            "5. 多选题 answer 必须是数组，每个元素与 options 中某一项完全一致\n" +
            "6. 判断题 answer 必须是 \"正确\" 或 \"错误\"\n" +
            "7. 填空题 / 简答题 answer 必须是字符串\n" +
            "8. 默认生成 5 题，混合题型（单选2、多选1、填空1、判断/简答1）\n" +
            "9. 所有字符串值必须用双引号包裹，不要使用单引号；不要输出尾随逗号\n" +
            "10. 严禁一次性输出完整 JSON，必须通过容器工具分批构建\n" +
            "11. 调用 save_exam 后，最终回复只能是 create_container 返回的 container_id 字符串本身，不要包含 save 结果 JSON、解释或其他文字"
    )
    String generateExam(
            @V("topic") String topic,
            @V("sourceType") String sourceType,
            @V("materials") String materials
    );
}
