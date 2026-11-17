package com.fraudshield.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.fraudshield")
@EnableScheduling
public class DecisionApplication {
    public static void main(String[] args) {
        SpringApplication.run(DecisionApplication.class, args);
    }
}