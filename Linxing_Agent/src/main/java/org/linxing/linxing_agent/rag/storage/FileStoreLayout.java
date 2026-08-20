package org.linxing.linxing_agent.rag.storage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 文件存储多租户命名空间布局的唯一真源。
 *
 * <p>布局（与 Python 侧 document_analysis_service 的落盘契约一致）：
 * <pre>
 *   {storePath}/tenants/{userId}/documents/{documentId}/
 *       ├── source/   原文档：{sanitizedBasename}.{ext}
 *       └── images/   解析图片：p{page:03d}_{seq:03d}.{ext}
 * </pre>
 *
 * <p>Python 返回并落库的 imagePath 即资源键（imageKey）：
 * {@code {userId}/documents/{documentId}/images/{imageName}}。Java 侧据此拼物理路径
 * 供语义增强直读，前端拿同一键生成签名 URL 展示——单一真源，无二次翻译。
 */
public final class FileStoreLayout {

    private FileStoreLayout() {
    }

    /** 顶层多租户根：{storePath}/tenants */
    public static Path tenantRoot(String storePath) {
        return Paths.get(storePath).resolve("tenants");
    }

    /** 单个租户根（预留组织级扩展）：{storePath}/tenants/{userId} */
    public static Path userRoot(String storePath, Integer userId) {
        return tenantRoot(storePath).resolve(String.valueOf(userId));
    }

    /** 单个文档目录：{storePath}/tenants/{userId}/documents/{documentId} */
    public static Path docDir(String storePath, Integer userId, Integer documentId) {
        return userRoot(storePath, userId).resolve("documents").resolve(String.valueOf(documentId));
    }

    /** 原文档目录：{storePath}/tenants/{userId}/documents/{documentId}/source */
    public static Path sourceDir(String storePath, Integer userId, Integer documentId) {
        return docDir(storePath, userId, documentId).resolve("source");
    }

    /** 解析图片目录：{storePath}/tenants/{userId}/documents/{documentId}/images */
    public static Path imagesDir(String storePath, Integer userId, Integer documentId) {
        return docDir(storePath, userId, documentId).resolve("images");
    }

    // 合法 imageKey：{userId}/documents/{documentId}/images/{imageName}
    // imageName 限定字母数字、点、下划线、短横，杜绝路径穿越与分隔符注入
    private static final Pattern IMAGE_KEY_PATTERN =
            Pattern.compile("^[0-9]+/documents/[0-9]+/images/[A-Za-z0-9._-]+$");

    /**
     * 将 imageKey 解析为物理路径（{storePath}/tenants/ + imageKey）。
     *
     * <p>对输入做白名单校验，阻止 {@code ..} 穿越与绝对路径注入；不合法返回 {@code null}。
     *
     * @param storePath 存储根目录
     * @param imageKey 资源键，形如 {@code 2/documents/101/images/p001_01.png}
     */
    public static Path resolveFromImageKey(String storePath, String imageKey) {
        if (imageKey == null || imageKey.isBlank() || !IMAGE_KEY_PATTERN.matcher(imageKey).matches()) {
            return null;
        }
        return tenantRoot(storePath).resolve(imageKey);
    }
}
