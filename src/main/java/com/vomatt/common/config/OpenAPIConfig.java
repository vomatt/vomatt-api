package com.vomatt.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class OpenAPIConfig {

    /** Component names referenced by {@code @CommonApiResponses} / {@code @PublicApiResponse}. */
    public static final String ERROR_SCHEMA = "ErrorResponse";
    public static final String EX_VALIDATION = "ValidationError";
    public static final String EX_UNAUTHORIZED = "Unauthorized";
    public static final String EX_FORBIDDEN = "Forbidden";
    public static final String EX_NOT_FOUND = "NotFound";
    public static final String EX_RATE_LIMITED = "RateLimited";

    private static final String API_DESCRIPTION = """
            REST API of Vomatt, a social polling platform.

            **Envelope**: every response is `{ success, data, message, errorCode, error }`. \
            On success `data` holds the payload; on failure `success=false` and `data` is normally null.

            **Auth**: `Authorization: Bearer <access token>` (JWT). Obtain tokens via `/api/auth/**`; \
            401 `auth.token.expired` means refresh, `auth.token.invalid` means sign in again.

            **Errors**: branch on `errorCode` (lowercase dotted, e.g. `vote.not_found`), never on `message`. \
            Filter-level 401/403/429 use the same body. 429 carries a `Retry-After` header (seconds).

            **Pagination**: list endpoints return `{ items, nextCursor }`; pass `nextCursor` back as `cursor`. \
            `limit` defaults to 20 and is clamped to 1..50.

            **i18n**: send `Accept-Language` (`zh-TW` default, `zh-CN`, `en`) to localize `message`.

            Conventions, flows and glossary: `docs/frontend/README.md` in the repository.""";

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
                .description(API_DESCRIPTION)
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
                .components(errorComponents(new Components().addSecuritySchemes("bearerAuth", securityScheme)))
                .security(Arrays.asList(securityRequirement))
                .info(info)
                .servers(servers);
    }

    private Components errorComponents(Components components) {
        Map<String, Schema> props = new LinkedHashMap<>();
        props.put("success", new Schema<Boolean>().type("boolean").example(false)
                .description("Always false for errors"));
        props.put("data", new Schema<>().nullable(true).description("Normally null; some errors carry structured data"));
        props.put("message", new Schema<String>().type("string").description("Localized message (Accept-Language)"));
        props.put("errorCode", new Schema<String>().type("string")
                .description("Stable machine-readable code, e.g. `vote.not_found`"));
        props.put("error", new Schema<String>().type("string").description("Same text as `message`"));
        components.addSchemas(ERROR_SCHEMA, new Schema<>().type("object")
                .description("Error envelope returned by every failing endpoint and filter").properties(props));
        components.addExamples(EX_VALIDATION, errorExample("field: must not be blank", "common.validation_failed"));
        components.addExamples(EX_UNAUTHORIZED, errorExample("Authentication required", "common.unauthorized"));
        components.addExamples(EX_FORBIDDEN, errorExample("Access denied", "common.forbidden"));
        components.addExamples(EX_NOT_FOUND, errorExample("Poll not found", "vote.not_found"));
        components.addExamples(EX_RATE_LIMITED, errorExample("Too many requests", "common.rate_limited"));
        return components;
    }

    private Example errorExample(String message, String errorCode) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("success", false);
        value.put("data", null);
        value.put("message", message);
        value.put("errorCode", errorCode);
        value.put("error", message);
        return new Example().value(value);
    }
}
