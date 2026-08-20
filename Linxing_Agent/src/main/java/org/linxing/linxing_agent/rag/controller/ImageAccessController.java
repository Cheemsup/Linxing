package org.linxing.linxing_agent.rag.controller;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.entity.DocRecord;
import org.linxing.linxing_agent.rag.mapper.DocumentMapper;
import org.linxing.linxing_agent.rag.storage.FileStoreLayout;
import org.linxing.linxing_agent.rag.storage.ImagePathSigner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 图片访问控制器：GET /assets/images/{userId}/documents/{documentId}/images/{imageName}。
 *
 * <p>该路径在 {@code WebMvcConfig.addInterceptors} 中已从 JWT 拦截器排除——图片由前端
 * {@code <img>} 直接加载、无法携带 Authorization 头，故鉴权改用短时签名（见
 * {@link ImagePathSigner}）：URL 附带 {@code expires}&{@code sig}，签名即权限。
 *
 * <p>越权防护三层：签名校验（防 URL 伪造/扩散）＋ userId/docId 归属校验（防跨租户联调）＋
 * imageKey 白名单路径解析（防 {@code ..} 穿越）。图片仅允许命中当前 user 名下的 doc 才会被读取。
 */
@Slf4j
@RestController
@RequestMapping("/assets/images")
public class ImageAccessController {

    private final ImagePathSigner imagePathSigner;
    private final DocumentMapper documentMapper;
    private final String storePath;
    private final int signTtlSeconds;

    public ImageAccessController(ImagePathSigner imagePathSigner,
                                 DocumentMapper documentMapper,
                                 RagProperties ragProperties) {
        this.imagePathSigner = imagePathSigner;
        this.documentMapper = documentMapper;
        this.storePath = ragProperties.getStorePath();
        this.signTtlSeconds = ragProperties.getSecurity().getImageSignTtl();
    }

    @GetMapping("/{userId}/documents/{documentId}/images/{imageName}")
    public ResponseEntity<?> getImage(
            @PathVariable Integer userId,
            @PathVariable Integer documentId,
            @PathVariable String imageName,
            @RequestParam String expires,
            @RequestParam String sig) {

        // 签名即鉴权：校验 HMAC 与过期窗口（宽限 60s 容忍时钟偏差）
        String imageKey = userId + "/documents/" + documentId + "/images/" + imageName;
        long now = System.currentTimeMillis();
        if (!imagePathSigner.verify(imageKey, expires, sig, now, TimeUnit.SECONDS.toMillis(60))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("签名无效或已过期");
        }

        // 归属校验：documentId 必须属于该 userId（防跨租户通过签名 URL 访问他人文档图片）
        Optional<DocRecord> rec = documentMapper.findById(documentId);
        if (rec.isEmpty() || !rec.get().getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }

        // 白名单路径解析（imageKey 正则限定字符，杜绝 .. 穿越）
        Path imagePath = FileStoreLayout.resolveFromImageKey(storePath, imageKey);
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(inferMediaType(imageName))
                // 私密缓存：命中即可复用，但禁止共享/上游缓存二次分发（签名会过期）
                .cacheControl(CacheControl.maxAge(Math.min(signTtlSeconds, 3600), TimeUnit.SECONDS).cachePrivate())
                .body(new FileSystemResource(imagePath));
    }

    private static MediaType inferMediaType(String imageName) {
        String lower = imageName.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".bmp")) {
            return MediaType.parseMediaType("image/bmp");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
