package com.xingchen.huibiao.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 前端开发时走 Vite 代理，直接调接口时放开本机跨域。
 * 如果 frontend/dist 已经构建过，后端也能把静态资源带起来，演示时只开一个进程。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dist = Paths.get(properties.getFrontendDir()).toAbsolutePath().normalize();
        if (Files.exists(dist.resolve("index.html"))) {
            registry.addResourceHandler("/assets/**")
                    .addResourceLocations(dist.resolve("assets").toUri().toString());
            // index.html 不缓存，重新构建前端后普通刷新就能拿到新的资源版本
            registry.addResourceHandler("/index.html")
                    .addResourceLocations(dist.toUri().toString())
                    .setCacheControl(CacheControl.noCache());
            registry.addResourceHandler("/favicon.ico")
                    .addResourceLocations(dist.toUri().toString());
        }
    }
}
