package org.linxing.linxing_agent.agent.catalog;

import java.util.List;

/**
 * 目录提供者接口，由 ToolRegistry 和 SkillRegistry 各自实现
 * 用于统一 CatalogTool / ResolveTool 的聚合调用
 */
public interface CatalogProvider {

    /**
     * 返回该提供者的目录条目列表
     */
    List<CatalogEntry> catalogEntries();

    /**
     * 解析指定名称的条目，返回 LLM 可读的详细文本
     * @param names
     * @return
     */
    String resolve(List<String> names);
}
