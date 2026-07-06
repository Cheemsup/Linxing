package org.linxing.linxing_agent.common.config;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.interceptor.JwtTokenUserInterceptor;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@Slf4j
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private JwtTokenUserInterceptor jwtTokenUserInterceptor;

    @Autowired
    private RagProperties ragProperties;

    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login",
                        "/user/register",
                        "/chunk_images/**"
                );
        log.info("自定义拦截器注册完成");
    }

    /**
     * 暴露 chunk 图片静态资源：/chunk_images/** → 物理目录 files_store/chunk_images/。
     *
     * 图片请求不带 JWT，靠路径中的 userId/docId 隔离；已在 addInterceptors 中排除 /chunk_images/**。
     * 目录优先取 RagProperties.pythonService.imageStoreDir（与 Python 侧一致），
     * 缺省回退到 storePath 下的 chunk_images 子目录。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String imageStoreDir = ragProperties.getPythonService().getImageStoreDir();
        String storePath = ragProperties.getStorePath();
        String baseDir = (imageStoreDir != null && !imageStoreDir.isBlank())
                ? imageStoreDir
                : (storePath != null && !storePath.isBlank() ? Paths.get(storePath, "chunk_images").toString() : null);
        if (baseDir == null) {
            log.warn("未配置 rag.store-path / rag.python-service.image-store-dir，跳过 /chunk_images/** 静态资源映射");
            return;
        }
        Path resolved = Paths.get(baseDir).toAbsolutePath().normalize();
        // file: 协议 URL 必须以 / 结尾，且 Windows 路径需转为正斜杠
        String location = "file:" + resolved.toString().replace('\\', '/') + "/";
        registry.addResourceHandler("/chunk_images/**")
                .addResourceLocations(location);
        log.info("注册静态资源映射: /chunk_images/** → {}", location);
    }
}
