package com.videoagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 数据源配置：
 * - MySQL（spring.datasource）：业务库，@Primary 供 MyBatis-Plus 使用
 * - PostgreSQL + pgvector（video-agent.pg.datasource）：向量库，供 JdbcTemplate 使用
 */
@Configuration
public class PgDataSourceConfig {

    @Bean
    @Primary
    public DataSource mysqlDataSource(DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    @Bean(name = "pgDataSource")
    public DataSource pgDataSource(PgDataSourceProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.url());
        config.setUsername(props.username());
        config.setPassword(props.password());
        config.setDriverClassName(props.driverClassName());
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource pgDataSource) {
        return new JdbcTemplate(pgDataSource);
    }
}

