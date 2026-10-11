package com.vomatt.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    // Connect timeout: connecting to a half-open OAuth upstream (Google/LINE/Apple) must not block forever
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // Read timeout: when an upstream hangs, synchronous calls need an upper bound so they do not exhaust threads/connection pool (Hikari)
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient restClient() {
        // Configure timeouts via Spring Framework core's ClientHttpRequestFactory (stable across Boot versions;
        // does not depend on org.springframework.boot.http.client, which is no longer at this coordinate in Boot 4.0).
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
