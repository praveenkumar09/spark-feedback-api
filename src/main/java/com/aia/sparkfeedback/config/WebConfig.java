package com.aia.sparkfeedback.config;

import com.aia.sparkfeedback.auth.SessionAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SessionAuth auth;
    private final String[] origins;

    public WebConfig(SessionAuth auth, @Value("${spark.cors.allowed-origins}") String origins) {
        this.auth = auth;
        this.origins = origins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry r) {
        r.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET", "POST", "PUT", "OPTIONS").allowedHeaders("*").maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(auth).addPathPatterns("/api/sessions/**", "/api/speech/**");
    }
}
