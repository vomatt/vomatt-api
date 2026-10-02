package com.vomatt.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class OpenAPIConfig {

    @Value("${app.openapi.dev-url}")
    private String devUrl;

    @Value("${app.openapi.prod-url}")
    private String prodUrl;

    @Bean
    public OpenAPI vomattOpenAPI(@Value("${spring.profiles.active:dev}") String activeProfile) {
        Server devServer = new Server();
        devServer.setUrl(devUrl);
        devServer.setDescription("Development Environment Server");

        Server prodServer = new Server();
        prodServer.setUrl(prodUrl);
        prodServer.setDescription("Production Environment Server");

        Contact contact = new Contact();
        contact.setName("Vomatt");
        contact.setEmail("yi-hsien@vomatt.com");

        License mitLicense = new License()
                .name("MIT License")
                .url("https://opensource.org/licenses/MIT");

        Info info = new Info()
                .title("Vomatt REST API")
                .description("Vomatt API")
                .version("1.0")
                .contact(contact)
                .license(mitLicense);

        // Define JWT security scheme
        SecurityScheme securityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        SecurityRequirement securityRequirement = new SecurityRequirement().addList("bearerAuth");

        List<Server> servers = "dev".equals(activeProfile)
                ? List.of(new Server().url(devUrl).description("Development"),
                new Server().url(prodUrl).description("Production"))
                : List.of(new Server().url(prodUrl).description("Production"),
                        new Server().url(devUrl).description("Development"));

        return new OpenAPI()
                .components(new Components().addSecuritySchemes("bearerAuth", securityScheme))
                .security(Arrays.asList(securityRequirement))
                .info(info)
                .servers(servers);
    }
}