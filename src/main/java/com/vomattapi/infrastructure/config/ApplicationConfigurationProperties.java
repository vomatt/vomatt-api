package com.vomattapi.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@ConfigurationProperties(prefix = "app")
@Component
@Data
public class ApplicationConfigurationProperties {
    
    private String name = "Vomatt Api";
    private String version = "1.0.0";
    private String description = "Vomatt Api API";
    private String baseUrl = "http://localhost:8080";
    
    private Email email = new Email();
    private Sms sms = new Sms();
    private Audit audit = new Audit();
    
    @Data
    public static class Email {
        private boolean enabled = false;
        private String from = "noreply@vomatt.com";
        private String fromName = "Vomatt Api";
        private Smtp smtp = new Smtp();
        
        @Data
        public static class Smtp {
            private String host = "localhost";
            private int port = 587;
            private String username;
            private String password;
            private boolean auth = true;
            private boolean starttls = true;
        }
    }
    
    @Data
    public static class Sms {
        private boolean enabled = false;
        private String provider = "twilio";
        private String apiKey;
        private String apiSecret;
        private String fromNumber;
    }
    
    @Data
    public static class Audit {
        private boolean enabled = true;
        private boolean logSensitiveData = false;
        private String[] excludedPaths = {"/actuator/**", "/swagger-ui/**", "/v3/api-docs/**"};
    }
}