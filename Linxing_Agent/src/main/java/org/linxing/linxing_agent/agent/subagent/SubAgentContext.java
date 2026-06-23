package org.linxing.linxing_agent.agent.subagent;

import lombok.Getter;
import org.linxing.linxing_agent.agent.core.JsonContainer;
import org.linxing.linxing_agent.agent.tool.impl.jsoncontainer.JsonContainerStore;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * subagent 工作流执行期间的线程上下文。1、承载不应该暴露给大模型但是业务代码需要的信息，如userID；2、JSONContainer需要使用
 */
public final class SubAgentContext implements JsonContainerStore {

    private static final ThreadLocal<SubAgentContext> HOLDER = new ThreadLocal<>();

    @Getter
    private final Integer userId;

    @Getter
    private final Integer sessionId;

    private final Map<String, JsonContainer> containers;

    private final Map<String, Object> attributes;

    private SubAgentContext(Integer userId, Integer sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.containers = new LinkedHashMap<>();
        this.attributes = new LinkedHashMap<>();
    }

    /**
     * 在工作流入口处绑定上下文。
     */
    public static SubAgentContext bind(Integer userId, Integer sessionId) {
        Assert.notNull(userId, "userId 不能为空");
        SubAgentContext context = new SubAgentContext(userId, sessionId);
        HOLDER.set(context);
        return context;
    }

    /**
     * 获取当前线程绑定的上下文，若未绑定则返回 {@code null}。
     */
    public static SubAgentContext current() {
        return HOLDER.get();
    }

    /**
     * 获取当前线程绑定的 userId，若未绑定则返回 {@code null}。
     */
    public static Integer currentUserId() {
        SubAgentContext context = HOLDER.get();
        return context != null ? context.getUserId() : null;
    }

    /**
     * 获取当前线程绑定的 sessionId，若未绑定则返回 {@code null}。
     */
    public static Integer currentSessionId() {
        SubAgentContext context = HOLDER.get();
        return context != null ? context.getSessionId() : null;
    }

    /**
     * 获取当前线程绑定的容器存储，若未绑定则返回 {@code null}。
     */
    public static JsonContainerStore currentStore() {
        SubAgentContext context = HOLDER.get();
        return context != null ? context : null;
    }

    @Override
    public JsonContainer getContainer(String containerId) {
        return containers.get(containerId);
    }

    @Override
    public void putContainer(String containerId, JsonContainer container) {
        containers.put(containerId, container);
    }

    /**
     * 设置线程上下文属性，供 subagent 工作流中的业务组件共享中间结果。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取线程上下文属性。
     *
     * @param key 属性键
     * @param <T> 期望类型
     * @return 属性值，不存在时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 清空所有线程上下文属性。
     */
    public void clearAttributes() {
        attributes.clear();
    }

    /**
     * 清理当前线程绑定的上下文。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
