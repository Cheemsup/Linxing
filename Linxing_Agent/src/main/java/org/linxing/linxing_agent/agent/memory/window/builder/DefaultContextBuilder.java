package org.linxing.linxing_agent.agent.memory.window.builder;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.agent.catalog.Catalog;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.linxing.linxing_agent.agent.core.AgentPrompts;
import org.linxing.linxing_agent.agent.core.AgentStepEvent;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.memory.longterm.injector.LongMemoryInjector;
import org.linxing.linxing_agent.agent.memory.window.projection.ProjectionLoopExecutor;
import org.linxing.linxing_agent.agent.memory.window.projection.ProjectionPolicy;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.recovery.TurnBoundary;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSet;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RewriteToolRule;
import org.linxing.linxing_agent.agent.skill.SkillMetadata;
import org.linxing.linxing_agent.agent.skill.SkillRegistry;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link ContextBuilder} 默认实现：装配 Agent 每轮发送给 LLM 的三类上下文素材。
 *
 * <p>三段职责：
 * <ul>
 *   <li>A 系统段 — {@link #buildSystemMessage} / {@link #buildSystemPrompt}：依据 progressiveMode 动态拼装系统提示词</li>
 *   <li>B 历史段 — {@link #build}：SystemMessage 幂等首位 + history 投影（recovered 无状态入参）+ 当前用户问，
 *       对话开始一次性装配 + token 估算 + 策略判定，写入 AgentContext.memory，循环内 Executor 只读不再回调</li>
 *   <li>C 工具规格段 — {@link #buildInitialToolSpecs} / {@link #buildRoundToolSpecs}：按渐进披露策略注入 ToolSpecification</li>
 * </ul>
 *
 */
@Slf4j
@Component
public class DefaultContextBuilder implements ContextBuilder {

    private static final String SYSTEM_PROMPT_TEMPLATE_FULL = AgentPrompts.SYSTEM_PROMPT_TEMPLATE_FULL;
    private static final String SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE = AgentPrompts.SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE;

    /**
     * 渐进式披露阈值：工具数 + 技能数超过此值即进入 progressiveMode（仅注入 resolve 工具，其余按需披露）。
     * <p>与 AgentExecutor 同源配置，保证本类内部判定的 progressiveMode 与外部执行路径一致。
     */
    @Value("${agent.disclosure.threshold:5}")
    private int disclosureThreshold;

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;
    private final List<CatalogProvider> catalogProviders;
    private final RuleSetStore ruleSetStore;
    private final LongMemoryInjector longMemoryInjector;
    private final org.linxing.linxing_agent.agent.memory.TokenEstimator tokenEstimator;
    private final org.linxing.linxing_agent.agent.memory.window.projection.ProjectionThresholds projectionThresholds;
    /**
     * 同步/异步 Projection 触发器（0724 改造内化）：build() 内部据 policy 决定同步重建 RuleSet
     * 与异步小循环触发，外部不再参与触发决策。仅判定/委托，不持有 DB 副作用。
     */
    private final ProjectionLoopExecutor projectionLoopExecutor;

    /**
     * per-session 已动态激活的工具名集合。
     * <p>Builder 为单例 {@code @Component}，激活态按 sessionId 隔离，与 {@link RuleSetStore} 同构。
     * <p><b>双生命周期（0723 改造）</b>：激活集<b>跨同 session 多次 chat 复用</b>（累积 resolve 激活成果，
     * 避免同 session 内重复 resolve）；不再由 {@link #clearSession(int)} 在循环结束时清（那是旧 bug），
     * 改由 Caffeine TTL 兜底回收。recovered 已改为 {@link #build} 入参，无 per-session 容器。
     * <p>TTL 复用 {@code RagProperties.cache.mirrorTtl}（与 Redis 镜像同生命周期——镜像失效重建时
     * 激活集也同步过期，避免跨 chat 复用陈旧激活态）；{@code maximumSize} 防切走不删的 session 无限累积。
     */
    private final Cache<Integer, Set<String>> activatedToolNamesBySession;

    public DefaultContextBuilder(ToolRegistry toolRegistry,
                                 SkillRegistry skillRegistry,
                                 ObjectMapper objectMapper,
                                 List<CatalogProvider> catalogProviders,
                                 RuleSetStore ruleSetStore,
                                 LongMemoryInjector longMemoryInjector,
                                 org.linxing.linxing_agent.agent.memory.TokenEstimator tokenEstimator,
                                 org.linxing.linxing_agent.agent.memory.window.projection.ProjectionThresholds projectionThresholds,
                                 ProjectionLoopExecutor projectionLoopExecutor,
                                 RagProperties ragProperties) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
        this.catalogProviders = catalogProviders;
        this.ruleSetStore = ruleSetStore;
        this.longMemoryInjector = longMemoryInjector;
        this.tokenEstimator = tokenEstimator;
        this.projectionThresholds = projectionThresholds;
        this.projectionLoopExecutor = projectionLoopExecutor;
        int ttlSeconds = ragProperties.getCache().getMirrorTtl();
        this.activatedToolNamesBySession = Caffeine.newBuilder()
                .expireAfterAccess(ttlSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .maximumSize(500)
                .build();
    }

    /**
     * 渐进式披露判定：工具数 + 技能数超过阈值即进入 progressiveMode（仅注入 resolve，其余按需披露）。
     * <p>决策完全内化于 Builder，AgentExecutor 不再参与判定，消除原"双源重算"隐患。
     */
    private boolean isProgressiveMode() {
        int totalCount = toolRegistry.size() + skillRegistry.size();
        return totalCount > disclosureThreshold;
    }

    /**
     * 一次性装配 + token 估算 + 策略判定 + 同步/异步 Projection 触发（{@link ContextBuilder#build} 实现）。
     *
     * <p><b>0724 改造：token 口径统一 + 触发判定内化</b>。原 ChatServiceImpl 用
     * {@code tokenEstimator.estimate(recovered.getMessages())} 粗估 rawPolicy（漏算 toolSpecs），
     * 现改为全程基于 build 后的 {@code totalTokens}（含 messages + toolSpecs）判 policy，口径唯一。
     *
     * <p><b>循环依赖破局（两次 assemble）</b>：同步重建需要先有 policy 才能判触发，但装配投影又要先有 RuleSet。
     * 破法——
     * <ol>
     *   <li>第一次 assemble + 估算 → asm0（RuleSet 可能空 → 投影退化为零投影，但 token 估算的是
     *       <b>完整装配内容</b>，即"不投影会多大"，这正是触发判定的正确语义）</li>
     *   <li>若 asm0.policy ∈ {REWRITE_TOOL, SNIP_LOWVALUE} 且 RuleSet MISS：
     *       {@code projectionLoopExecutor.executeSync} 同步重建 RuleSet，再用新 RuleSet
     *       <b>第二次</b> assemble + 估算 → asm1（投影生效，messages 变少，token 重算）</li>
     *   <li>否则 asm1 = asm0</li>
     * </ol>
     *
     * <p><b>异步触发内化</b>：asm1.policy ∈ {REWRITE,SNIP} 且本轮未同步重建过且 {@code tryStart} CAS 成功时，
     * 立即 {@code executeAsync} 提交（下一轮 build 读到新 RuleSet）。SUMMARY 区间不触发异步（由外部落盘处理）。
     *
     * <p><b>SUMMARY 协议</b>：返回 {@code policy == SUMMARY} 时由调用方（ChatServiceImpl）落盘
     * 并构造精简 recovered 二次调本方法，Builder 全程不调 SummaryService，守住纯装配 + 触发判定边界。
     *
     * @param sessionId    定位 tool schema 激活集（跨 chat 复用）+ RuleSet
     * @param recovered    原始历史（无状态入参）；null 时退化为零投影
     * @param userId       拼 Long Memory 常驻段；null 时跳过
     * @param currentQuery 当前用户问；null/blank 时不追加
     */
    @Override
    public ContextAssembly build(int sessionId, RecoveredHistory recovered, Integer userId, String currentQuery) {
        List<ToolSpecification> toolSpecs = buildRoundToolSpecs(sessionId);

        // 第一次装配 + 估算：RuleSet 可能空 → 投影退化零投影，但 token 估算含完整装配口径（messages 全量 + toolSpecs）
        List<ChatMessage> messages0 = assembleMessages(sessionId, recovered, userId, currentQuery);
        long totalTokens0 = tokenEstimator.estimateContext(messages0, toolSpecs);
        ProjectionPolicy policy0 = projectionThresholds.policyFor(totalTokens0);

        boolean hasTurns = recovered != null && recovered.getTurnBoundaries() != null
                && !recovered.getTurnBoundaries().isEmpty();
        // 同步重建：RuleSet MISS + 进入投影区间 + 有 Turn 结构才跑（无 Turn 投影无意义）
        boolean justSyncRebuilt = false;
        if (hasTurns
                && projectionLoopExecutor.shouldTrigger(policy0)
                && !ruleSetStore.hasEntry(sessionId)) {
            log.info("[Builder-Sync] sessionId={} MISS + policy0={}, 同步重建 RuleSet", sessionId, policy0);
            justSyncRebuilt = projectionLoopExecutor.executeSync(
                    sessionId, recovered, currentQuery, policy0);
            if (!justSyncRebuilt) {
                log.info("[Builder-Sync] sessionId={} tryStart 失败（已有循环在跑），用旧 RuleSet 装配", sessionId);
            }
        }

        // 第二次装配（若同步重建过）：新 RuleSet 已生效，投影生效，messages 变少 → 重算 token + policy
        List<ChatMessage> messages = messages0;
        long totalTokens = totalTokens0;
        ProjectionPolicy policy = policy0;
        if (justSyncRebuilt) {
            messages = assembleMessages(sessionId, recovered, userId, currentQuery);
            totalTokens = tokenEstimator.estimateContext(messages, toolSpecs);
            policy = projectionThresholds.policyFor(totalTokens);
        }

        // 异步 Projection 触发：本轮未同步重建过 + 仍在投影区间 + CAS 成功才提交
        if (!justSyncRebuilt
                && policy != ProjectionPolicy.SUMMARY
                && projectionLoopExecutor.shouldTrigger(policy)
                && projectionLoopExecutor.tryStart(sessionId)) {
            log.info("[Builder-Async] sessionId={} triggered, policy={}", sessionId, policy);
            projectionLoopExecutor.executeAsync(sessionId, recovered, currentQuery, policy);
        }

        log.info("[Builder] sessionId={}, totalTokens={}, maxContext={}, policy={}, syncRebuilt={}, toolSpecs={}",
                sessionId, totalTokens, projectionThresholds.getMaxContextTokens(),
                policy, justSyncRebuilt, toolSpecs.size());

        return ContextAssembly.builder()
                .messages(messages)
                .toolSpecs(toolSpecs)
                .totalTokens(totalTokens)
                .policy(policy)
                .build();
    }

    /**
     * 历史段读路径（Rule Set 投影版）：消费入参 {@link RecoveredHistory} 与 {@link RuleSet}，
     * 对 history 段应用 SkipTurnRule（整 Turn 跳过）与 RewriteToolRule（tool 结果占位），末尾追加当前用户问。
     *
     * <p>装配顺序：SystemMessage 首位 → history 段投影 → 当前用户问。
     *
     * <p><b>history 段来源</b>：直接取 {@code recovered.getMessages()}——其下标与
     * {@code recovered.turnBoundaries} 一一对齐，是投影能正确分 Turn 的前提。
     * 装配结果一次性写入运行容器，Executor 循环内 add 的 aiMessage/resultMsg 自然追加在末尾。
     *
     * <p><b>两道兜底</b>：无 Recovery/无 turnBoundaries 时退化为零投影（SystemMessage + 当前用户问）；
     * recovered.messages 为空时退化为 SystemMessage + 当前用户问。
     */
    private List<ChatMessage> assembleMessages(int sessionId, RecoveredHistory recovered,
                                               Integer userId, String currentQuery) {
        SystemMessage systemMessage = buildSystemMessage(userId);

        // 无 Recovery 或无 Turn 结构 → 零投影：SystemMessage + 当前用户问
        if (recovered == null || recovered.getTurnBoundaries() == null
                || recovered.getTurnBoundaries().isEmpty()) {
            List<ChatMessage> result = new ArrayList<>(2);
            result.add(systemMessage);
            appendCurrentQuery(result, currentQuery);
            return result;
        }

        List<ChatMessage> historyMessages = recovered.getMessages();
        // 防御：history 为空（null 或空列表）→ 退化为 SystemMessage + 当前用户问
        if (historyMessages == null || historyMessages.isEmpty()) {
            List<ChatMessage> result = new ArrayList<>(2);
            result.add(systemMessage);
            appendCurrentQuery(result, currentQuery);
            return result;
        }
        int historySize = historyMessages.size();

        RuleSet ruleSet = ruleSetStore.get(sessionId);//获取rewrite、snip阶段生成的rule

        List<ChatMessage> result = new ArrayList<>(historySize + 2);
        result.add(systemMessage);

        // history 段投影：按 TurnBoundary 逐段处理
        Set<Integer> skippedTurnStartIds = ruleSet.skippedTurnStartIds();
        for (TurnBoundary tb : recovered.getTurnBoundaries()) {
            if (skippedTurnStartIds.contains(tb.getTurnStartMessageId())) {
                continue; // SkipTurnRule 命中：整 Turn 跳过
            }
            for (int i = tb.getStartIdx(); i < tb.getEndIdx() && i < historySize; i++) {
                result.add(projectToolResult(historyMessages.get(i), ruleSet));//过了snip的第一关，现在过rewrite第二关
            }
        }
        //注：@ 引用符现阶段仅作为给大模型的提示，不在此处自动读取文件并注入内容——由大模型自行调用工具完成文件读取
        appendCurrentQuery(result, currentQuery);
        return result;
    }

    /**
     * 追加当前用户问：置于 history 之后，不参与历史投影（尚未被 Snip 分析），原样保留。
     */
    private void appendCurrentQuery(List<ChatMessage> result, String currentQuery) {
        if (currentQuery != null && !currentQuery.isBlank()) {
            result.add(dev.langchain4j.data.message.UserMessage.from(currentQuery));
        }
    }


    /**
     * SystemMessage 装配：由 {@link #assembleMessages} 在装配历史段时首位调用。
     * progressiveMode 由 {@link #isProgressiveMode()} 内部判定，调用方无需传入。
     */
    private SystemMessage buildSystemMessage(Integer userId) {
        return SystemMessage.from(buildSystemPrompt(userId));
    }

    /**
     * 对单条消息应用 RewriteToolRule：若为 ToolExecutionResultMessage 且命中 rule，
     * 用占位符替换其 content
     * <p>占位符版保留原 tool_call_id 重建 ToolExecutionResultMessage，避免破坏与对应 ToolExecutionRequest 的配对。
     */
    private ChatMessage projectToolResult(ChatMessage msg, RuleSet ruleSet) {
        if (!(msg instanceof ToolExecutionResultMessage term)) {
            return msg;
        }
        String toolCallId = term.id() != null ? term.id() : term.toolName();
        RewriteToolRule rule = ruleSet.rewriteRuleFor(toolCallId);
        if (rule == null) {
            return msg;
        }
        // 丢 content、留简要提示，便于 LLM 知道此处曾有工具调用
        String placeholder = "[此工具结果已被 Projection 精简：toolCallId=" + toolCallId
                + (rule.getReason() != null && !rule.getReason().isBlank()
                ? ", reason=" + rule.getReason() : "")
                + "]";
        // ToolExecutionResultMessage 需配对的 ToolExecutionRequest；用原 msg 的 name/id 重建
        return ToolExecutionResultMessage.from(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id(term.id())
                        .name(term.toolName())
                        .build(),
                placeholder);
    }

    /**
     * 动态构建系统提示词：注入【长期记忆】常驻段 + 【可用能力】目录 + 技能说明（按 progressiveMode）。
     * <p>progressiveMode 由 {@link #isProgressiveMode()} 内部判定，调用方无需传入。
     * <p>Long Memory 段在【可用能力】之前，由 {@link LongMemoryInjector} 产出（Directory 全文 + Agent/User/Current 头部摘要）。
     * userId 为空（如测试 / 无上下文场景）时跳过 Long Memory 段。
     */
    private String buildSystemPrompt(Integer userId) {
        boolean progressiveMode = isProgressiveMode();
        List<CatalogEntry> allEntries = new ArrayList<>();
        for (CatalogProvider provider : catalogProviders) {
            allEntries.addAll(provider.catalogEntries());
        }

        // 过滤掉元工具（META_TOOLS），这些不作为目录内容向 LLM 披露
        List<CatalogEntry> filtered = allEntries.stream()
                .filter(e -> !Catalog.META_TOOLS.contains(e.getName()))
                .collect(Collectors.toList());

        StringBuilder dynamicSection = new StringBuilder();

        // 【长期记忆】段：在【可用能力】之前
        String residentSection = longMemoryInjector.buildResidentSection(userId);//读取注入长期记忆的md文档内容
        if (!residentSection.isBlank()) {
            dynamicSection.append(residentSection).append("\n\n");
        }

        if (!filtered.isEmpty()) {
            Catalog catalog = new Catalog(filtered);//将工具目录内容写到system prompt
            dynamicSection.append("【可用能力】\n").append(catalog.toPromptText()).append("\n\n");
        }

        if (!progressiveMode) {
            // 非渐进模式：完整技能说明直接铺进 system prompt
            List<String> allSkillNames = skillRegistry.getAllNames();
            if (!allSkillNames.isEmpty()) {
                String resolved = skillRegistry.resolve(allSkillNames);
                if (resolved != null && !resolved.isBlank() && !resolved.startsWith("未找到")) {
                    dynamicSection.append("【可用技能完整说明】\n").append(resolved).append("\n\n");
                }
            }
            dynamicSection.append("所有工具和技能的完整定义已在上方提供，请直接使用。");
        } else {
            // 渐进模式：能力数过多，引导 LLM 先看目录、按需调 resolve 取定义
            dynamicSection.append("由于可用工具和技能较多，请先查看上方目录了解可用能力。"
                    + "如需使用某个工具或技能，请调用 resolve 获取其完整定义。");
        }

        String template = progressiveMode ? SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE : SYSTEM_PROMPT_TEMPLATE_FULL;

        return template.replace(AgentPrompts.DYNAMIC_SECTION_PLACEHOLDER, dynamicSection);
    }

    @Override
    public List<ToolSpecification> buildInitialToolSpecs() {
        if (!isProgressiveMode()) {
            return toolRegistry.getToolSpecifications(); // 非渐进：全量注入
        }
        // 渐进：初始只注入 resolve（"工具之工具"，可用于按需获取其他工具的完整定义）
        List<ToolSpecification> specs = new ArrayList<>();
        ToolSpecification resolveSpec = toolRegistry.getToolSpecification("resolve");
        if (resolveSpec != null) {
            specs.add(resolveSpec);
        }
        return specs;
    }

    /**
     * 每轮工具规格装配：渐进模式下把当前轮已激活的工具定义追加到 resolve 初始规格上。
     * <p>非渐进模式或无激活工具时直接返回全量/初始规格，避免无谓复制。
     */
    @Override
    public List<ToolSpecification> buildRoundToolSpecs(int sessionId) {
        List<ToolSpecification> initialSpecs = buildInitialToolSpecs();
        Set<String> activated = activatedToolNamesBySession.getIfPresent(sessionId);
        if (!isProgressiveMode() || activated == null || activated.isEmpty()) {
            return initialSpecs;
        }
        List<ToolSpecification> roundSpecs = new ArrayList<>(initialSpecs);
        roundSpecs.addAll(toolRegistry.getToolSpecifications(new ArrayList<>(activated)));
        return roundSpecs;
    }

    /**
     * 工具执行结果回调：渐进披露模式下，resolve 成功时解析被请求的工具/技能名并激活。
     * <p>激活策略——
     * <ul>
     *   <li>被解析的工具：若已注册则加入本 session 激活集</li>
     *   <li>被解析的技能：将其关联的工具一并激活（技能本身无 ToolSpecification，靠关联工具落地）</li>
     * </ul>
     * 非渐进模式或非 resolve / 失败结果均 no-op，但统一回调保持接口对称。
     * <p>0724 改造：激活技能时通过 recorder 推送 skill_activated 事件（携带 displayName + tool_names），
     * 让前端感知"技能 X 已激活、关联工具已注入"——原激活逻辑静默，用户无从知晓。
     */
    @Override
    public void onToolExecuted(int sessionId, String toolName, ToolCallResult result, String arguments, StepRecorder recorder) {
        if (!isProgressiveMode()) {
            return;// 非渐进模式不激活
        }
        if (!"resolve".equals(toolName) || result == null || !result.isSuccess()) {
            return;
        }
        List<String> resolvedNames = parseResolvedNames(arguments);
        if (resolvedNames.isEmpty()) {
            return;
        }
        Set<String> activated = activatedToolNamesBySession.asMap()
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        for (String name : resolvedNames) {
            // 被解析的是技能：连带激活其关联工具，并推送 skill_activated 事件
            SkillMetadata skillMeta = skillRegistry.getMetadata(name);
            if (skillMeta != null && skillMeta.getToolNames() != null) {
                List<String> activatedToolNames = new ArrayList<>();
                for (String relatedToolName : skillMeta.getToolNames()) {
                    if (toolRegistry.getTool(relatedToolName) != null) {
                        activated.add(relatedToolName);
                        activatedToolNames.add(relatedToolName);
                    }
                }
                // 推送 skill_activated：携带 displayName + 已激活的关联工具名列表
                if (recorder != null) {
                    String displayName = skillMeta.getDisplayName() != null
                            && !skillMeta.getDisplayName().isBlank()
                            ? skillMeta.getDisplayName() : name;
                    Map<String, Object> stepData = new HashMap<>();
                    stepData.put(AgentStepTypes.KEY_SKILL_NAME, displayName);
                    stepData.put(AgentStepTypes.KEY_TOOL_NAMES, activatedToolNames);
                    recorder.record(AgentStepEvent.builder()
                            .eventType(AgentStepTypes.SKILL_ACTIVATED)
                            .stepNumber(0)
                            .phase(AgentStepTypes.PHASE_THINKING)
                            .label("已激活技能：" + displayName)
                            .stepData(stepData)
                            .build());
                }
            } else {
                // 被解析的是工具：已注册则激活
                if (toolRegistry.getTool(name) != null) {
                    activated.add(name);
                }
            }
        }
    }

    @Override
    public void clearSession(int sessionId) {
        // 注（0723 改造）：recovered 已改为 build 入参，无 per-session 容器，无需清理；
        // 激活集已改 Caffeine TTL 回收，本方法仅作"会话真正结束时手动清"入口（AgentExecutor finally 不再调，第 6 步）
        activatedToolNamesBySession.invalidate(sessionId);
    }

    /**
     * 从 resolve 工具的 arguments JSON 中提取被解析的名称列表。
     * 用于渐进披露模式下解析 LLM 通过 resolve 请求了哪些工具/技能。
     */
    private List<String> parseResolvedNames(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(arguments);
            JsonNode namesNode = node.get("names");
            if (namesNode != null && namesNode.isArray()) {
                List<String> names = new ArrayList<>();
                namesNode.forEach(n -> names.add(n.asText()));
                return names;
            }
        } catch (Exception e) {
            log.warn("[DefaultContextBuilder] 解析 resolve 参数失败: {}", arguments);
        }
        return List.of();
    }
}
