package com.oneil.legacy;

import com.oneil.legacy.scan.ScanProperties;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresTestSupport {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static { POSTGRES.start(); }

    @Autowired protected ScanProperties scanProperties;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    protected void configureRepository(Path root) {
        Path physical = root.toAbsolutePath().normalize();
        scanProperties.setRepositoryRoot(physical.toString());
        scanProperties.setRepositoryName(physical.getFileName().toString());
        scanProperties.setRepositoryConfigured(true);
    }
}
