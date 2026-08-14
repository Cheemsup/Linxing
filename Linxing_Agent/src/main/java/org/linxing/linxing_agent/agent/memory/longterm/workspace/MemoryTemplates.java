package org.linxing.linxing_agent.agent.memory.longterm.workspace;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Long-term Memory 各 Markdown 文件的最小可用模板（V1）。
 * <p>半结构化 Markdown：一二级标题固定，仅允许修改 Section 内容，约束 LLM 修改行为使 Memory 长期稳定。
 * <p>模板源文件位于 classpath {@code memory-templates/}，启动时一次性加载到内存，运行时零 IO。
 */
@Slf4j
@Component
public class MemoryTemplates {

    /** classpath 模板根目录（递归匹配所有 .md） */
    private static final String TEMPLATE_ROOT = "classpath:memory-templates/**/*.md";

    /** 重建时仅覆盖的核心模板（强制覆盖）：Agent / User / Directory。
     *  <p>排除 {@code Learning/Current.md}（用户当前学习状态）与 {@code History/}（历史归档）。 */
    public static final String[] REBUILDABLE = {
            "Agent.md",
            "User.md",
            "Directory.md"
    };

    /** 懒生成时种入的全部模板（幂等不覆盖）：核心模板 + Current 主题结构样板。
     *  <p>主题文件本身由 Agent/用户按需创建（决策 4：多主题）；{@code Learning/Current/_template.md}
     *  作为新主题的结构样板种入磁盘，供 read_memory 读取与复制改名使用。
     *  各主题枚举处（CurrentTopicRegistry / LongMemoryInjector）按 {@code _} 前缀跳过它，不计入主题数。 */
    public static final String[] SEEDABLE = {
            "Agent.md",
            "User.md",
            "Directory.md",
            "Learning/Current/_template.md"
    };

    /** 相对路径 → 模板内容，启动时从 classpath 加载 */
    private Map<String, String> templates = Map.of();

    @PostConstruct
    public void load() {
        Map<String, String> loaded = new HashMap<>();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(TEMPLATE_ROOT);
        } catch (IOException e) {
            throw new IllegalStateException("加载 Memory 模板失败：" + TEMPLATE_ROOT, e);
        }
        for (Resource resource : resources) {
            String relativePath = relativePath(resource);
            try {
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                loaded.put(relativePath, content);
            } catch (IOException e) {
                throw new IllegalStateException("读取 Memory 模板失败：" + resource.getDescription(), e);
            }
        }
        this.templates = Map.copyOf(loaded);
        log.info("[MemoryTemplates] 已加载 {} 个模板：{}", templates.size(), templates.keySet());
    }

    /**
     * 由 classpath 资源 URI 推导相对路径，
     * 如 {@code .../memory-templates/Learning/Current.md} → {@code Learning/Current.md}。
     */
    private static String relativePath(Resource resource) {
        String uri;
        try {
            uri = resource.getURI().toString();
        } catch (IOException e) {
            throw new IllegalStateException("解析模板 URI 失败：" + resource.getDescription(), e);
        }
        int idx = uri.indexOf("memory-templates/");
        if (idx < 0) {
            throw new IllegalStateException("模板不在 memory-templates/ 下：" + uri);
        }
        return uri.substring(idx + "memory-templates/".length()).replace('\\', '/');
    }

    /**
     * 获取指定相对路径的模板内容。
     *
     * @param relativePath 相对路径，如 {@code Agent.md} / {@code Learning/Current.md}
     * @return 模板全文
     */
    public String get(String relativePath) {
        String content = templates.get(relativePath);
        if (content == null) {
            throw new IllegalStateException("Memory 模板不存在：" + relativePath + "，已加载：" + templates.keySet());
        }
        return content;
    }

    /**
     * 是否存在指定模板。
     */
    public boolean exists(String relativePath) {
        return templates.containsKey(relativePath);
    }
}
