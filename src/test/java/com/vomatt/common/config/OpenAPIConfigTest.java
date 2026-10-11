package com.vomatt.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAPIConfigTest {

    private static final String DEV_URL = "https://dev-api.vomatt.com";
    private static final String PROD_URL = "https://api.vomatt.com";

    private OpenAPI build(String profile) {
        OpenAPIConfig config = new OpenAPIConfig();
        ReflectionTestUtils.setField(config, "devUrl", DEV_URL);
        ReflectionTestUtils.setField(config, "prodUrl", PROD_URL);
        return config.vomattOpenAPI(profile);
    }

    @Test
    @DisplayName("should list Development first when profile is dev")
    void shouldListDevFirstWhenProfileIsDev() {
        List<String> urls = build("dev").getServers().stream().map(Server::getUrl).toList();

        assertEquals(List.of(DEV_URL, PROD_URL), urls);
    }

    @Test
    @DisplayName("should list Production first when profile is not dev")
    void shouldListProdFirstWhenProfileIsNotDev() {
        List<String> urls = build("prod").getServers().stream().map(Server::getUrl).toList();

        assertEquals(List.of(PROD_URL, DEV_URL), urls);
    }

    @Test
    @DisplayName("should register shared error schemas and examples")
    void shouldRegisterErrorSchemaAndExamples() {
        var components = build("dev").getComponents();

        assertTrue(components.getSchemas().get(OpenAPIConfig.ERROR_SCHEMA).getProperties()
                .keySet().containsAll(List.of("success", "data", "message", "errorCode", "error")));
        assertTrue(components.getExamples().keySet().containsAll(List.of(
                OpenAPIConfig.EX_VALIDATION, OpenAPIConfig.EX_UNAUTHORIZED, OpenAPIConfig.EX_FORBIDDEN,
                OpenAPIConfig.EX_NOT_FOUND, OpenAPIConfig.EX_RATE_LIMITED)));
        assertTrue(components.getSecuritySchemes().containsKey("bearerAuth"));
    }

    @Test
    @DisplayName("should set info.description pointing to the frontend docs")
    void shouldSetInfoDescription() {
        String description = build("dev").getInfo().getDescription();

        assertTrue(description.contains("errorCode"));
        assertTrue(description.contains("docs/frontend/README.md"));
    }
}
