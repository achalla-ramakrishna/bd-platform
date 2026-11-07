package com.codewalnut.bd.shared;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Lets a Railway deployment (ADR-0007) set the database with one variable: Railway's
 * {@code MYSQL_URL} ({@code mysql://user:password@host:port/database}) becomes the datasource URL,
 * username and password. {@code DATABASE_URL} (JDBC form), when set, always wins.
 */
public class RailwayEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "railwayMysqlUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (StringUtils.hasText(environment.getProperty("DATABASE_URL"))) {
            return;
        }
        String mysqlUrl = environment.getProperty("MYSQL_URL");
        if (StringUtils.hasText(mysqlUrl)) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, toDatasourceProperties(mysqlUrl)));
        }
    }

    static Map<String, Object> toDatasourceProperties(String mysqlUrl) {
        URI uri = URI.create(mysqlUrl.trim());
        if (!"mysql".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException("MYSQL_URL must look like mysql://user:password@host:port/database");
        }
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (database.isEmpty()) {
            throw new IllegalStateException("MYSQL_URL must include a database name");
        }
        int port = uri.getPort() == -1 ? 3306 : uri.getPort();
        Map<String, Object> props = new HashMap<>();
        // Railway's MySQL is reached over its private network without TLS.
        props.put("spring.datasource.url", "jdbc:mysql://" + uri.getHost() + ":" + port + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            props.put("spring.datasource.username", URLDecoder.decode(user, StandardCharsets.UTF_8));
            if (colon >= 0) {
                props.put("spring.datasource.password",
                    URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8));
            }
        }
        return props;
    }
}
