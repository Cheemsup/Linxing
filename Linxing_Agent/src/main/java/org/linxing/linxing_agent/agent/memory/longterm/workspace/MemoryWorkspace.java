package org.linxing.linxing_agent.agent.memory.longterm.workspace;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Long-term Memory Workspace：受限沙盒，按 userId 隔离用户根目录。
 * <p>所有文件操作必须 rooted 在该用户根目录内，{@link #resolve(Integer, String)} 规范化路径后断言以用户根目录为前缀，
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryWorkspace {

    private static final String AGENT_MD = "Agent.md";
    private static final String USER_MD = "User.md";
    private static final String DIRECTORY_MD = "Directory.md";
    private static final String CURRENT_MD = "Learning/Current.md";
    private static final String HISTORY_DIR = "History";

    /** 初始化时需要懒生成的最小模板文件：相对路径 → 模板内容 */
    private static final Map<String, String> INITIAL_TEMPLATES = Map.of(
            AGENT_MD, MemoryTemplates.AGENT_MD,
            USER_MD, MemoryTemplates.USER_MD,
            DIRECTORY_MD, MemoryTemplates.DIRECTORY_MD,
            CURRENT_MD, MemoryTemplates.CURRENT_MD
    );

    /**
     * 初始化时种入的两个模拟历史记忆（用于联调 Directory 动态扫描注入）。
     * <p>幂等：文件已存在则不覆盖，真实归档数据不受影响。
     */
    private static final Map<String, String> SEED_HISTORY = Map.of(
            "History/AgentMemory.md", MemoryTemplates.HISTORY_AGENT_MEMORY_MD,
            "History/Parser.md", MemoryTemplates.HISTORY_PARSER_MD
    );

    private final MemoryWorkspaceProperties properties;

    @PostConstruct
    void init() {
        if (properties.getRootDir() == null || properties.getRootDir().isBlank()) {
            log.warn("[MemoryWorkspace] rootDir 未配置（agent.memory.longterm.workspace.root-dir），Memory 功能将在首次访问时报错");
            return;
        }
        Path root = Paths.get(properties.getRootDir());
        try {
            Files.createDirectories(root);
            log.info("[MemoryWorkspace] 根目录就绪：{}", root.toAbsolutePath());
        } catch (IOException e) {
            // 不抛出：避免阻断容器启动；首次访问时再由具体操作暴露问题
            log.error("[MemoryWorkspace] 根目录创建失败：{}", root.toAbsolutePath(), e);
        }
    }

    /**
     * 解析用户根目录。
     */
    public Path userRoot(Integer userId) {
        if (userId == null) {
            throw new MemoryAccessException("userId 为空，无法定位 Memory Workspace");
        }
        if (properties.getRootDir() == null || properties.getRootDir().isBlank()) {
            throw new MemoryAccessException("agent.memory.longterm.workspace.root-dir 未配置");
        }
        return Paths.get(properties.getRootDir()).resolve(String.valueOf(userId)).normalize();
    }

    /**
     * 将相对路径解析为用户根目录下的绝对路径，并做沙盒前缀校验。
     * <p>拒绝 {@code ..} 与绝对路径越界：规范化后路径必须以用户根目录为前缀。
     *
     * @param userId       用户 ID
     * @param relativePath 相对路径（如 {@code Learning/Java.md}）
     * @return 用户根目录内的绝对路径
     */
    public Path resolve(Integer userId, String relativePath) {
        Path root = userRoot(userId).toAbsolutePath();
        if (relativePath == null || relativePath.isBlank()) {
            throw new MemoryAccessException("relativePath 为空");
        }
        Path target = root.resolve(relativePath).normalize();
        String targetStr = target.toString();
        String rootStr = root.toString();
        if (!targetStr.startsWith(rootStr) && !targetStr.equals(rootStr)) {
            throw new MemoryAccessException("路径越界，拒绝访问：" + relativePath);
        }
        // 进一步断言：目标必须位于 root 之下（而非 root 本身或其同级），通过路径层级校验
        if (target.getNameCount() <= root.getNameCount()) {
            throw new MemoryAccessException("路径越界，拒绝访问：" + relativePath);
        }
        return target;
    }

    /**
     * 列出当前用户 Workspace 全部 Markdown 文件，返回带相对路径的列表（相对用户根目录）。
     */
    public List<String> list(Integer userId) {
        Path root = userRoot(userId);
        if (!Files.exists(root)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".md")) {
                        result.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new MemoryAccessException("列出 Memory 文件失败：userId=" + userId, e);
        }
        return result;
    }

    /**
     * 读取指定相对路径的 Markdown 全文。
     */
    public String read(Integer userId, String relativePath) {
        Path target = resolve(userId, relativePath);
        if (!Files.exists(target)) {
            throw new MemoryAccessException("Memory 文件不存在：" + relativePath);
        }
        try {
            return Files.readString(target);
        } catch (IOException e) {
            throw new MemoryAccessException("读取 Memory 文件失败：" + relativePath, e);
        }
    }

    /**
     * 整体覆盖写入指定相对路径的 Markdown。父目录自动创建。
     */
    public void write(Integer userId, String relativePath, String content) {
        Path target = resolve(userId, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content);
        } catch (IOException e) {
            throw new MemoryAccessException("写入 Memory 文件失败：" + relativePath, e);
        }
    }

    /**
     * 用户首次访问时懒生成最小模板文件：Agent.md / User.md / Directory.md / Learning/Current.md。
     * <p>已存在的文件不覆盖；同时确保 History/ 目录存在，并种入两个模拟历史记忆（联调用，幂等不覆盖）。
     */
    public void initUserWorkspaceIfAbsent(Integer userId) {
        Path root = userRoot(userId);
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve(HISTORY_DIR));
            for (Map.Entry<String, String> entry : INITIAL_TEMPLATES.entrySet()) {
                Path file = resolve(userId, entry.getKey());
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.getValue());
                }
            }
            // 种入模拟历史记忆（联调用；真实归档文件已存在则不覆盖）
            for (Map.Entry<String, String> entry : SEED_HISTORY.entrySet()) {
                Path file = resolve(userId, entry.getKey());
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.getValue());
                }
            }
        } catch (IOException e) {
            throw new MemoryAccessException("初始化用户 Memory Workspace 失败：userId=" + userId, e);
        }
    }
}
