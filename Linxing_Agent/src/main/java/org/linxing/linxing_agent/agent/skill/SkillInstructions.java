package org.linxing.linxing_agent.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Phase 2 数据：SKILL.md 正文（Markdown），按需从磁盘读取并缓存
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillInstructions {

    /**
     * 技能唯一标识
     */
    private String name;

    /**
     * SKILL.md 正文（Markdown 格式），包含工作流程、注意事项等
     */
    private String instructions;

    /**
     * 所需工具名称列表
     */
    private List<String> toolNames;

    /**
     * references/ 和 assets/ 下的文件相对路径列表，用于 Phase 3 按需加载
     */
    private List<String> resourcePaths;
}
