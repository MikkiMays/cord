package com.dev.cord.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Применить ко всем путям
                .allowedOrigins("http://localhost:3000/") // Укажите ваши допустимые источники
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowCredentials(true); // Разрешить учетные данные
    }
}