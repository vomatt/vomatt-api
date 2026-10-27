package com.vomatt.common.docs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.vomatt.vomattapi.VomattApiApplication;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract snapshot: {@code /v3/api-docs} must equal the committed {@code docs/frontend/openapi.json}.
 * Regenerate with {@code ./mvnw test -Dtest=OpenApiSnapshotTest -Dsnapshot.update=true}.
 */
@SpringBootTest(classes = VomattApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "springdoc.api-docs.enabled=true",
        "spring.profiles.active=dev",
        "app.openapi.dev-url=http://localhost:8080",
        "app.openapi.prod-url=https://api.example.com",
        "jwt.secret=openapi-snapshot-test-secret-0123456789-0123456789",
        "app.key=openapi-snapshot-test-app-key-0123456789-0123456789",
        // No Redis here, and /api/auth/** fails closed (429) without it; the 401 checks need the request to reach auth
        "rate-limit.enabled=false"
})
class OpenApiSnapshotTest {

    static final Path SNAPSHOT = Path.of("docs/frontend/openapi.json");

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @Autowired
    WebApplicationContext context;

    @Test
    void shouldMatchCommittedSnapshotWhenContractUnchanged() throws Exception {
        // spring-boot-webmvc-test is not on the classpath, so build MockMvc by hand
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Sorted keys + fixed server URLs (set above) keep the output independent of the environment
        JsonMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        JsonNode tree = mapper.readTree(body);
        SnapshotSupport.verify(SNAPSHOT, mapper.writeValueAsString(sorted(mapper, tree)),
                "./mvnw test -Dtest=OpenApiSnapshotTest -Dsnapshot.update=true");
    }

    /** Real filter chain: the follow lists need a token, the profile does not. */
    @Test
    void shouldReturn401WhenReadingFollowListsSignedOut() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        for (String path : java.util.List.of("/api/users/alice/followers", "/api/users/alice/following",
                "/api/users/alice/mutual-followers", "/api/users/me/follows",
                "/api/users/search")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/users/nobody-here")).andExpect(status().isNotFound());
        // Passkey registration is for signed-in users only
        for (String path : java.util.List.of("/api/auth/passkey/register/options", "/api/auth/passkey/register/verify")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Autowired
    tools.jackson.databind.ObjectMapper appMapper;

    /** Uses the app's own mapper (HTTP bodies and Redis values), since the contract relies on its defaults. */
    @Test
    void shouldAcceptPayloadsFromBeforeAdminSplitWhenDeserializedByAppMapper() {
        // Old clients may still send checkRole; it must be ignored, not rejected
        var otp = appMapper.readValue("{\"email\":\"a@b.c\",\"checkRole\":\"false\"}",
                com.vomatt.auth.dto.SendOtpRequest.class);
        org.assertj.core.api.Assertions.assertThat(otp.email()).isEqualTo("a@b.c");
        // Refresh tokens stored before adminScope existed must still rotate, as consumer sessions
        var data = appMapper.convertValue(java.util.Map.of("userId", "u", "email", "e", "roles", java.util.List.of("user"),
                "sessionId", "s"), com.vomatt.common.security.RefreshTokenService.RefreshTokenData.class);
        org.assertj.core.api.Assertions.assertThat(data.grantsAdmin()).isFalse();
        // A browser's credential.toJSON() carries fields the API does not read; they must be ignored, not rejected
        var passkey = appMapper.readValue("""
                {"credential": {"id": "a", "rawId": "a", "type": "public-key", "authenticatorAttachment": "platform",
                  "clientExtensionResults": {"credProps": {"rk": true}},
                  "response": {"clientDataJSON": "b", "attestationObject": "c", "transports": ["internal"],
                    "authenticatorData": "d", "publicKey": "e", "publicKeyAlgorithm": -7}}}""",
                com.vomatt.auth.dto.PasskeyRegistrationVerifyRequest.class);
        org.assertj.core.api.Assertions.assertThat(passkey.credential().response().attestationObject()).isEqualTo("c");
    }

    /** ORDER_MAP_ENTRIES_BY_KEYS ignores ObjectNode, so rebuild the tree through a sorted Map. */
    private static Object sorted(JsonMapper mapper, JsonNode tree) {
        return mapper.convertValue(tree, Object.class);
    }
}
