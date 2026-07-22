package org.linxing.linxing_agent.agent.memory.longterm.injector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * History 元信息扫描器：运行时扫描 {@code History/} 下已完成的学习阶段归档文件，
 * 提取每文件的学习主题与完成时间，产出元信息摘要段供 {@link LongMemoryInjector} 注入上下文。
 *
 * <p>设计动机：Directory.md 是静态导航文件，但 History 会随学习推进不断新增归档。
 * 为避免静态文件与实际归档脱节，History 部分由本类在每次装配上下文时实时扫描生成，
 * 其余导航骨架（Agent/User/Learning）仍由静态 Directory.md 提供。
 *
 * <p>扫描范围：{@code History/*.md}，跳过 {@code History/.trash/} 兜底备份目录。
 * 解析规则依赖 {@code HistoryArchiver} 产出的归档模板（{@code ## 学习主题} / {@code ## 完成时间}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryMetaScanner {

    private static final String HISTORY_DIR = "History";
    private static final String TRASH_SEGMENT = ".trash";
    private static final String TOPIC_HEADER = "## 学习主题";
    private static final String COMPLETED_HEADER = "## 完成时间";

    private final MemoryWorkspace memoryWorkspace;

    /**
     * 扫描用户 History/ 下的归档文件，产出元信息摘要段。
     * <p>无归档或读取失败时返回空串（降级跳过，不阻断上下文装配）。
     *
     * @param userId 用户 ID
     * @return 形如「- Agent Memory（完成于 2026-07-18）」的列表段；空则返回空串
     */
    public String scanHistoryMetaSection(Integer userId) {
        if (userId == null) {
            return "";
        }
        List<String> files;
        try {
            files = memoryWorkspace.list(userId);
        } catch (MemoryAccessException e) {
            log.warn("[HistoryMetaScanner] 列出文件失败 userId={}: {}", userId, e.getMessage());
            return "";
        }
        List<HistoryMeta> metas = new ArrayList<>();
        for (String path : files) {
            if (!isHistoryArchive(path)) {
                continue;
            }
            String content = safeRead(userId, path);
            if (content.isBlank()) {
                continue;
            }
            String topic = firstLine(extractSection(content, TOPIC_HEADER));
            String completed = firstLine(extractSection(content, COMPLETED_HEADER));
            if (topic.isBlank() && completed.isBlank()) {
                continue;//无有效元信息，跳过
            }
            metas.add(new HistoryMeta(path, topic, completed));
        }
        if (metas.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("已完成学习阶段（History 实时扫描）：\n");
        for (HistoryMeta m : metas) {
            sb.append("- ");
            if (!m.topic().isBlank()) {
                sb.append(m.topic());
            } else {
                sb.append(m.path());
            }
            if (!m.completed().isBlank()) {
                sb.append("（完成于 ").append(m.completed()).append("）");
            }
            sb.append("\n");
        }
        sb.append("如需查看某阶段详细总结，使用 @").append(HISTORY_DIR)
                .append("/<文件名> 引用或调用 read_memory。");
        return sb.toString();
    }

    /**
     * 判定是否为 History 归档文件：以 History/ 开头、.md 结尾、不含 .trash 段。
     */
    private static boolean isHistoryArchive(String path) {
        if (path == null || !path.startsWith(HISTORY_DIR + "/") || !path.endsWith(".md")) {
            return false;
        }
        for (String seg : path.split("/")) {
            if (TRASH_SEGMENT.equals(seg)) {
                return false;
            }
        }
        return true;
    }

    private String safeRead(Integer userId, String path) {
        try {
            return memoryWorkspace.read(userId, path);
        } catch (MemoryAccessException e) {
            log.warn("[HistoryMetaScanner] 读取 History 失败 userId={} path={}: {}", userId, path, e.getMessage());
            return "";
        }
    }

    /**
     * 提取指定二级标题之后的 Section body（到下一个二级标题或文末）。
     */
    private static String extractSection(String md, String header) {
        if (md == null || md.isBlank()) {
            return "";
        }
        int idx = md.indexOf(header);
        if (idx < 0) {
            return "";
        }
        int bodyStart = idx + header.length();
        int nextH2 = md.indexOf("\n## ", bodyStart);
        return nextH2 < 0 ? md.substring(bodyStart).trim() : md.substring(bodyStart, nextH2).trim();
    }

    /**
     * 取 Section body 的首行非空文本（Topic/完成时间通常是单行值）。
     */
    private static String firstLine(String section) {
        if (section == null || section.isBlank()) {
            return "";
        }
        for (String line : section.split("\n")) {
            String t = line.trim();
            if (!t.isBlank() && !t.startsWith("<!--")) {
                return t;
            }
        }
        return "";
    }

    /**
     * 历史归档元信息。
     */
    private record HistoryMeta(String path, String topic, String completed) {
    }
}
