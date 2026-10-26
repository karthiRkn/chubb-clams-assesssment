package com.chubb.claims.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chubb.claims.common.security.Role;
import org.junit.jupiter.api.Test;

/** The switch, the password rule and "create only what is missing", without starting Spring. */
class DemoUsersUnitTest {

    private final UserService service = mock(UserService.class);
    private final UserRepository users = mock(UserRepository.class);

    private DemoUsers demo(boolean enabled, String password) {
        return new DemoUsers(service, users, enabled, password);
    }

    @Test
    void whenSwitchedOffNothingIsCreatedAndNothingIsLookedUp() {
        demo(false, "a-long-enough-password").run(null);
        verify(service, never()).create(anyString(), anyString(), any());
        verify(users, never()).existsByUsername(anyString());
    }

    @Test
    void aMissingOrShortPasswordStopsStartUpWhenEnabledAndNeverAppearsInTheMessage() {
        assertThatThrownBy(() -> demo(true, "short").run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("short ");
        assertThatThrownBy(() -> demo(true, null).run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> demo(true, "").run(null)).isInstanceOf(IllegalStateException.class);
        verify(service, never()).create(anyString(), anyString(), any());
    }

    @Test
    void oneUserPerRoleIsCreatedWithTheConfiguredPassword() {
        demo(true, "a-long-enough-password").run(null);
        verify(service).create("admin1", "a-long-enough-password", Role.ADMIN);
        verify(service).create("officer1", "a-long-enough-password", Role.CLAIMS_OFFICER);
        verify(service).create("manager1", "a-long-enough-password", Role.MANAGER);
        verify(service).create("claimant1", "a-long-enough-password", Role.CLAIMANT);
    }

    @Test
    void aUserThatAlreadyExistsIsLeftAloneAndTheRestAreStillCreated() {
        when(users.existsByUsername("officer1")).thenReturn(true);
        demo(true, "a-long-enough-password").run(null);
        verify(service, never()).create(org.mockito.ArgumentMatchers.eq("officer1"), anyString(), any());
        verify(service, times(3)).create(anyString(), anyString(), any());
    }

    @Test
    void everyDemoRoleIsAPersonRoleNeverTheServiceRole() {
        assertThat(DemoUsers.USERS.values()).doesNotContain(Role.SERVICE);
        assertThat(DemoUsers.USERS.values()).containsExactlyInAnyOrder(Role.ADMIN, Role.CLAIMS_OFFICER, Role.MANAGER, Role.CLAIMANT);
        assertThat(DemoUsers.USERS.keySet()).allMatch(name -> name.matches(UserDtos.USERNAME_PATTERN));
    }
}
