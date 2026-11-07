package com.codewalnut.bd;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** ADR-0003: module boundaries are checked on every build. */
class ModularityTests {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(BdPlatformApplication.class).verify();
    }
}
