package org.linxing.linxing_agent.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.linxing.linxing_agent.agent.memory.dto.MemoryWriteRequest;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 长期记忆用户入口：供前端查看/编辑当前用户的长期记忆 Markdown 文件。
 * <p>用户写直接落盘，绕过异步 Memory Worker 的 LLM 判断——用户主动编辑意图明确，无需 LLM 二次改写。
 * <p>用户隔离与沙盒越界校验由 {@link MemoryWorkspace} 内部完成，Controller 仅透传 BaseContext 的 userId。
 */
@RestController
@RequestMapping("/agent/memory")
@RequiredArgsConstructor
@Slf4j
public class MemoryController {

    private final MemoryWorkspace memoryWorkspace;

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
     */
    @PostMapping("/file")
    public Result<Void> writeFile(@RequestBody @Valid MemoryWriteRequest body) {
        Integer userId = getCurrentUserId();
        memoryWorkspace.write(userId, body.getPath(), body.getContent());
        return Result.success(null);
    }

    private static Integer getCurrentUserId() {
        return BaseContext.requireCurrentUserId();
    }
}