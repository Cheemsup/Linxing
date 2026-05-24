package org.linxing.linxing_agent.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Catalog {

    private List<CatalogEntry> entries;

    /**
     * 将目录格式化为 LLM 可读的文本，供 ToolCatalogTool 返回
     * @return
     */
    public String toPromptText() {
        if (entries == null || entries.isEmpty()) {
            return "当前没有可用的工具。";
        }
        StringBuilder sb = new StringBuilder("可用工具目录：\n\n");
        for (int i = 0; i < entries.size(); i++) {
            CatalogEntry entry = entries.get(i);
            sb.append(i + 1).append(". **").append(entry.getName()).append("**\n");
            sb.append("   - 简介：").append(entry.getBrief()).append("\n");
            if (entry.getWhenToUse() != null && !entry.getWhenToUse().isBlank()) {
                sb.append("   - 适用场景：").append(entry.getWhenToUse()).append("\n");
            }
            if (entry.getPrerequisites() != null && !entry.getPrerequisites().isEmpty()) {
                sb.append("   - 前置条件：").append(String.join("、", entry.getPrerequisites())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("如需使用某个工具，请调用 tool_resolve 工具获取其完整参数定义。");
        return sb.toString();
    }
}
