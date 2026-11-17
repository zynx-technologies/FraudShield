package com.fraudshield.strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.fraudshield")
public class StrategyApplication {
    public static void main(String[] args) {
        SpringApplication.run(StrategyApplication.class, args);
    }
}