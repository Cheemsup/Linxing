package org.linxing.linxing_agent.agent.skill;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能注册中心
 * 启动时扫描 skills/ 目录下所有 SKILL.md 文件的 frontmatter，全量加载到内存（Phase 1）。
 * Phase 2 的 SKILL.md 正文按需从磁盘读取，并通过 Caffeine LRU 缓存避免重复 I/O。
 * Phase 3 的资源文件仅在 SKILL.md 正文引用时才从磁盘读取，不预加载。
 */
@Slf4j
@Component
public class SkillRegistry implements ApplicationListener<ContextRefreshedEvent>, CatalogProvider {

    private final Map<String, SkillMetadata> metadataIndex = new LinkedHashMap<>();

    private final Cache<String, SkillInstructions> instructionsCache;

    private final SkillLoader skillLoader;
    private final Path skillsBasePath;
    private volatile boolean initialized = false;

    public SkillRegistry(SkillLoader skillLoader,
                         @Value("${agent.skills.path:}") String skillsBasePath) {
        this.skillLoader = skillLoader;
        // 未配置时默认使用 classpath 下的 agent/skill/skills/ 目录
        if (skillsBasePath == null || skillsBasePath.isBlank()) {
            this.skillsBasePath = Path.of("src/main/java/org/linxing/linxing_agent/agent/skill/skills");
            log.info("[SkillRegistry] agent.skills.path 未配置，使用默认路径: {}", this.skillsBasePath.toAbsolutePath());
        } else {
            this.skillsBasePath = Path.of(skillsBasePath);
        }
        this.instructionsCache = Caffeine.newBuilder()
                .maximumSize(50)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build();
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (initialized) {
            return;
        }
        scanAndLoadMetadata();
        initialized = true;
    }

    /**
     * 扫描 skills/ 目录，解析所有 SKILL.md 的 frontmatter 到内存
     */
    private void scanAndLoadMetadata() {
        List<SkillMetadata> metadataList = skillLoader.scanAllMetadata(skillsBasePath);

        for (SkillMetadata metadata : metadataList) {
            SkillMetadata existing = metadataIndex.get(metadata.getName());
            if (existing != null) {
                throw new IllegalStateException(
                        String.format("[SkillRegistry] 技能名称冲突: [%s]，已有注册，不可重复", metadata.getName()));
            }
            metadataIndex.put(metadata.getName(), metadata);
        }

        log.info("[SkillRegistry] 已注册 {} 个技能: {}", metadataIndex.size(),
                String.join(", ", metadataIndex.keySet()));
    }

    @Override
    public List<CatalogEntry> catalogEntries() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (SkillMetadata metadata : metadataIndex.values()) {
            entries.add(CatalogEntry.skill(metadata.getName(), metadata.getDescription()));
        }
        return entries;
    }

    @Override
    public String resolve(List<String> names) {
        List<SkillInstructions> instructions = resolveInstructions(names);
        if (instructions.isEmpty()) {
            return "未找到指定的技能，请先调用 catalog 查看可用列表。";
        }
        return instructions.stream()
                .map(instr -> "## 技能: " + instr.getName() + "\n\n"
                        + instr.getInstructions() + "\n\n"
                        + (instr.getToolNames() != null && !instr.getToolNames().isEmpty()
                        ? "关联工具: " + String.join(", ", instr.getToolNames()) + "\n\n"
                        : "")
                        + (instr.getResourcePaths() != null && !instr.getResourcePaths().isEmpty()
                        ? "可用参考资源: " + String.join(", ", instr.getResourcePaths()) + "\n\n"
                        : ""))
                .collect(Collectors.joining("---\n\n"));
    }

    /**
     * 按需获取技能完整指令（磁盘读取 + 缓存）
     */
    public List<SkillInstructions> resolveInstructions(List<String> names) {
        List<SkillInstructions> result = new ArrayList<>();
        for (String name : names) {
            SkillInstructions instructions = instructionsCache.get(name, key -> {
                SkillMetadata metadata = metadataIndex.get(key);
                if (metadata == null) {
                    log.warn("[SkillRegistry] resolve 时未找到技能: {}", key);
                    return null;
                }
                return skillLoader.loadInstructions(metadata);
            });
            if (instructions != null) {
                result.add(instructions);
            }
        }
        return result;
    }

    /**
     * 按需获取技能的参考资源文件（磁盘读取，不缓存）
     */
    public String loadResource(String skillName, String resourcePath) {
        SkillMetadata metadata = metadataIndex.get(skillName);
        if (metadata == null) {
            log.warn("[SkillRegistry] loadResource 时未找到技能: {}", skillName);
            return null;
        }
        return skillLoader.loadResource(metadata, resourcePath);
    }

    /**
     * 根据名称获取技能元数据
     */
    public SkillMetadata getMetadata(String name) {
        return metadataIndex.get(name);
    }

    /**
     * 已注册技能数量
     */
    public int size() {
        return metadataIndex.size();
    }

    /**
     * 获取所有已注册技能的名称列表，用于全量注入模式
     */
    public List<String> getAllNames() {
        return new ArrayList<>(metadataIndex.keySet());
    }
}
