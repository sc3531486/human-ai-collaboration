package com.example.bank.business.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        File recordsDir = new File("records");
        if (!recordsDir.exists()) {
            recordsDir.mkdirs();
        }
        String absolutePath = recordsDir.getAbsolutePath();
        if (!absolutePath.endsWith(File.separator)) {
            absolutePath += File.separator;
        }

        registry.addResourceHandler("/records/**")
                .addResourceLocations("file:" + absolutePath);
    }
}
