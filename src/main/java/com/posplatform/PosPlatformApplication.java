package com.posplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * POS Platform backend. Modular monolith; see docs/architecture.
 */
@SpringBootApplication
public class PosPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosPlatformApplication.class, args);
    }
}
