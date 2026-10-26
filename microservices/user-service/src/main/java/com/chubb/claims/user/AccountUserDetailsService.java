package com.chubb.claims.user;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives Spring Security's password check the stored hash, role and active flag of a user; used only by login.
 *
 * <p>A deactivated account is refused AFTER its password has been checked: it is reported as "credentials expired", which
 * Spring's provider tests once the password hash has been compared, whereas "disabled" is tested before and would answer
 * measurably faster than a wrong password, telling a caller that the account exists and is switched off. This way an unknown
 * user, a wrong password and a deactivated account all cost one BCrypt comparison and all get the same 401 INVALID_CREDENTIALS.
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AccountUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username)
                .map(u -> User.withUsername(u.getUsername()).password(u.getPasswordHash()).roles(u.getRole().name())
                        .credentialsExpired(!u.isActive()).build())
                .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    }
}
