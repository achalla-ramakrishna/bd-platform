package com.codewalnut.bd.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** ADR-0004: only verified accounts in the Workspace domain may sign in. */
class WorkspaceOidcUserServiceTest {

    private final SecurityProperties props = new SecurityProperties("example-gcc.test", List.of(), false, null, false);

    @Test
    void acceptsVerifiedWorkspaceAccount() {
        assertThat(WorkspaceOidcUserService.isAllowed(props, "example-gcc.test", "a.person@example-gcc.test", true)).isTrue();
    }

    @Test
    void rejectsPersonalGmail() {
        assertThat(WorkspaceOidcUserService.isAllowed(props, null, "someone@gmail.com", true)).isFalse();
    }

    @Test
    void rejectsOtherWorkspace() {
        assertThat(WorkspaceOidcUserService.isAllowed(props, "other.test", "a@other.test", true)).isFalse();
    }

    @Test
    void rejectsMismatchedEmailDomain() {
        assertThat(WorkspaceOidcUserService.isAllowed(props, "example-gcc.test", "a@other.test", true)).isFalse();
    }

    @Test
    void rejectsUnverifiedEmail() {
        assertThat(WorkspaceOidcUserService.isAllowed(props, "example-gcc.test", "a@example-gcc.test", false)).isFalse();
    }

    @Test
    void previewRefusesToStartWithoutALongAccessCode() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new SecurityProperties("example-gcc.test", List.of(), true, "short", true))
            .hasMessageContaining("PREVIEW_ACCESS_CODE");
        assertThat(new SecurityProperties("example-gcc.test", List.of(), true, "a-long-access-code", true).preview()).isTrue();
    }

    @Test
    void derivesNameFromEmail() {
        assertThat(UserDirectory.nameFromEmail("priya.k-rao@example-gcc.test")).isEqualTo("Priya K Rao");
    }
}
