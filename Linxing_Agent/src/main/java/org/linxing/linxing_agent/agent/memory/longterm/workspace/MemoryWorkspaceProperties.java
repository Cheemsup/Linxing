package org.linxing.linxing_agent.agent.memory.longterm.workspace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Long-term Memory Workspace 配置。
 * <p>物理位置已决议取 {@code files_store/memory}，按 {@code userId} 子目录隔离多用户。
 * 实际用户根目录为 {@code {rootDir}/{userId}/}。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.memory.longterm.workspace")
public class MemoryWorkspaceProperties {

    /** Memory Workspace 根目录（不含 userId 子目录），如 {@code …………\Linxing\files_store\memory} */
    private String rootDir;
}
