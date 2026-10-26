package com.chubb.claims.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** User Service. Entities and repositories are scanned from this package only, so it validates only its own table. */
@SpringBootApplication(scanBasePackages = {"com.chubb.claims.user", "com.chubb.claims.common"})
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
