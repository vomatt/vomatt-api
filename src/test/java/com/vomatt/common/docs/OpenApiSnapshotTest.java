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

    /** Real filter chain: anyone may POST a contact message; reading them takes an admin. */
    @Test
    void shouldLetSignedOutVisitorsPostContactMessagesOnly() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        // Reaches the controller: 400 validation, not the 401 entry point
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/contact")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/contact")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/contact-messages")).andExpect(status().isUnauthorized());
    }

    /** Real filter chain: the payment provider's webhook needs no token, starting a checkout does. */
    @Test
    void shouldLetSignedOutCallersReachBillingWebhookOnly() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/billing/webhook"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/billing/checkout"))
                .andExpect(status().isUnauthorized());
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
        // Passkey sign-in is public: without a token the request reaches the controller (400 validation, or 503 for
        // options as this context has no Redis) instead of the 401 entry point
        for (String path : java.util.List.of("/api/auth/passkey/login/options", "/api/auth/passkey/login/verify",
                "/api/auth/admin/passkey/login/verify")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                            .as(path).isNotIn(401, 403));
        }
        // Passkey registration is for signed-in users only
        for (String path : java.util.List.of("/api/auth/passkey/register/options", "/api/auth/passkey/register/verify")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/users/me/passkeys")).andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/users/me/passkeys/" + java.util.UUID.randomUUID())).andExpect(status().isUnauthorized());
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

    @Test
    void shouldLetOwnerDeleteLockedCommunityAccountWhenActingAsIt(
            @Autowired com.vomatt.repository.UserRepository users,
            @Autowired com.vomatt.repository.CommunityMemberRepository members,
            @Autowired com.vomatt.common.security.JwtUtil jwtUtil) throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        var owner = users.save(com.vomatt.entity.User.builder().username("owner_" + suffix).email(suffix + "@test.local").build());
        var community = com.vomatt.entity.User.builder().username("locked_" + suffix).displayName("Locked")
                .roles(new String[] { com.vomatt.common.constant.UserRole.COMMUNITY }).build();
        community.setLockedAt(java.time.OffsetDateTime.now());
        community = users.save(community);
        members.save(new com.vomatt.entity.CommunityMember(community.getId(), owner.getId(), com.vomatt.entity.CommunityRole.OWNER));
        String bearer = "Bearer " + jwtUtil.generateToken(owner.getId().toString(), java.util.List.of("user"), false, "s-" + suffix);
        String path = "/api/communities/" + community.getId();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path)
                        .header("Authorization", bearer).header("X-Act-As", community.getId().toString())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"displayName\":\"Renamed\"}"))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.containsString("community.locked")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                        .header("Authorization", bearer).header("X-Act-As", community.getId().toString()))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(users.existsById(community.getId())).isFalse();
        org.assertj.core.api.Assertions.assertThat(users.existsById(owner.getId())).isTrue();
    }

    @Test
    void shouldNeverChangeCommunityUsernameThroughEitherProfileRoute(
            @Autowired com.vomatt.repository.UserRepository users,
            @Autowired com.vomatt.repository.CommunityMemberRepository members,
            @Autowired com.vomatt.common.security.JwtUtil jwtUtil,
            @Autowired com.vomatt.common.audit.AuditLogRepository audits) throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        var owner = users.save(com.vomatt.entity.User.builder().username("owner_" + suffix).email(suffix + "@test.local").build());
        var community = users.save(com.vomatt.entity.User.builder().username("club_" + suffix).displayName("Club")
                .roles(new String[] { com.vomatt.common.constant.UserRole.COMMUNITY }).build());
        members.save(new com.vomatt.entity.CommunityMember(community.getId(), owner.getId(), com.vomatt.entity.CommunityRole.OWNER));
        String bearer = "Bearer " + jwtUtil.generateToken(owner.getId().toString(), java.util.List.of("user"), false, "s-" + suffix);
        var patch = (java.util.function.BiFunction<String, String, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>)
                (path, body) -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path)
                        .header("Authorization", bearer).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);

        mockMvc.perform(patch.apply("/api/users/me", "{\"username\":\"renamed_" + suffix + "\"}")
                        .header("X-Act-As", community.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode")
                        .value("community.username_immutable"));
        mockMvc.perform(patch.apply("/api/users/me", "{\"username\":\"club_" + suffix + "\",\"displayName\":\"Club A\"}")
                        .header("X-Act-As", community.getId().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(patch.apply("/api/communities/" + community.getId(), "{\"username\":\"renamed_" + suffix + "\",\"displayName\":\"Club B\"}"))
                .andExpect(status().isOk());

        var stored = users.findById(community.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getUsername()).isEqualTo("club_" + suffix);
        org.assertj.core.api.Assertions.assertThat(stored.getDisplayName()).isEqualTo("Club B");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(audits.findAll()).filteredOn(a -> community.getId().toString().equals(a.getResourceId())
                                && "UPDATE".equals(a.getAction()) && a.isSuccess())
                        .extracting(com.vomatt.common.audit.AuditLog::getUserId, com.vomatt.common.audit.AuditLog::getResourceType, com.vomatt.common.audit.AuditLog::getDetails)
                        .containsExactlyInAnyOrder(
                                org.assertj.core.groups.Tuple.tuple(owner.getId().toString(), "COMMUNITY", "actingAs=" + community.getId() + "; displayName: \"Club\" -> \"Club A\""),
                                org.assertj.core.groups.Tuple.tuple(owner.getId().toString(), "COMMUNITY", "displayName: \"Club A\" -> \"Club B\"")));
    }

    /** ORDER_MAP_ENTRIES_BY_KEYS ignores ObjectNode, so rebuild the tree through a sorted Map. */
    private static Object sorted(JsonMapper mapper, JsonNode tree) {
        return mapper.convertValue(tree, Object.class);
    }
}
