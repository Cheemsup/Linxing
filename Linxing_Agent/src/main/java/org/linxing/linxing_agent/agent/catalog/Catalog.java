package org.linxing.linxing_agent.agent.catalog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Catalog {

    public static final Set<String> META_TOOLS = Set.of(
            "catalog", "resolve", "tool_catalog", "tool_resolve", "skill_catalog", "skill_resolve"
    );

    private List<CatalogEntry> entries;

    /**
     * 将目录格式化为 LLM 可读的文本，供 CatalogTool 返回
     */
    public String toPromptText() {
        if (entries == null || entries.isEmpty()) {
            return "当前没有可用的工具或技能。";
        }
        StringBuilder sb = new StringBuilder("可用能力目录：\n\n");
        for (int i = 0; i < entries.size(); i++) {
            CatalogEntry entry = entries.get(i);
            String typeLabel = "skill".equals(entry.getType()) ? "技能" : "工具";
            sb.append(i + 1).append(". **").append(entry.getName())
                    .append("** [").append(typeLabel).append("]\n");
            sb.append("   - 简介：").append(entry.getBrief()).append("\n");
            if (entry.getWhenToUse() != null && !entry.getWhenToUse().isBlank()) {
                sb.append("   - 适用场景：").append(entry.getWhenToUse()).append("\n");
            }
            if (entry.getPrerequisites() != null && !entry.getPrerequisites().isEmpty()) {
                sb.append("   - 前置条件：").append(String.join("、", entry.getPrerequisites())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("如需使用某个工具或技能，请调用 resolve 获取其完整定义。");
        return sb.toString();
    }
}
