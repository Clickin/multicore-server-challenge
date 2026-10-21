package com.example.feed;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HikariCP over pgjdbc. Every stack reads the same DATABASE_URL (postgres://user:pass@host:port/db);
 * JDBC wants jdbc:postgresql://host:port/db plus separate credentials, so it is translated here.
 *
 * Pool size = env DB_POOL if set, else min(128, 8 x usable cores): one JVM process, so this is the
 * app's whole Postgres connection count (<= 128). Each connection serves only ~600-1600 req/s (network
 * round trip to Postgres), so 1M users (~42k req/s) needs more than 64. Runtime.availableProcessors() honours the CPU affinity
 * mask (sched_getaffinity) that systemd's CPUAffinity/AllowedCPUs sets, so it is the cores we were given.
 * Other pool settings (spring.datasource.hikari.*, e.g. the 5 s acquire timeout) are bound onto the
 * bean as usual.
 */
@Configuration(proxyBeanMethods = false)
class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(@Value("${DATABASE_URL}") String databaseUrl,
            @Value("${DB_POOL:0}") int dbPool) {
        URI uri = URI.create(databaseUrl);
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() == -1 ? 5432 : uri.getPort())
                + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String username = null;
        String password = null;
        if (uri.getRawUserInfo() != null) {
            String[] parts = uri.getRawUserInfo().split(":", 2);
            username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            password = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : null;
        }
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .build();
        int cores = Runtime.getRuntime().availableProcessors();
        ds.setMaximumPoolSize(dbPool > 0 ? dbPool : Math.min(128, 8 * cores));
        return ds;
    }
}
