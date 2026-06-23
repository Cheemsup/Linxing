package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.agentic.Agent;

/**
 * 学习计划生成 Agent
 * <p>
 * 读取 AgenticScope 中的 topic / goal / duration / source_type / materials / clarification，
 * 调用 LLM 通过容器工具分批构建结构化学习计划，最终返回 container_id 写入 outputKey "plan_container_id"。
 * <p>
 * 使用容器工具分批构建可绕过 max_tokens 限制，避免长 JSON 被截断导致解析失败。
 */
public interface PlanGeneratorAgent {

    @Agent
    @SystemMessage(
            "你是一个学习计划生成专家。根据用户提供的学习主题、目标和参考素材，" +
            "生成结构化的学习计划。计划应循序渐进，阶段清晰，包含可执行的学习任务和里程碑。\n\n" +
            "【重要】由于学习计划内容较长，你必须使用容器工具分批构建，严禁一次性输出完整 JSON，" +
            "否则会被 max_tokens 限制截断导致解析失败。\n" +
            "所有阶段追加完毕后，必须调用 save_study_plan 工具将容器保存到数据库。"
    )
    @UserMessage(
            "请根据以下信息生成学习计划。\n\n" +
            "学习主题：{{topic}}\n" +
            "学习目标：{{goal}}\n" +
            "计划时长：{{duration}}\n" +
            "素材来源：{{sourceType}}\n" +
            "补充说明：{{clarification}}\n\n" +
            "参考素材：\n{{materials}}\n\n" +
            "【操作步骤】\n" +
            "1. 调用 create_container 创建容器：\n" +
            "   - container_type = \"study_plan\"\n" +
            "   - metadata 为 JSON 对象字符串，包含字段：title（计划标题）、goal（学习目标）、" +
            "description（计划描述）、duration（计划时长）、source_type（notes/web_search/mixed 之一）、" +
            "source_refs（来源引用字符串数组）\n" +
            "   - array_paths 为 JSON 数组字符串：[\"phases\"]\n" +
            "2. 根据计划内容，多次调用 append_to_container 分批追加阶段，每次 1-3 个 phase：\n" +
            "   - container_id 为第 1 步返回的容器ID\n" +
            "   - array_path = \"phases\"\n" +
            "   - items 为 phase 对象的 JSON 数组字符串\n" +
            "3. 所有阶段追加完毕后，必须调用 save_study_plan 工具保存学习计划：\n" +
            "   - container_id 为第 1 步返回的容器ID\n" +
            "4. 调用 save_study_plan 成功后，最终回复只包含第 1 步返回的容器ID字符串本身，不要输出任何其他内容\n\n" +
            "【phase 对象结构】\n" +
            "{\n" +
            "  \"title\": \"阶段标题\",\n" +
            "  \"duration\": \"阶段时长\",\n" +
            "  \"objective\": \"阶段目标\",\n" +
            "  \"key_topics\": [\"知识点1\", \"知识点2\"],\n" +
            "  \"resources\": [{\"name\": \"资源名\", \"url\": \"\"}],\n" +
            "  \"practice_tasks\": [\"任务1\"],\n" +
            "  \"milestones\": [\"里程碑1\"]\n" +
            "}\n\n" +
            "【要求】\n" +
            "1. phases 数组不能为空，每个 phase 必须有 title\n" +
            "2. key_topics / practice_tasks / milestones 必须是字符串数组\n" +
            "3. source_type 必须是 notes / web_search / mixed 之一\n" +
            "4. 阶段数建议 3-8 个，循序渐进\n" +
            "5. 所有字符串值必须用双引号包裹，不要使用单引号；不要输出尾随逗号\n" +
            "6. 严禁一次性输出完整 JSON，必须通过容器工具分批构建\n" +
            "7. 调用 save_study_plan 后，最终回复只能是 create_container 返回的 container_id 字符串本身，不要包含 save 结果 JSON、解释或其他文字"
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
