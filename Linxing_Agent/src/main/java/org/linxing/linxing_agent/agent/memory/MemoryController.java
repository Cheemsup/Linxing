package org.linxing.linxing_agent.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryFileWriter;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.linxing.linxing_agent.agent.memory.longterm.worker.CurrentTopicRegistry;
import org.linxing.linxing_agent.agent.memory.dto.MemoryWriteRequest;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 长期记忆用户入口：供前端查看/编辑当前用户的长期记忆 Markdown 文件。
 * <p>用户写走 {@link MemoryFileWriter#writeForce}（决策 8：用户优先级最高，跳过 CAS，但有原子写 + 旧版备份），
 * 绕过异步 Memory Worker 的 LLM 判断——用户主动编辑意图明确，无需 LLM 二次改写。
 * <p>用户隔离与沙盒越界校验由 {@link MemoryWorkspace} 内部完成，Controller 仅透传 BaseContext 的 userId。
 */
@RestController
@RequestMapping("/agent/memory")
@RequiredArgsConstructor
@Slf4j
public class MemoryController {

    private static final String CURRENT_DIR = "Learning/Current/";

    private final MemoryWorkspace memoryWorkspace;
    private final MemoryFileWriter memoryFileWriter;
    private final CurrentTopicRegistry currentTopicRegistry;

    /**
     * 列出当前用户 Workspace 全部 Markdown 文件的相对路径。
     * <p>首次访问时先调 {@link MemoryWorkspace#initUserWorkspaceIfAbsent} 懒生成模板，保证新用户立即见到初始记忆文件。
     */
    @GetMapping("/files")
    public Result<List<String>> listFiles() {
        Integer userId = getCurrentUserId();
        memoryWorkspace.initUserWorkspaceIfAbsent(userId);
        return Result.success(memoryWorkspace.list(userId));
    }

    /**
     * 读取指定相对路径的 Markdown 全文。
     *
     * @param path 相对用户根目录的路径，如 {@code Learning/Current.md}
     */
    @GetMapping("/file")
    public Result<String> readFile(@RequestParam String path) {
        Integer userId = getCurrentUserId();
        return Result.success(memoryWorkspace.read(userId, path));
    }

    /**
     * 整体覆盖写入指定相对路径的 Markdown。父目录自动创建。
     * <p>覆盖式保存：直接以 {@code content} 替换原文件内容，不维护 diff/patch。
     * <p>用户写走 {@link MemoryFileWriter#writeForce}（原子写 + 旧版备份，跳过 CAS）。
     * 写入 {@code Learning/Current/} 下新文件后触发超额主题归档检查。
     */
    @PostMapping("/file")
    public Result<Void> writeFile(@RequestBody @Valid MemoryWriteRequest body) {
        Integer userId = getCurrentUserId();
        boolean isNewFile = !memoryWorkspace.resolve(userId, body.getPath()).toFile().exists();
        memoryFileWriter.writeForce(userId, body.getPath(), body.getContent());
        if (body.getPath().startsWith(CURRENT_DIR) && isNewFile) {
            currentTopicRegistry.checkAndEvictIfOverQuota(userId);
        }
        return Result.success(null);
    }

    /**
     * 一键重建核心长期记忆模板（Agent.md / User.md / Directory.md）。
     * <p>供用户在发现记忆文件问题时无限次触发修复。强制覆盖上述三个核心模板；
     * 明确排除 {@code Learning/Current.md}（当前学习状态）与 {@code History/}（历史归档），这两段是用户数据，重建不得破坏。
     *
     * @return 实际重建（覆盖）的文件相对路径列表
     */
    @PostMapping("/rebuild")
    public Result<List<String>> rebuildTemplates() {
        Integer userId = getCurrentUserId();
        List<String> rebuilt = memoryWorkspace.rebuildTemplates(userId);
        log.info("用户重建长期记忆模板：userId={}, rebuilt={}", userId, rebuilt);
        return Result.success(rebuilt);
    }

    private static Integer getCurrentUserId() {
        return BaseContext.requireCurrentUserId();
    }
}