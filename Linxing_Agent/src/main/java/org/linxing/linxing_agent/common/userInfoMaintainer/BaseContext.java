package org.linxing.linxing_agent.common.userInfoMaintainer;

public class BaseContext {
    private static final ThreadLocal<UserInfo> threadLocal = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        UserInfo userInfo = threadLocal.get();
        if (userInfo == null) {
            userInfo = new UserInfo();
        }
        userInfo.setUserId(id);
        threadLocal.set(userInfo);
    }

    public static Long getCurrentId() {
        UserInfo userInfo = threadLocal.get();
        return userInfo != null ? userInfo.getUserId() : null;
    }

    /**
     * 获取当前登录用户ID并转为Integer，未登录则抛IllegalStateException
     * @return 当前用户ID
     */
    public static Integer requireCurrentUserId() {
        Long id = getCurrentId();
        if (id == null) {
            throw new IllegalStateException("用户未登录");
        }
        return id.intValue();
    }

    public static void setCurrentUser(UserInfo userInfo) {
        threadLocal.set(userInfo);
    }

    public static UserInfo getCurrentUser() {
        return threadLocal.get();
    }

    public static void setCurrentUsername(String username) {
        UserInfo userInfo = threadLocal.get();
        if (userInfo == null) {
            userInfo = new UserInfo();
        }
        userInfo.setUsername(username);
        threadLocal.set(userInfo);
    }

    public static String getCurrentUsername() {
        UserInfo userInfo = threadLocal.get();
        return userInfo != null ? userInfo.getUsername() : null;
    }

    public static void removeCurrentId() {
        threadLocal.remove();
    }

    public static void clear() {
        threadLocal.remove();
    }
}
