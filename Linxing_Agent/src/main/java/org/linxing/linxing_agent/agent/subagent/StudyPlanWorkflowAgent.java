package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;

/**
 * study_plan 工作流类型化接口
 * 主工作流入口，输入 @V 参数从 Tool 层传入的初始 Map 中读取，子 Agent 在执行过程中向 AgenticScope 写入 plan_json / exam_json / clarification 等中间结果。
 */
public interface StudyPlanWorkflowAgent {

    @Agent
    StudyPlanWorkflowResult execute(
            @V("topic") String topic,
            @V("goal") String goal,
            @V("duration") String duration,
            @V("materials") String materials,
            @V("sourceType") String sourceType,
            @V("generate_exam") Boolean generateExam,
            @V("needs_clarification") Boolean needsClarification,
            @V("clarification_question") String clarificationQuestion
    );
}
