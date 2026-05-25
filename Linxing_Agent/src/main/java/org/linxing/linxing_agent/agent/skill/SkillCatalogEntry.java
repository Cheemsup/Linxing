package org.linxing.linxing_agent.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 技能目录条目，用于渐进式披露 Phase 1 的目录展示
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillCatalogEntry {

    /**
     * 技能唯一标识
     */
    private String name;

    /**
     * 技能描述（含 WHAT + WHEN）
     */
    private String description;
}
