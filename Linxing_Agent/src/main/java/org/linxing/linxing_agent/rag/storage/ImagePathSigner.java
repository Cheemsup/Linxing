package org.linxing.linxing_agent.rag.storage;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 图片访问签名签发/校验器（S3 presigned URL 风格）。
 *
 * <p>/assets/images/** 免 JWT 公开访问，隔离靠短时签名：URL 附带
 * {@code expires=<毫秒时间戳>}&{@code sig=HMAC-SHA256(secret, "/assets/images/" + imageKey + "?expires=" + expires)}
 * （hex 小写）。图片资源通过 {@code <img>} 直接加载、无法携带请求头，故以签名替代 Bearer Token。
 *
 * <p>DB（nodeMetadata.imagePath）存储的是 imageKey（不过期、不带查询参数），仅在对前端
 * 暴露时动态签发签名 URL；语义增强直读物理路径不经过本类。
 */
@Component
public class ImagePathSigner {

    private final byte[] secret;
    private final long ttlMillis;

    public ImagePathSigner(org.linxing.linxing_agent.rag.config.RagProperties properties) {
        this.secret = properties.getSecurity().getImageSignSecret().getBytes(StandardCharsets.UTF_8);
        this.ttlMillis = properties.getSecurity().getImageSignTtl() * 1000L;
    }

    /**
     * 为 imageKey 签发签名 URL（响应给前端用）。
     *
     * <p>返回的是前端可直接用于 {@code <img>} 的地址（带 {@code /api/assets/images/} 前缀，
     * 由 devServer/网关剥离 {@code /api} 后落到后端 {@code /assets/images/**}）；
     * 而 HMAC 签名覆盖的是后端资源路径（不含 {@code /api} 前缀），与 {@link #verify} 一致。
     *
     * @param imageKey 规范资源键，形如 {@code 2/documents/101/images/p001_01.png}
     * @param nowMs    当前毫秒时间戳（便于测试注入）
     * @return 形如 {@code /api/assets/images/{imageKey}?expires=..&sig=..}，imageKey 非法返回 null
     */
    public String sign(String imageKey, long nowMs) {
        if (imageKey == null || imageKey.isBlank()) {
            return null;
        }
        long expires = nowMs + ttlMillis;
        // 签名覆盖后端资源路径（不动 /api 前缀），前端展示时再拼 /api 走代理
        String resourcePath = "/assets/images/" + imageKey + "?expires=" + expires;
        String sig = hmacHex(this.secret, resourcePath);
        return "/api" + resourcePath + "&sig=" + sig;
    }

    /**
     * 校验签名 URL 是否有效（当前时间、过期窗口、HMAC 一致）。
     *
     * @param imageKey    规范资源键
     * @param expiresStr  请求里的 expires 毫秒时间戳
     * @param sig         请求里的 sig
     * @param nowMs       当前毫秒时间戳
     * @param graceMillis 过期宽限（允许小量时钟偏差）
     */
    public boolean verify(String imageKey, String expiresStr, String sig, long nowMs, long graceMillis) {
        if (imageKey == null || sig == null || expiresStr == null) {
            return false;
        }
        long expires;
        try {
            expires = Long.parseLong(expiresStr);
        } catch (NumberFormatException e) {
            return false;
        }
        // 过期且不在宽限窗内 → 拒绝
        if (expires + graceMillis < nowMs) {
            return false;
        }
        String message = "/assets/images/" + imageKey + "?expires=" + expires;
        String expected = hmacHex(this.secret, message);
        return constantTimeEquals(expected, sig);
    }

    /**
     * 遍历 nodeMetadata 列表，把形如 imageKey 的 imagePath 值替换为签名 URL。
     * 未知形状的项原样保留（幂等，便于重复调用）。
     *
     * @param nodeMetadata nodeMetadata 列表（DB 侧存 imageKey）
     * @param nowMs        当前毫秒时间戳
     */
    public void signMetadataImages(List<Map<String, Object>> nodeMetadata, long nowMs) {
        if (nodeMetadata == null || nodeMetadata.isEmpty()) {
            return;
        }
        for (Map<String, Object> meta : nodeMetadata) {
            Object raw = meta.get("imagePath");
            if (raw == null) {
                continue;
            }
            String imageKey = String.valueOf(raw);
            // 已是签名 URL（带 query）或非 imageKey 形状 → 跳过
            if (imageKey.contains("?") || FileStoreLayout.resolveFromImageKey("", imageKey) == null) {
                continue;
            }
            String signed = sign(imageKey, nowMs);
            if (signed != null) {
                // 与前端代理剥离 /api 前缀对齐：默认返回全路径 /assets/images/{key}?...
                meta.put("imagePath", signed);
            }
        }
    }

    private static String hmacHex(byte[] secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("图片签名计算失败", e);
        }
    }

    /** 固定时间比较，防时序侧信道。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
        }
        return sb.toString();
    }
}
