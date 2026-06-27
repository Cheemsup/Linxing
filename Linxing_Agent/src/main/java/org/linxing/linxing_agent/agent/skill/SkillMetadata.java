package org.linxing.linxing_agent.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Phase 1 数据：SKILL.md frontmatter 解析结果，启动时全量加载到内存
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMetadata {

    /**
     * 技能唯一标识，小写+连字符，与目录名一致
     */
    private String name;

    /**
     * 技能描述，包含"做什么(WHAT)"和"何时用(WHEN)"
     */
    private String description;

    /**
     * 前端展示名，用于向用户展示此技能的人类可读名称。
     * 未配置时回退到 {@link #name}。
     */
    private String displayName;

    /**
     * 所需工具名称列表
     */
    private List<String> toolNames;

    /**
     * 技能文件在磁盘上的绝对路径（如 .../skills/design.md）
     */
    private String skillFilePath;

    /**
     * 技能文件所在目录在磁盘上的绝对路径（用于定位 references/assets）
     */
    private String skillDirPath;
}
