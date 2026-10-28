package com.chubb.claims.user;

import com.chubb.claims.common.security.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, Long> {

    /** Lookup by the PUBLIC key (never the internal numeric id). */
    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Every account with this role and active flag, by username (the filter runs in SQL; there is no paging, see the caller). */
    List<UserAccount> findByRoleAndActiveOrderByUsername(Role role, boolean active);
}
