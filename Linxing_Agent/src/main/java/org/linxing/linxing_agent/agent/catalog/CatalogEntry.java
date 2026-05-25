package org.linxing.linxing_agent.agent.catalog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CatalogEntry {

    /**
     * 条目类型：tool 或 skill
     */
    private String type;

    private String name;
    private String brief;
    private String whenToUse;
    private List<String> prerequisites;

    /**
     * 创建工具类型的条目
     */
    public static CatalogEntry tool(String name, String brief, String whenToUse, List<String> prerequisites) {
        return new CatalogEntry("tool", name, brief, whenToUse, prerequisites);
    }

    /**
     * 创建技能类型的条目
     */
    public static CatalogEntry skill(String name, String description) {
        return new CatalogEntry("skill", name, description, null, null);
    }
}
