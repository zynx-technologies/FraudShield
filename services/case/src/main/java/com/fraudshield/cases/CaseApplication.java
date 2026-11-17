package com.fraudshield.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.fraudshield")
public class CaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(CaseApplication.class, args);
    }
}