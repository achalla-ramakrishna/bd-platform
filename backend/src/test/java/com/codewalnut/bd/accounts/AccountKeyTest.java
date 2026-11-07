package com.codewalnut.bd.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountKeyTest {

    @Test
    void domainsAreNormalised() {
        assertThat(AccountKey.normaliseDomain("https://www.Example-GCC.test/careers?x=1")).isEqualTo("example-gcc.test");
        assertThat(AccountKey.normaliseDomain(" example-gcc.test ")).isEqualTo("example-gcc.test");
    }

    @Test
    void keysIgnoreCaseOfCity() {
        assertThat(new AccountKey("example-gcc.test", "Bengaluru")).isEqualTo(new AccountKey("EXAMPLE-GCC.test", " bengaluru"));
    }

    @Test
    void invalidDomainsAreDetected() {
        assertThat(AccountKey.isValidDomain("example-gcc.test")).isTrue();
        assertThat(AccountKey.isValidDomain("not a domain")).isFalse();
        assertThat(AccountKey.isValidDomain("localhost")).isFalse();
    }
}
