package com.axiom.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the built React SPA from classpath:/static and forwards unknown paths
 * to index.html so client-side navigation works on refresh.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected org.springframework.core.io.Resource getResource(
                            String path, org.springframework.core.io.Resource location) throws IOException {
                        if (path.startsWith("api/")) return null;
                        org.springframework.core.io.Resource requested = location.createRelative(path);
                        if (requested.exists() && requested.isReadable()) return requested;
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
