package org.linxing.linxing_agent.agent.memory.longterm.workspace;

/**
 * Long-term Memory 各 Markdown 文件的最小可用模板（V1）。
 * <p>半结构化 Markdown：一二级标题固定，仅允许修改 Section 内容，约束 LLM 修改行为使 Memory 长期稳定。
 * 模板细节列入后续待讨论项，此处仅提供 V1 最小可用版。
 * 
 * //TODO：直接将这部分的内容写到对应的markdown文档中，不再依赖现在的硬编码方式读取
 */
public final class MemoryTemplates {

    private MemoryTemplates() {
    }

    /** Agent 长期设定 */
    public static final String AGENT_MD = """
            # Agent

            ## Identity

            林行（Linxing）学习助手 Agent。定位为陪伴式学习伙伴，而非通用问答机器人。
            核心职责：帮用户制定学习计划、讲解知识、复盘进度、维护跨会话的长期记忆。

            ## Behavior

            - 优先参考【长期记忆】段中的 Agent.md / User.md / Learning/Current.md / Directory.md。
            - 需要查看某记忆文件全文时，使用 @相对路径 引用或调用 read_memory，不要凭空回忆。
            - 长期记忆的更新由异步 Memory Worker 统一决策，主对话中不主动改写 Memory 文件。
            - 涉及用户学习状态时，先看 Current.md 的 Topic/Plan/Recent Tasks/Next Goal 再作答。

            ## Principle

            - 长期记忆只保存当前最新状态，不记录闲聊、临时信息、单次任务细节。
            - 不新增或删除 Memory 文件的 Section（一二级标题固定），仅修改 Section 内容。
            - 记忆与事实冲突时以事实为准，并在本轮结束后由 Worker 修正过时记忆。

            ## Workflow

            1. 接收用户输入，参考常驻【长期记忆】段获取用户偏好与学习状态。
            2. 若需某文件全文，用 @ 引用或 read_memory 按需读取。
            3. 完成回复。
            4. 回答结束后异步 Memory Worker 判断是否更新长期记忆、是否触发学习阶段切换归档。

            ## Reply Style

            简洁、直接、面向行动。优先给结论与下一步，再按需展开原理。学习场景中适当给出可执行的练习或自检题。
            """;

    /** 用户角色与偏好 */
    public static final String USER_MD = """
            # User

            ## Profile

            <!-- 用户角色信息：身份/背景/当前阶段，由 Memory Worker 根据对话逐步补全 -->

            ## Preference

            回答偏好：默认中文，代码与术语保留英文；解释优先给结论与可执行步骤，再按需展开原理。

            ## Coding Style

            <!-- 编程偏好：语言/框架/风格，由 Memory Worker 根据对话逐步补全 -->

            ## Learning

            学习偏好：希望系统化、有计划地推进，重视阶段性复盘与总结。
            """;

    /** 当前学习状态 */
    public static final String CURRENT_MD = """
            # Learning Current

            ## Topic

            <!-- 当前学习主题：由 Memory Worker 在阶段切换时更新；未开始时留空 -->

            ## Plan

            <!-- 当前学习计划：待学与最近计划清单，由 Memory Worker 根据对话更新 -->

            ## Recent Tasks

            <!-- 最近任务：最近完成或正在进行的条目，由 Memory Worker 更新 -->

            ## Next Goal

            <!-- 下一阶段目标 -->
            """;

    /** 整个 Workspace 的导航文件 */
    public static final String DIRECTORY_MD = """
            # Memory Directory

            ## Agent

            Agent.md
            Agent 长期设定。

            ---

            ## User

            User.md
            用户角色与偏好。

            ---

            ## Learning

            Current.md
            当前学习计划与状态。

            ---

            ## History

            <!-- 已完成的学习阶段归档，由 Memory Worker 自动生成。下方历史文件元信息由系统运行时动态扫描注入，无需手动维护。 -->
            """;

    /** 模拟历史记忆 1：Agent Memory 学习阶段（用于联调 Directory 动态扫描注入） */
    public static final String HISTORY_AGENT_MEMORY_MD = """
            # History: Agent Memory

            ## 学习主题

            Agent Memory（Agent 上下文管理）

            ## 学习成果

            梳理了 Agent 上下文管理的三段式设计：Recovery → Projection(Rewrite+Snip) → Summary，
            以及 Redis Mirror 运行时镜像机制。理解了 Window Memory 与 Long-term Memory 的职责边界。

            ## 学习总结

            完成了短期上下文管理的落地，能够处理长对话下的上下文压缩与投影，为长期记忆打下基础。

            ## 完成时间

            2026-07-18T00:00:00+08:00
            """;

    /** 模拟历史记忆 2：Parser 学习阶段（用于联调 Directory 动态扫描注入） */
    public static final String HISTORY_PARSER_MD = """
            # History: Parser

            ## 学习主题

            Parser（文档解析与语义增强）

            ## 学习成果

            梳理了 Node-Based RAG 架构：Python 统一解析、Java 语义增强、Node 装箱、
            Display/Index 双轨存储。理解了 indexText 字段双轨修复的来龙去脉。

            ## 学习总结

            完成 RAG 解析链路梳理，明确了旧 strategy/render 已废弃、统一走 Node 装箱路径。

            ## 完成时间

            2026-07-10T00:00:00+08:00
            """;
}
