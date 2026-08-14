package org.linxing.linxing_agent.agent.memory.longterm.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 长期记忆文件统一写入器：CAS 冲突检测 + 原子写 + 旧版备份。
 *
 * <p>所有业务写路径（主 Agent {@code write_memory} / cron 合并 / 用户 HTTP 编辑）均收口到本类，
 * 解决用户与 Agent 并发改写同一 md 文件时的丢更新问题。
 *
 * <p><b>CAS 策略（仅 Agent 写路径）</b>：Agent 写入前先 {@link #readBaseline} 记录 mtime+size 基线，
 * 落盘前在锁内再读一次基线比对，不符即冲突——直接放弃 Agent 本次写（用户赢，不写 sidecar，不自动合并）。
 * 成功覆盖前旧版直接丢弃（不保留历史版本），落盘用 temp+rename 原子写。
 *
 * <p><b>用户写路径</b>：{@link #writeForce} 跳过 CAS（用户优先级更高），但走同一原子写（旧版同样直接丢弃）。
 *
 * <p><b>并发控制</b>：per-file {@link ReentrantLock}（{@code ConcurrentHashMap<Path, ReentrantLock>}）
 * 包住"读基线→CAS→原子写"临界区，防 LLM 自相残杀；用户写与 Agent 写同一文件时也互斥，
 * 但用户靠不 CAS 赢内容。
 *
 * <p>调研依据：Claude Code 无锁 LWW+git 兜底；mem0 无锁丢更新（反面教材）；
 * Linxing md 不入 git 无兜底，故比 Claude Code 多一层 CAS；用户用外部编辑器改文件不遵守应用锁，
 * 故悲观锁无效，只有磁盘 CAS（mtime+size）能感知。
 *
 * TODO：锁映射 {@link #locks} 无界增长（用户×文件数），当前增长缓慢；
 *      若 profile 显示增长再考虑 WeakHashMap 或 LRU 淘汰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryFileWriter {

    private final MemoryWorkspace memoryWorkspace;

    /** per-file 锁，按绝对路径隔离 */
    private final ConcurrentHashMap<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * CAS 基线：文件 mtime（毫秒）+ size（字节）。轻量，不做内容 hash。
     *
     * @param lastModifiedMillis 文件最后修改时间（{@link Files#getLastModifiedTime}）
     * @param size               文件字节大小
     */
    public record FileBaseline(long lastModifiedMillis, long size) {
        /**
         * 从磁盘文件读取当前基线。
         *
         * @param p 文件路径
         * @return 基线
         * @throws IOException 读取失败
         */
        public static FileBaseline of(Path p) throws IOException {
            return new FileBaseline(
                    Files.getLastModifiedTime(p).toMillis(),
                    Files.size(p));
        }
    }

    /**
     * 写入结果。
     */
    public sealed interface WriteResult {
        /** 写入成功 */
        record Success(Path target) implements WriteResult {}
        /** CAS 冲突：Agent 放弃本次写，用户赢 */
        record Conflict(Path target, FileBaseline expected, FileBaseline actual) implements WriteResult {}
        /** 跳过（如内容为空等无需写入场景） */
        record Skipped(Path target, String reason) implements WriteResult {}
    }

    /**
     * Agent CAS 写。baseline 为 null 表示"文件必须不存在"（新建）。
     * <p>基线不符 → 返回 {@link WriteResult.Conflict}，Agent 放弃（不写 sidecar，不合并）。
     * <p>成功 → 旧版直接丢弃（不保留历史版本），再 temp+rename 原子写。
     *
     * @param userId       用户 ID
     * @param relativePath 相对路径
     * @param baseline     Agent 读取时记录的基线（null 表示新建）
     * @param newContent   新内容
     * @return 写入结果
     */
    public WriteResult writeIfUnchanged(Integer userId, String relativePath,
                                        FileBaseline baseline, String newContent) {
        Path target = memoryWorkspace.resolve(userId, relativePath);
        ReentrantLock lock = locks.computeIfAbsent(target, k -> new ReentrantLock());
        lock.lock();
        try {
            boolean existed = Files.exists(target);
            // CAS 比对：当前磁盘基线 vs Agent 读取时基线
            if (baseline != null) {
                if (!existed) {
                    // Agent 以为文件存在，实际已被删——冲突，放弃
                    log.warn("[MemoryFileWriter] CAS 冲突：文件已不存在 userId={} path={}", userId, relativePath);
                    return new WriteResult.Conflict(target, baseline, null);
                }
                FileBaseline actual = FileBaseline.of(target);
                if (actual.lastModifiedMillis() != baseline.lastModifiedMillis()
                        || actual.size() != baseline.size()) {
                    log.warn("[MemoryFileWriter] CAS 冲突：基线不符 userId={} path={} expected={} actual={}",
                            userId, relativePath, baseline, actual);
                    return new WriteResult.Conflict(target, baseline, actual);
                }
            } else if (existed) {
                // Agent 以为新建，实际文件已存在——冲突，放弃
                log.warn("[MemoryFileWriter] CAS 冲突：期望新建但文件已存在 userId={} path={}", userId, relativePath);
                return new WriteResult.Conflict(target, null, FileBaseline.of(target));
            }
            atomicWrite(target, newContent == null ? "" : newContent);
            log.info("[MemoryFileWriter] CAS 写入成功 userId={} path={}", userId, relativePath);
            return new WriteResult.Success(target);
        } catch (MemoryAccessException e) {
            // resolve 抛出的沙盒越界等，直接透传
            throw e;
        } catch (IOException e) {
            throw new MemoryAccessException("CAS 写入失败：" + relativePath, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 用户/系统强制写：跳过 CAS，但走同一原子写（旧版直接丢弃，不保留历史版本）。
     * <p>用户 HTTP 编辑优先级最高，不因 Agent 基线过期被拒；cron 合并等系统写入也走此路。
     *
     * @param userId       用户 ID
     * @param relativePath 相对路径
     * @param newContent   新内容
     * @return 写入结果
     */
    public WriteResult writeForce(Integer userId, String relativePath, String newContent) {
        Path target = memoryWorkspace.resolve(userId, relativePath);
        ReentrantLock lock = locks.computeIfAbsent(target, k -> new ReentrantLock());
        lock.lock();
        try {
            atomicWrite(target, newContent == null ? "" : newContent);
            log.info("[MemoryFileWriter] 强制写入成功 userId={} path={}", userId, relativePath);
            return new WriteResult.Success(target);
        } catch (MemoryAccessException e) {
            throw e;
        } catch (IOException e) {
            throw new MemoryAccessException("强制写入失败：" + relativePath, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取当前磁盘基线，供 Agent 调用 {@link #writeIfUnchanged} 时回传。
     * <p>文件不存在时返回 null（表示"新建"语义）。
     *
     * @param userId       用户 ID
     * @param relativePath 相对路径
     * @return 当前基线，文件不存在返回 null
     */
    public FileBaseline readBaseline(Integer userId, String relativePath) {
        Path target = memoryWorkspace.resolve(userId, relativePath);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return FileBaseline.of(target);
        } catch (IOException e) {
            throw new MemoryAccessException("读取基线失败：" + relativePath, e);
        }
    }

    /**
     * 原子写：临时文件与 target 同目录（同卷，Windows 上 ATOMIC_MOVE 才生效）→ 写内容 → move 覆盖。
     */
    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".md");
        try {
            Files.writeString(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            // 异常时清理临时文件，避免残留
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 清理失败不影响主异常
            }
            throw e;
        }
    }
}
