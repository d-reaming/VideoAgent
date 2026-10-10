package com.videoagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.videoagent.storage.mapper")
@EnableScheduling
public class VideoAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoAgentApplication.class, args);
    }
}
