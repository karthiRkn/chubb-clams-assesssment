package com.chubb.claims.assessment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Assessment Service. Entities and repositories are scanned from this package only. */
@SpringBootApplication(scanBasePackages = {"com.chubb.claims.assessment", "com.chubb.claims.common"})
public class AssessmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssessmentServiceApplication.class, args);
    }
}
