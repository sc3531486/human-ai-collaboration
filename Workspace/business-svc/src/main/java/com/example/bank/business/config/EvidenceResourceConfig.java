package com.example.bank.business.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.file.Paths;
/** 业务影像的统一只读入口，支持图片、PDF 及录像的 Range 下载。 */
@Configuration
public class EvidenceResourceConfig implements WebMvcConfigurer {
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry){
        String location=Paths.get("records").toAbsolutePath().normalize().toUri().toString();
        if(!location.endsWith("/"))location+="/";
        registry.addResourceHandler("/records/**").addResourceLocations(location);
    }
}
