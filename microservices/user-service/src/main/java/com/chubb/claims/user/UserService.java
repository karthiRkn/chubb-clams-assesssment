package com.chubb.claims.user;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import com.chubb.claims.user.UserDtos.LoginRequest;
import com.chubb.claims.user.UserDtos.LoginResponse;
import com.chubb.claims.user.UserDtos.PageResponse;
import com.chubb.claims.user.UserDtos.UserResponse;
import com.chubb.claims.user.UserDtos.UserView;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login and user management. Passwords are stored only as BCrypt hashes. Login uses Spring Security's
 * {@link DaoAuthenticationProvider}, which is designed to spend the same time on an unknown username as on a wrong
 * password, and the two give the same answer (both are 401 INVALID_CREDENTIALS).
 */
@Service
public class UserService {

    /** BCrypt only reads the first 72 bytes; a longer password would be silently cut, so it is refused instead. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtUtil jwt;
    private final AuthenticationProvider authenticator;

    public UserService(UserRepository users, PasswordEncoder encoder, JwtUtil jwt, AccountUserDetailsService details) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(details);
        provider.setPasswordEncoder(encoder);
        this.authenticator = provider;
    }

    /** Public self-registration always creates a CLAIMANT; any other role is given by an administrator. */
    @Transactional
    public UserResponse register(String username, String password) {
        return create(username, password, Role.CLAIMANT);
    }

    @Transactional
    public UserResponse create(String username, String password, Role role) {
        requireAssignable(role);
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "The password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (users.existsByUsername(username)) {
            throw usernameTaken(username);
        }
        try {
            return UserResponse.from(users.saveAndFlush(new UserAccount(username, encoder.encode(password), role)));
        } catch (DataIntegrityViolationException e) {
            // two requests for the same new username at once: the unique constraint decides, the loser gets the same 409
            throw usernameTaken(username);
        }
    }

    public LoginResponse login(LoginRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        Authentication result;
        try {
            result = authenticator.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, request.password()));
        } catch (AuthenticationException e) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
        }
        Role role = result.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .map(a -> Role.valueOf(a.substring("ROLE_".length()))).findFirst()
                .orElseThrow(() -> ApiException.of(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password"));
        return new LoginResponse(jwt.generate(username, role), "Bearer", jwt.expirySeconds(), username, role);
    }

    @Transactional(readOnly = true)
    public UserResponse get(String username) {
        return UserResponse.from(users.findByUsername(username).orElseThrow(() -> notFound(username)));
    }

    /** Ordered by username, so paging is stable. Filtering and paging happen in SQL. */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(int page, int size) {
        return PageResponse.from(users.findAll(PageRequest.of(page, size, Sort.by("username"))).map(UserResponse::from));
    }

    /**
     * Changes a user's role. An administrator cannot change their own, so the system is never left without one by a
     * slip. A token already issued keeps the old role until it expires (there is no revocation, see DESIGN_DECISIONS.md).
     */
    @Transactional
    public UserResponse changeRole(AuthenticatedUser caller, String username, Role role) {
        requireAssignable(role);
        if (caller.username().equals(username)) {
            throw ApiException.conflict("CANNOT_CHANGE_OWN_ROLE", "You cannot change your own role");
        }
        UserAccount user = users.findByUsername(username).orElseThrow(() -> notFound(username));
        user.changeRole(role);
        return UserResponse.from(users.saveAndFlush(user));
    }

    /**
     * Activates or deactivates an account. A deactivated person cannot log in again; a token they already hold works
     * until it expires. An administrator cannot deactivate themselves, so the system is never left without a way in by a slip.
     */
    @Transactional
    public UserResponse setActive(AuthenticatedUser caller, String username, boolean active) {
        if (!active && caller.username().equals(username)) {
            throw ApiException.conflict("CANNOT_DEACTIVATE_SELF", "You cannot deactivate your own account");
        }
        UserAccount user = users.findByUsername(username).orElseThrow(() -> notFound(username));
        user.setActive(active);
        return UserResponse.from(users.saveAndFlush(user));
    }

    /** For other services (through the SERVICE-only internal route): the role and active flag, never anything secret. */
    @Transactional(readOnly = true)
    public UserView view(String username) {
        return UserView.from(users.findByUsername(username).orElseThrow(() -> notFound(username)));
    }

    private static void requireAssignable(Role role) {
        if (!role.isUserRole()) {
            throw ApiException.badRequest("INVALID_ROLE", role + " is not a role a user can have");
        }
    }

    private static ApiException usernameTaken(String username) {
        return ApiException.conflict("USERNAME_TAKEN", "The username " + username + " is already taken");
    }

    private static ApiException notFound(String username) {
        return ApiException.notFound("USER_NOT_FOUND", "User " + username + " was not found");
    }
}
