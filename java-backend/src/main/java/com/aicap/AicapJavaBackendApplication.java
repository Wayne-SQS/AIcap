package com.aicap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 爱管理 AIcap JavaWeb 后端启动类。
 * 对应 FastAPI 版 main.py:同一 /api 契约、同一 AIcap MySQL 库。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
@MapperScan("com.aicap.mapper")
public class AicapJavaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AicapJavaBackendApplication.class, args);
    }
}
