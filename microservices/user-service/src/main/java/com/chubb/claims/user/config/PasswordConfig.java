package com.chubb.claims.user.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /** BCrypt with a per-password random salt; the cost factor is configuration so tests can lower it. */
    @Bean
    public PasswordEncoder passwordEncoder(@Value("${claims.auth.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
