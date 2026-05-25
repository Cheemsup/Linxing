package org.linxing.linxing_agent.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Skill 文件解析器
 * 负责从磁盘递归扫描 skills 目录下所有 .md 文件，解析 YAML frontmatter 和 Markdown 正文。
 * 同时扫描技能文件所在目录下的 references/ 和 assets/ 子目录，收集资源文件列表。
 */
@Slf4j
@Component
public class SkillLoader {

    private static final String SKILL_FILE_SUFFIX = ".md";
    private static final String FRONTMATTER_DELIMITER = "---";

    private final Yaml yaml = new Yaml();

    /**
     * 递归扫描 skills 目录下所有 .md 文件，解析每个文件的 frontmatter
     *
     * @param skillsBasePath skills 根目录路径
     * @return 所有技能的 Phase 1 元数据列表
     */
    public List<SkillMetadata> scanAllMetadata(Path skillsBasePath) {
        if (!Files.isDirectory(skillsBasePath)) {
            log.warn("[SkillLoader] skills 目录不存在: {}", skillsBasePath);
            return Collections.emptyList();
        }

        List<SkillMetadata> result = new ArrayList<>();
        try {
            Files.walk(skillsBasePath)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(SKILL_FILE_SUFFIX))
                    .forEach(skillFile -> {
                        try {
                            SkillMetadata metadata = parseMetadata(skillFile);
                            if (metadata != null) {
                                result.add(metadata);
                                log.info("[SkillLoader] 加载技能元数据: [{}] from {}", metadata.getName(), skillFile.getFileName());
                            }
                        } catch (Exception e) {
                            log.error("[SkillLoader] 解析技能文件失败: {}", skillFile, e);
                        }
                    });
        } catch (IOException e) {
            log.error("[SkillLoader] 扫描 skills 目录失败: {}", skillsBasePath, e);
        }
        return result;
    }

    /**
     * 解析单个 skill 文件的 frontmatter，生成 Phase 1 元数据
     */
    private SkillMetadata parseMetadata(Path skillFile) throws IOException {
        String content = Files.readString(skillFile, StandardCharsets.UTF_8);
        Map<String, Object> frontmatter = parseFrontmatter(content);
        if (frontmatter == null) {
            log.debug("[SkillLoader] 文件缺少 frontmatter，跳过: {}", skillFile);
            return null;
        }

        String name = getString(frontmatter, "name");
        String description = getString(frontmatter, "description");
        List<String> toolNames = getStringList(frontmatter, "tool_names");

        if (name == null || name.isBlank()) {
            log.warn("[SkillLoader] 文件缺少 name 字段: {}", skillFile);
            return null;
        }
        if (description == null || description.isBlank()) {
            log.warn("[SkillLoader] 文件缺少 description 字段: {}", skillFile);
            return null;
        }

        Path skillDir = skillFile.getParent();
        return SkillMetadata.builder()
                .name(name.trim())
                .description(description.trim())
                .toolNames(toolNames != null ? toolNames : List.of())
                .skillFilePath(skillFile.toAbsolutePath().toString())
                .skillDirPath(skillDir.toAbsolutePath().toString())
                .build();
    }

    /**
     * 从磁盘读取 skill 文件正文（Phase 2），并收集资源文件列表
     *
     * @param metadata 技能元数据（含 skillFilePath 和 skillDirPath）
     * @return 技能指令
     */
    public SkillInstructions loadInstructions(SkillMetadata metadata) {
        Path skillFile = Path.of(metadata.getSkillFilePath());

        if (!Files.exists(skillFile)) {
            log.warn("[SkillLoader] 技能文件不存在: {}", skillFile);
            return SkillInstructions.builder()
                    .name(metadata.getName())
                    .instructions("")
                    .toolNames(metadata.getToolNames())
                    .resourcePaths(List.of())
                    .build();
        }

        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            String body = extractBody(content);
            Path skillDir = Path.of(metadata.getSkillDirPath());
            List<String> resourcePaths = scanResources(skillDir);

            return SkillInstructions.builder()
                    .name(metadata.getName())
                    .instructions(body)
                    .toolNames(metadata.getToolNames())
                    .resourcePaths(resourcePaths)
                    .build();
        } catch (IOException e) {
            log.error("[SkillLoader] 读取技能文件正文失败: {}", skillFile, e);
            return SkillInstructions.builder()
                    .name(metadata.getName())
                    .instructions("")
                    .toolNames(metadata.getToolNames())
                    .resourcePaths(List.of())
                    .build();
        }
    }

    /**
     * 加载技能的参考资源文件（Phase 3）
     *
     * @param metadata     技能元数据
     * @param relativePath 资源文件相对路径（如 references/question-types.md）
     * @return 资源文件内容，失败返回 null
     */
    public String loadResource(SkillMetadata metadata, String relativePath) {
        Path resourcePath = Path.of(metadata.getSkillDirPath()).resolve(relativePath);
        if (!Files.exists(resourcePath)) {
            log.warn("[SkillLoader] 资源文件不存在: {}", resourcePath);
            return null;
        }
        try {
            return Files.readString(resourcePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[SkillLoader] 读取资源文件失败: {}", resourcePath, e);
            return null;
        }
    }

    /**
     * 解析 YAML frontmatter（--- 之间的内容）
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseFrontmatter(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith(FRONTMATTER_DELIMITER)) {
            return null;
        }
        int end = trimmed.indexOf(FRONTMATTER_DELIMITER, FRONTMATTER_DELIMITER.length());
        if (end < 0) {
            return null;
        }
        String yamlContent = trimmed.substring(FRONTMATTER_DELIMITER.length(), end).trim();
        if (yamlContent.isBlank()) {
            return null;
        }
        return yaml.loadAs(yamlContent, Map.class);
    }

    /**
     * 提取 SKILL.md 的 Markdown 正文（frontmatter 之后的内容）
     */
    private String extractBody(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith(FRONTMATTER_DELIMITER)) {
            return trimmed;
        }
        int end = trimmed.indexOf(FRONTMATTER_DELIMITER, FRONTMATTER_DELIMITER.length());
        if (end < 0) {
            return trimmed;
        }
        String body = trimmed.substring(end + FRONTMATTER_DELIMITER.length()).trim();
        return body;
    }

    /**
     * 扫描技能目录下的 references/ 和 assets/ 子目录，收集资源文件相对路径
     */
    private List<String> scanResources(Path skillDir) {
        List<String> resources = new ArrayList<>();
        scanSubDir(skillDir, "references", resources);
        scanSubDir(skillDir, "assets", resources);
        return resources;
    }

    private void scanSubDir(Path skillDir, String subDirName, List<String> resources) {
        Path subDir = skillDir.resolve(subDirName);
        if (!Files.isDirectory(subDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(subDir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    resources.add(subDirName + "/" + file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("[SkillLoader] 扫描资源目录失败: {}", subDir, e);
        }
    }

    @SuppressWarnings("unchecked")
    private String getString(Map<String, Object> frontmatter, String key) {
        Object value = frontmatter.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> frontmatter, String key) {
        Object value = frontmatter.get(key);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
            return result;
        }
        return null;
    }
}
