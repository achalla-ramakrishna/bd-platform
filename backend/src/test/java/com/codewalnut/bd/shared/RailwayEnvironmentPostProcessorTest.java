package com.codewalnut.bd.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RailwayEnvironmentPostProcessorTest {

    @Test
    void convertsRailwayMysqlUrl() {
        Map<String, Object> props = RailwayEnvironmentPostProcessor.toDatasourceProperties(
            "mysql://root:p%40ss:word@mysql.railway.internal:3306/railway");
        assertThat(props).containsEntry("spring.datasource.url",
            "jdbc:mysql://mysql.railway.internal:3306/railway?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
        assertThat(props).containsEntry("spring.datasource.username", "root");
        assertThat(props).containsEntry("spring.datasource.password", "p@ss:word");
    }

    @Test
    void databaseUrlWins() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("DATABASE_URL", "jdbc:mysql://elsewhere/db")
            .withProperty("MYSQL_URL", "mysql://u:p@h:3306/db");
        new RailwayEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getPropertySources().contains(RailwayEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
    }

    @Test
    void appliesMysqlUrlWhenNoDatabaseUrl() {
        MockEnvironment env = new MockEnvironment().withProperty("MYSQL_URL", "mysql://u:p@h:3307/db");
        new RailwayEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:mysql://h:3307/db?");
    }

    @Test
    void rejectsMalformedUrl() {
        assertThatThrownBy(() -> RailwayEnvironmentPostProcessor.toDatasourceProperties("postgres://u:p@h/db"))
            .hasMessageContaining("mysql://");
        assertThatThrownBy(() -> RailwayEnvironmentPostProcessor.toDatasourceProperties("mysql://u:p@h:3306/"))
            .hasMessageContaining("database name");
    }
}
