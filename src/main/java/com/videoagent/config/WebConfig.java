package com.videoagent.config;

import com.videoagent.access.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * MVC 配置：本地数据目录映射为 /files/** 静态资源 + 注册认证拦截器（保护 /api/**）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final StorageProperties storage;
    private final AuthInterceptor authInterceptor;

    public WebConfig(StorageProperties storage, AuthInterceptor authInterceptor) {
        this.storage = storage;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String rootPath = Paths.get(storage.root()).toAbsolutePath().normalize().toString().replace('\\', '/');
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:" + rootPath + "/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**", "/files/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
    }
}