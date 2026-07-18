package org.linxing.linxing_agent.agent.memory.builder;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.memory.recovery.RecoveredHistory;

import java.util.List;
import java.util.Set;

/**
 * 上下文装配器（单一聚合交接口）——接管 AgentExecutor 原先散落的 prompt 装配职责。
 *
 * <p>设计依据见 {@code reference/TODOS/AgentMemory/contextDesign.md/0716_nowRefact.md} §6-1：
 * Builder 是"单一功能聚合交接口"，对下收集各类型数据并管理、对上输出 prompt 喂给 langchain4j。
 * 装配分三段——
 * <ul>
 *   <li><b>A 系统段</b>：静态模板 + 动态目录（tool/skill）+ 技能说明，每 session 稳定</li>
 *   <li><b>B 历史段</b>：该对话路径的提问/回复 + tool 调用记录，受 Projection 策略驱动（2-B 起）</li>
 *   <li><b>C 工具规格段</b>：ToolSpecifications 渐进披露（仍由 AgentExecutor 控制调用时机，Builder 仅代为拼装）</li>
 * </ul>
 *
 * <p><b>当前阶段（2-B）</b>：在 2-A 三方法（A 系统段 / C 工具规格段）基础上，
 * 新增 {@link #buildMessages(AgentContext)} 接管 B 历史段的读路径——
 * 把 {@link SystemMessage} 幂等置于首位，后接 {@code context.getMemory().messages()}。
 * SystemMessage 装配权由此从旧 WindowMemory（{@code setSystemMessage}）上移到 Builder，
 * AgentMemory 退化为极简累加器（不再持有 systemMessage 字段、不再有驱逐语义）。
 * Rule Set 驱动的 Projection 留待 2-D。
 */
public interface ContextBuilder {

    /**
     * 装配 A 系统段：静态模板 + 动态目录 + 技能说明 → SystemMessage。
     * @param progressiveMode true=渐进披露模式，false=全量注入模式
     */
    SystemMessage buildSystemMessage(boolean progressiveMode);

    /**
     * 装配 B 历史段：以 SystemMessage 幂等置于首位，后接 memory 当前累加的所有消息。
     * <p>
     * SystemMessage 不进 memory（memory 只承载运行时对话流），由本方法每轮装配时
     * 重新置于首位，保证 langchain4j 两硬约束之一（SystemMessage 幂等首位）。
     * @param context Agent 上下文，提供 memory 与 progressiveMode
     * @return 直接喂给 {@code ChatRequest.messages()} 的消息列表（不可变视图）
     */
    List<ChatMessage> buildMessages(AgentContext context);

    /**
     * 装配 B 历史段（2-D 起，消费 Rule Set 驱动 Projection）。
     * <p>
     * 在 {@link #buildMessages(AgentContext)} 基础上，按 {@code recovered.turnBoundaries}
     * 与当前会话的 Rule Set 对 history 段做投影：
     * <ul>
     *   <li><b>SkipTurnRule</b>：命中某 Turn 的 {@code turnStartMessageId} 则整 Turn 跳过
     *       （区间 [startIdx, endIdx) 内的消息全部不进 prompt，不切断 tool 配对）</li>
     *   <li><b>RewriteToolRule</b>：history 中的 {@code ToolExecutionResultMessage} 命中
     *       {@code toolCallId} 则 content 替换为占位符（保留 tool_call_id 不破坏配对）</li>
     * </ul>
     * 当前轮追加消息（用户问题 + 循环内 aiMessage/resultMsg）在 history 之后，不属于任何
     * TurnBoundary、尚未被 Snip 分析，原样保留。
     * <p>
     * Projection 完全运行时构建、请求结束即释放、不缓存、不持久化（0717 终稿第八节）。
     * @param context  Agent 上下文
     * @param recovered Recovery 结果（含 history 的 turnBoundaries）；无 Recovery 时传 null
     * @return 经投影后的消息列表，SystemMessage 幂等首位
     */
    List<ChatMessage> buildMessages(AgentContext context, RecoveredHistory recovered);

    /**
     * 构建第一轮的 toolSpecifications（C 工具规格段）。
     * 全量模式返回所有已注册工具；渐进披露模式仅返回 resolve 元工具。
     */
    List<ToolSpecification> buildInitialToolSpecs(boolean progressiveMode);

    /**
     * 构建每轮对话的 toolSpecifications。
     * 全量模式始终返回初始规格；渐进披露模式在初始规格基础上追加已动态激活的工具。
     */
    List<ToolSpecification> buildRoundToolSpecs(List<ToolSpecification> initialSpecs,
                                                Set<String> activatedToolNames,
                                                boolean progressiveMode);
}
