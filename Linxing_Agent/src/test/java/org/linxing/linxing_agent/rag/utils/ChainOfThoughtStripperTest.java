package org.linxing.linxing_agent.rag.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ChainOfThoughtStripper} 清洗逻辑单元测试。
 * 覆盖尖括号 / 方括号 / 历史连字符变体 / 未闭合块 / 首尾有效内容保留 / 空与 null 入参。
 */
class ChainOfThoughtStripperTest {

    @Test
    void stripsBasicThinkTag() {
        assertEquals("有效描述", ChainOfThoughtStripper.strip("有效描述 <think>用户希望我生成描述</think>"));
    }

    @Test
    void stripsCaseInsensitiveTag() {
        assertEquals("A", ChainOfThoughtStripper.strip("A <THINK>reasoning</THINK>"));
        assertEquals("A", ChainOfThoughtStripper.strip("A <Thought>reasoning</Thought>"));
    }

    @Test
    void stripsAllTagVariants() {
        assertEquals("X", ChainOfThoughtStripper.strip("X <thinking>a</thinking>"));
        assertEquals("X", ChainOfThoughtStripper.strip("X <reasoning>a</reasoning>"));
        assertEquals("X", ChainOfThoughtStripper.strip("X <thought>a</thought>"));
    }

    @Test
    void stripsMultilineThinkBlock() {
        String input = "背景。\n<think>第一行推理\n第二行推理</think>\n最终描述";
        assertEquals("背景。\n\n最终描述", ChainOfThoughtStripper.strip(input));
    }

    @Test
    void stripsBracketMarkers() {
        assertEquals("ok", ChainOfThoughtStripper.strip("ok [think]hidden[/think]"));
        assertEquals("ok", ChainOfThoughtStripper.strip("ok [THINK]hidden[/THINK]"));
        assertEquals("ok", ChainOfThoughtStripper.strip("ok [thinking]hidden[/thinking]"));
    }

    @Test
    void stripsLegacyDashVariant() {
        // 旧 SemanticEnhancementServiceImpl 的正则形式，保留兼容
        assertEquals("结果", ChainOfThoughtStripper.strip("结果 -thinking推理内容-thinking"));
    }

    @Test
    void stripsUnclosedBlockToEnd() {
        assertEquals("前缀", ChainOfThoughtStripper.strip("前缀 <think>未闭合的推理内容"));
    }

    @Test
    void keepsContentAroundThinkBlock() {
        String input = "该图描述写入流程。 <think>The user wants me to generate a description. Let me analyze.</think> 该图展示七步写入流程。";
        assertEquals("该图描述写入流程。  该图展示七步写入流程。", ChainOfThoughtStripper.strip(input));
    }

    @Test
    void stripsMultipleBlocks() {
        String input = "<think>一</think>中间 <think>二</think> 尾部";
        assertEquals("中间  尾部", ChainOfThoughtStripper.strip(input));
    }

    @Test
    void leavesNormalTextUntouched() {
        assertEquals("这是普通文本，不包含思维链。", ChainOfThoughtStripper.strip("这是普通文本，不包含思维链。"));
        // 正常语境里的"思考"一词不应被误删
        assertEquals("ReAct 循环：思考一步、行动一步。", ChainOfThoughtStripper.strip("ReAct 循环：思考一步、行动一步。"));
    }

    @Test
    void handlesEmptyAndNull() {
        assertNull(ChainOfThoughtStripper.strip(null));
        assertEquals("", ChainOfThoughtStripper.strip(""));
    }

    @Test
    void returnsEmptyWhenEntireContentIsThink() {
        assertEquals("", ChainOfThoughtStripper.strip("<think>全是推理</think>"));
    }
}
