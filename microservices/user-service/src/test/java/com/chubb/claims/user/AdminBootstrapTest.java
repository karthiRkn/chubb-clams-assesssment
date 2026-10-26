package com.chubb.claims.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chubb.claims.common.security.Role;
import org.junit.jupiter.api.Test;

/** The first administrator comes from the environment, is created once, and is never overwritten. */
class AdminBootstrapTest {

    private final UserService service = mock(UserService.class);
    private final UserRepository users = mock(UserRepository.class);

    private AdminBootstrap bootstrap(String username, String password) {
        return new AdminBootstrap(service, users, username, password);
    }

    @Test
    void nothingConfiguredCreatesNothing() {
        bootstrap("", "").run(null);
        bootstrap(null, null).run(null);
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void aConfiguredAdministratorIsCreatedWithTheAdminRole() {
        bootstrap("root.admin", "a-long-enough-password").run(null);
        verify(service).create("root.admin", "a-long-enough-password", Role.ADMIN);
    }

    @Test
    void anExistingAdministratorIsLeftAloneSoThePasswordIsNeverResetFromTheEnvironment() {
        when(users.existsByUsername("root.admin")).thenReturn(true);
        bootstrap("root.admin", "a-different-password-now").run(null);
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void halfAConfigurationOrAWeakPasswordStopsTheServiceInsteadOfStartingWithoutAnAdminOrWithAWeakOne() {
        assertThatThrownBy(() -> bootstrap("root.admin", "").run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bootstrap("", "a-long-enough-password").run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bootstrap("root.admin", "short").run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bootstrap("Root Admin", "a-long-enough-password").run(null)).isInstanceOf(IllegalStateException.class);
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void theErrorMessageNeverRepeatsThePassword() {
        assertThatThrownBy(() -> bootstrap("Bad Name", "secret-pass-1").run(null))
                .hasMessageNotContaining("secret-pass-1");
    }
}
