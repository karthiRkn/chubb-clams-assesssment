package com.chubb.claims.claim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Claim Service. Entities and repositories are scanned from this package only, so it validates only its own tables. */
@SpringBootApplication(scanBasePackages = {"com.chubb.claims.claim", "com.chubb.claims.common"})
public class ClaimServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimServiceApplication.class, args);
    }
}
