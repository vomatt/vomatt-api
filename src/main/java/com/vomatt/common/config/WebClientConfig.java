package com.vomatt.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    // 連線逾時：half-open 的 OAuth upstream（Google／LINE／Apple）建立連線時不應無限阻塞
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // 讀取逾時：upstream 卡住不回應時，同步呼叫須有上限，避免拖垮執行緒/連線池（Hikari）
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient restClient() {
        // 以 Spring Framework 核心的 ClientHttpRequestFactory 設定逾時（跨 Boot 版本穩定，
        // 不依賴 org.springframework.boot.http.client，後者在 Boot 4.0 已不在此座標）。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
