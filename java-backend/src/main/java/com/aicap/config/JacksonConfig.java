package com.aicap.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置。
 * 对齐 FastAPI pydantic:默认 unknown 字段处理策略为"严格"(由各 DTO @JsonIgnoreProperties 决定);
 * meeting 域 DTO 不加注解 = 拒绝未知字段(extra="forbid"),常规 DTO 加 ignoreUnknown=true = 忽略。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictJsonCustomizer() {
        return builder -> builder.failOnUnknownProperties(true);
    }
}
