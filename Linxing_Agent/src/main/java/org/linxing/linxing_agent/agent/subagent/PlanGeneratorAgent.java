package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.agentic.Agent;

/**
 * 学习计划生成 Agent
 * <p>
 * 读取 AgenticScope 中的 topic / goal / duration / source_type / materials / clarification，
 * 调用 LLM 生成结构化学习计划 JSON，写入 outputKey "plan_json"。
 */
public interface PlanGeneratorAgent {

    @Agent
    @SystemMessage(
            "你是一个学习计划生成专家。根据用户提供的学习主题、目标和参考素材，" +
            "生成结构化的学习计划 JSON。计划应循序渐进，阶段清晰，包含可执行的学习任务和里程碑。"
    )
    @UserMessage(
            "请根据以下信息生成学习计划。\n\n" +
            "学习主题：{{topic}}\n" +
            "学习目标：{{goal}}\n" +
            "计划时长：{{duration}}\n" +
            "素材来源：{{sourceType}}\n" +
            "补充说明：{{clarification}}\n\n" +
            "参考素材：\n{{materials}}\n\n" +
            "要求：\n" +
            "1. 必须输出合法 JSON，不要用 Markdown 代码块包裹\n" +
            "2. JSON 结构如下：\n" +
            "{\n" +
            "  \"title\": \"计划标题\",\n" +
            "  \"goal\": \"学习目标\",\n" +
            "  \"description\": \"计划描述\",\n" +
            "  \"duration\": \"计划时长\",\n" +
            "  \"source_type\": \"notes|web_search|mixed\",\n" +
            "  \"phases\": [\n" +
            "    {\n" +
            "      \"title\": \"阶段标题\",\n" +
            "      \"duration\": \"阶段时长\",\n" +
            "      \"objective\": \"阶段目标\",\n" +
            "      \"key_topics\": [\"知识点1\", \"知识点2\"],\n" +
            "      \"resources\": [{\"name\": \"资源名\", \"url\": \"\"}],\n" +
            "      \"practice_tasks\": [\"任务1\"],\n" +
            "      \"milestones\": [\"里程碑1\"]\n" +
            "    }\n" +
            "  ],\n" +
            "  \"source_refs\": [\"来源引用\"]\n" +
            "}\n" +
            "3. phases 数组不能为空，每个 phase 必须有 title\n" +
            "4. key_topics / practice_tasks / milestones 必须是字符串数组\n" +
            "5. source_type 必须是 notes / web_search / mixed 之一\n" +
            "6. 阶段数建议 3-8 个，循序渐进\n" +
            "7. 只输出 JSON，不要输出任何其他文字"
    )
    String generatePlan(
            @V("topic") String topic,
            @V("goal") String goal,
            @V("duration") String duration,
            @V("sourceType") String sourceType,
            @V("materials") String materials,
            @V("clarification") String clarification
    );
}
