package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 知识收集 Agent。
 * <p>
 * 装配 {@link org.linxing.linxing_agent.agent.tool.impl.WebSearchTool} 与
 * {@link org.linxing.linxing_agent.agent.tool.impl.RagSearchTool} 后，
 * LLM 在内部 tool-calling 循环中自主决定搜索次数与关键词，
 * 将收集到的素材整理为结构化摘要，写入 outputKey "materials"。
 * <p>
 * 与 {@link PlanGeneratorAgent} 的区别：本 Agent 不直接生成计划 JSON，
 * 只负责为后续内容生成阶段准备素材背景。
 */
public interface KnowledgeCollectorAgent {

    @Agent
    @SystemMessage(
            "你是一个知识收集专家。根据学习主题和目标，使用提供的搜索工具收集相关学习素材。\n" +
            "可以多次搜索不同关键词，确保素材覆盖主题的核心知识点。\n" +
            "将收集到的素材整理为结构化摘要返回，包含：\n" +
            "1. 主题核心概念与知识脉络\n" +
            "2. 推荐的学习路径与阶段划分建议\n" +
            "3. 关键资源（书籍、文档、课程等）\n" +
            "4. 常见学习难点与注意事项\n" +
            "返回纯文本摘要，不要输出 JSON。"
    )
    @UserMessage(
            "请使用搜索工具收集学习素材并整理摘要。\n\n" +
            "学习主题：{{topic}}\n" +
            "学习目标：{{goal}}\n" +
            "计划时长：{{duration}}\n" +
            "已有补充信息：{{clarification}}\n" +
            "已有素材（若非空可补充搜索）：\n{{materials}}\n\n" +
            "要求：\n" +
            "1. 优先搜索用户个人知识库（searchKnowledgeBase），了解用户已有笔记基础\n" +
            "2. 根据主题需要联网搜索（webSearch）补充外部知识\n" +
            "3. 至少进行 1 次搜索，若素材不足可多次搜索不同关键词\n" +
            "4. 整理后的摘要应足以支撑后续学习计划生成，无需再额外搜索"
    )
    String collectKnowledge(
            @V("topic") String topic,
            @V("goal") String goal,
            @V("duration") String duration,
            @V("clarification") String clarification,
            @V("materials") String materials
    );
}
