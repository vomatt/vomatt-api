package com.vomatt.vomattapi;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.vomatt")
@EntityScan(basePackages = "com.vomatt")
@EnableJpaRepositories(basePackages = "com.vomatt")
@EnableAsync
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class VomattApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(VomattApiApplication.class, args);
    }

}
