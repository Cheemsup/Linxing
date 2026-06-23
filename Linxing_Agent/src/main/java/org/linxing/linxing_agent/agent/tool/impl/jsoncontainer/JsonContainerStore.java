package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import org.linxing.linxing_agent.agent.core.JsonContainer;

/**
 * JSON 容器存储接口。
 * <p>
 * 将容器存取逻辑与 {@link org.linxing.linxing_agent.agent.core.AgentContext} 解耦，
 * 使容器工具既能被主循环使用（基于请求级 {@code AgentContext}），
 * 也能被 subagent 体系使用（基于线程级存储）。
 */
public interface JsonContainerStore {

    /**
     * 根据容器 ID 获取容器。
     *
     * @param containerId 容器 ID
     * @return 容器，不存在时返回 {@code null}
     */
    JsonContainer getContainer(String containerId);

    /**
     * 放入或更新容器。
     *
     * @param containerId 容器 ID
     * @param container   容器
     */
    void putContainer(String containerId, JsonContainer container);
}
