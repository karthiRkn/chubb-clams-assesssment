package com.chubb.claims.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, Long> {

    /** Lookup by the PUBLIC key (never the internal numeric id). */
    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
