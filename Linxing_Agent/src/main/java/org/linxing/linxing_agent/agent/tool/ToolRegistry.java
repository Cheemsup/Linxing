package org.linxing.linxing_agent.agent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolRegistry implements ApplicationListener<ContextRefreshedEvent>, CatalogProvider {

    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();

    private volatile boolean initialized = false;

    /**
     * 容器启动完成后自动发现所有 Tool 实现并注册
     * @param event
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (initialized) {
            return;//防止父子上下文重复触发
        }
        Map<String, Tool> toolBeans = event.getApplicationContext().getBeansOfType(Tool.class);
        toolBeans.forEach((beanName, tool) -> {
            ToolSpec existing = tools.get(tool.name());
            if (existing != null) {
                throw new IllegalStateException(
                        String.format("[ToolRegistry] 工具名称冲突: [%s]，已有注册，不可重复", tool.name()));
            }
            register(tool);//将工具注册到map中维护
        });
        initialized = true;
        log.info("[ToolRegistry] 已注册 {} 个工具: {}", tools.size(),
                String.join(", ", tools.keySet()));
    }

    /**
     * 注册单个工具，由 Tool 自身的 spec() 提供 JSON Schema
     * @param tool
     */
    public void register(Tool tool) {
        ToolSpec spec = ToolSpec.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(tool.spec())//工具自描述参数 Schema
                .executor(tool)
                .build();
        tools.put(tool.name(), spec);
    }

    /**
     * 获取所有已注册工具的 LangChain4j 规格，用于注入 LLM 请求
     * @return
     */
    public List<ToolSpecification> getToolSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (ToolSpec tool : tools.values()) {
            specs.add(tool.toLangChain4jSpec());
        }
        return specs;
    }

    /**
     * 根据名称获取工具规格
     * @param name
     * @return
     */
    public ToolSpec getTool(String name) {
        return tools.get(name);
    }

    /**
     * 批量获取指定工具的完整 ToolSpec
     * @param names
     * @return
     */
    public List<ToolSpec> resolveSpecs(List<String> names) {
        List<ToolSpec> result = new ArrayList<>();
        for (String name : names) {
            ToolSpec spec = tools.get(name);
            if (spec != null) {
                result.add(spec);
            } else {
                log.warn("[ToolRegistry] resolve 时未找到工具: {}", name);
            }
        }
        return result;
    }

    @Override
    public List<CatalogEntry> catalogEntries() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (ToolSpec spec : tools.values()) {
            Tool executor = spec.getExecutor();
            entries.add(CatalogEntry.tool(
                    spec.getName(),
                    executor.brief(),
                    executor.whenToUse(),
                    executor.prerequisites()
            ));
        }
        return entries;
    }

    @Override
    public String resolve(List<String> names) {
        List<ToolSpec> specs = resolveSpecs(names);
        String resultText = specs.stream()
                .map(spec -> "## 工具: " + spec.getName() + "\n\n"
                        + spec.getDescription() + "\n\n"
                        + "参数:\n```json\n" + spec.getParameters() + "\n```")
                .collect(Collectors.joining("\n\n---\n\n"));
        return resultText.isBlank()
                ? "未找到指定的工具，请先调用 catalog 查看可用列表。"
                : resultText;
    }

    /**
     * 获取全量工具规格（含所有工具的完整 Schema）
     * @return
     */
    public List<ToolSpec> allSpecs() {
        return new ArrayList<>(tools.values());
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 已注册工具数量
     * @return
     */
    public int size() {
        return tools.size();
    }

    Map<String, ToolSpec> getTools() {
        return tools;
    }
}
