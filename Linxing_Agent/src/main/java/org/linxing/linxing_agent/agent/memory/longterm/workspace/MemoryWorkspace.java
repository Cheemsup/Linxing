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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Long-term Memory Workspace：受限沙盒，按 userId 隔离用户根目录。
 * <p>所有文件操作必须 rooted 在该用户根目录内，{@link #resolve(Integer, String)} 规范化路径后断言以用户根目录为前缀，
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryWorkspace {

    private static final String HISTORY_DIR = "History";

    private final MemoryWorkspaceProperties properties;
    private final MemoryTemplates templates;

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
     * 列出根目录下全部用户 ID（目录名）。供 cron 跨用户操作（如历史合并）遍历使用。
     * <p>仅返回 {@code rootDir} 下的一级子目录名（即 userId 字符串），非递归。
     * 根目录未配置或不存在时返回空列表。
     */
    public List<String> listUserIds() {
        if (properties.getRootDir() == null || properties.getRootDir().isBlank()) {
            return List.of();
        }
        Path root = Paths.get(properties.getRootDir());
        if (!Files.exists(root)) {
            return List.of();
        }
        List<String> userIds = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .forEach(d -> {
                        String name = d.getFileName().toString();
                        // 仅保留纯数字目录名（userId）
                        if (name.matches("\\d+")) {
                            userIds.add(name);
                        }
                    });
        } catch (IOException e) {
            log.warn("[MemoryWorkspace] 列出用户目录失败：{}", e.getMessage());
        }
        return userIds;
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
     *
     * @deprecated 业务写路径（主 Agent write_memory / cron / 用户 HTTP 编辑）请走
     *             {@link MemoryFileWriter}（CAS 冲突检测 + 原子写 + 旧版备份）。
     *             <p>本方法仅供模板种入（{@link #initUserWorkspaceIfAbsent}）与重建
     *             （{@link #rebuildTemplates}）使用——它们覆盖为权威模板内容，无需 CAS/备份。
     */
    @Deprecated
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
     * 删除指定相对路径的文件。仅用于超额主题驱逐——longterm 包首个 delete 操作。
     * <p>沙盒前缀校验复用 {@link #resolve}；文件不存在视为成功（幂等）。
     *
     * @param userId       用户 ID
     * @param relativePath 相对路径
     */
    public void delete(Integer userId, String relativePath) {
        Path target = resolve(userId, relativePath);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new MemoryAccessException("删除 Memory 文件失败：" + relativePath, e);
        }
    }

    /**
     * 同卷移动文件（归档原始文件移入 .raw/ 等）。父目录自动创建。
     * <p>沙盒前缀校验复用 {@link #resolve}；用 {@link Files#move} 原子移动。
     *
     * @param userId    用户 ID
     * @param fromRel   源相对路径
     * @param toRel     目标相对路径
     */
    public void move(Integer userId, String fromRel, String toRel) {
        Path from = resolve(userId, fromRel);
        Path to = resolve(userId, toRel);
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new MemoryAccessException("移动 Memory 文件失败：" + fromRel + " -> " + toRel, e);
        }
    }

    /**
     * 用户首次访问时懒生成最小模板文件：Agent.md / User.md / Directory.md。
     * <p>已存在的文件不覆盖；同时确保 History/ 与 Learning/Current/ 目录存在
     * （Current/ 为空目录，主题文件由 Agent 按需创建，决策 4：多主题）。
     * <p>历史归档完全由超额驱逐真实生成，此处不种入任何模拟历史记忆。
     */
    public void initUserWorkspaceIfAbsent(Integer userId) {
        Path root = userRoot(userId);
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve(HISTORY_DIR));
            Files.createDirectories(root.resolve("Learning/Current"));
            for (String path : MemoryTemplates.SEEDABLE) {
                Path file = resolve(userId, path);
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, templates.get(path));
                }
            }
        } catch (IOException e) {
            throw new MemoryAccessException("初始化用户 Memory Workspace 失败：userId=" + userId, e);
        }
    }

    /**
     * 重建核心模板文件：强制覆盖 {@link MemoryTemplates#REBUILDABLE}（Agent.md / User.md / Directory.md）。
     * <p>供用户一键修复长期记忆模板——发现文件有问题时可无限次触发。
     * <p>范围明确排除 {@code Learning/Current.md}（用户当前学习状态）与 {@code History/}（历史归档），
     * 这两段是用户数据，重建不得破坏。父目录与 History/ 目录一并确保存在。
     *
     * @return 实际重建（覆盖）的相对路径列表
     */
    public List<String> rebuildTemplates(Integer userId) {
        Path root = userRoot(userId);
        List<String> rebuilt = new ArrayList<>();
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve(HISTORY_DIR));
            for (String path : MemoryTemplates.REBUILDABLE) {
                Path file = resolve(userId, path);
                Files.createDirectories(file.getParent());
                Files.writeString(file, templates.get(path));
                rebuilt.add(path);
            }
        } catch (IOException e) {
            throw new MemoryAccessException("重建用户 Memory 模板失败：userId=" + userId, e);
        }
        return rebuilt;
    }
}
