package org.linxing.linxing_agent.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 技能目录，用于渐进式披露 Phase 1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillCatalog {

    private List<SkillCatalogEntry> entries;

    /**
     * 将技能目录格式化为 LLM 可读的文本，供 SkillCatalogTool 返回
     * @return
     */
    public String toPromptText() {
        if (entries == null || entries.isEmpty()) {
            return "当前没有可用的技能。";
        }
        StringBuilder sb = new StringBuilder("可用技能目录：\n\n");
        for (int i = 0; i < entries.size(); i++) {
            SkillCatalogEntry entry = entries.get(i);
            sb.append(i + 1).append(". **").append(entry.getName()).append("**\n");
            sb.append("   - 描述：").append(entry.getDescription()).append("\n");
            sb.append("\n");
        }
        sb.append("如需使用某个技能，请调用 skill_resolve 获取其完整定义。");
        return sb.toString();
    }
}
