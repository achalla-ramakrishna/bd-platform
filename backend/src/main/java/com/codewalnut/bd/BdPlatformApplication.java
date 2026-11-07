package com.codewalnut.bd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CodeWalnut BD Platform: one deployable, modular inside (ADR-0003). Each direct
 * sub-package is a module; Spring Modulith checks the boundaries in tests.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BdPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(BdPlatformApplication.class, args);
    }
}
