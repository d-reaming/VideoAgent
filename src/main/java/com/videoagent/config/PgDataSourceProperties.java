package com.videoagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL 向量库独立数据源配置（业务库在 MySQL，向量库在 PG）。
 */
@ConfigurationProperties(prefix = "video-agent.pg.datasource")
public record PgDataSourceProperties(String url, String username, String password, String driverClassName) {
}
