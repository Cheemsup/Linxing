package com.sky.context;

/**
 * 注意，本代码仅供参考，现在的项目中在threadlocal不应该只局限于存储用户id，还可以存储其他信息，
 * 而且在使用时也应该根据实际情况来判断是否需要使用threadlocal。
 */
public class BaseContext {
    public static ThreadLocal<Long> threadLocal = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        threadLocal.set(id);
    }

    public static Long getCurrentId() {
        return threadLocal.get();
    }

    public static void removeCurrentId() {
        threadLocal.remove();
    }
}