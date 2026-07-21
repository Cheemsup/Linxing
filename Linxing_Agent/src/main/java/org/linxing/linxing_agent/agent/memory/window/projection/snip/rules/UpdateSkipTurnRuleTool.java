package org.linxing.linxing_agent.agent.memory.window.projection.snip.rules;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.linxing.linxing_agent.agent.memory.window.projection.snip.SkipTurnReActContext;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import tools.jackson.databind.JsonNode;

import java.util.List;

public final class UpdateSkipTurnRuleTool {

    public static final String NAME = "update_skip_turn_rule";

    public static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("增/删/改 SkipTurnRule：标记哪些对话 Turn（按 turnId）可不参与后续 Prompt 构建。"
                    + "action=add 需 turnId+reason；action=remove 需 ruleId；"
                    + "action=replace 需 ruleId+turnId+reason。仅增量改动你判断有变化的条目。")
            .parameters(JsonObjectSchema.builder()
                    .addEnumProperty("action", List.of("add", "remove", "replace"))
                    .addIntegerProperty("turnId", "Turn 起始消息的 DB id（add/replace 必填）")
                    .addStringProperty("ruleId", "待 remove/replace 的 rule id")
                    .addStringProperty("reason", "该 Turn 被判定为低价值的理由（add/replace 必填）")
                    .required("action")
                    .build())
            .build();

    private UpdateSkipTurnRuleTool() {
    }

    /**
     * 解析 arguments 并按 action 分派到 batch 的 add/remove/replace
     * @param ctx
     * @param arguments
     * @return
     */
    public static String execute(SkipTurnReActContext ctx, String arguments) {
        String action;
        Integer turnId;
        String ruleId;
        String reason;
        try {
            JsonNode node = ctx.getObjectMapper().readTree(arguments);//解析 LLM 传入的 JSON 参数
            action = getText(node, "action", null);
            turnId = (node.has("turnId") && !node.get("turnId").isNull())
                    ? node.get("turnId").asInt() : null;
            ruleId = getText(node, "ruleId", null);
            reason = getText(node, "reason", null);
        } catch (Exception e) {
            return "error: 参数解析失败 " + e.getMessage();
        }

        if (action == null) {
            return "error: action 必填";
        }
        RuleSetStore.RuleUpdateBatch batch = ctx.getBatch();//取出小循环累计的 batch，统一在循环结束时落库
        switch (action) {//更改更新rule的batch
            case "add" -> {
                if (turnId == null || reason == null || reason.isBlank()) {
                    return "error: add 需 turnId 与 reason";
                }
                batch.addSkipTurnRule(turnId, reason);
                return "ok: added skip rule for turnId=" + turnId;
            }
            case "remove" -> {
                if (ruleId == null || ruleId.isBlank()) {
                    return "error: remove 需 ruleId";
                }
                batch.remove(ruleId);
                return "ok: removed rule " + ruleId;
            }
            case "replace" -> {
                if (ruleId == null || ruleId.isBlank() || turnId == null
                        || reason == null || reason.isBlank()) {
                    return "error: replace 需 ruleId+turnId+reason";
                }
                batch.replaceSkipTurnRule(ruleId, turnId, reason);
                return "ok: replaced rule " + ruleId + " for turnId=" + turnId;
            }
            default -> {
                return "error: 未知 action " + action;
            }
        }
    }

    /**
     * 读取可选字符串字段，缺失或 null 返回 defaultValue
     * @param node
     * @param field
     * @param defaultValue
     * @return
     */
    private static String getText(JsonNode node, String field, String defaultValue) {
        if (node.has(field) && !node.get(field).isNull()) {
            String text = node.get(field).asText();
            return text != null ? text : defaultValue;
        }
        return defaultValue;
    }
}
