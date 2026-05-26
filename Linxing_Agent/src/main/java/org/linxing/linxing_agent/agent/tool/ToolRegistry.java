package org.linxing.linxing_agent.agent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolRegistry implements ApplicationListener<ContextRefreshedEvent>, CatalogProvider {

    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

    private volatile boolean initialized = false;

    public ToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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
     * 按名获取单个工具的 ToolSpecification，用于渐进披露模式动态注入
     * @param name
     * @return
     */
    public ToolSpecification getToolSpecification(String name) {
        ToolSpec spec = tools.get(name);
        return spec != null ? spec.toLangChain4jSpec() : null;
    }

    /**
     * 批量获取指定工具的 ToolSpecification，用于渐进披露模式动态注入
     * @param names
     * @return
     */
    public List<ToolSpecification> getToolSpecifications(List<String> names) {
        return names.stream()
                .map(this::getToolSpecification)
                .filter(spec -> spec != null)
                .collect(Collectors.toList());
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
        if (specs.isEmpty()) {
            return "未找到指定的工具，请先调用 catalog 查看可用列表。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(toFunctionCallJson(specs.get(i)));
        }
        return sb.toString();
    }

    private String toFunctionCallJson(ToolSpec spec) {
        try {
            ToolSpecification langChain4jSpec = spec.toLangChain4jSpec();
            Map<String, Object> functionProps = new LinkedHashMap<>();
            functionProps.put("name", langChain4jSpec.name());
            functionProps.put("description", langChain4jSpec.description());
            functionProps.put("parameters", langChain4jSpec.parameters());

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "function");
            root.put("function", functionProps);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("[ToolRegistry] 序列化工具 Schema 失败: {}", spec.getName(), e);
            return "{}";
        }
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
