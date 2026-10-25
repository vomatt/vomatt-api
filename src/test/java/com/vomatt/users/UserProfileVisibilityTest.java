package com.vomatt.users;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.entity.User;
import com.vomatt.entity.UserPreference;
import com.vomatt.repository.UserPreferenceRepository;
import com.vomatt.repository.UserProfileProjection;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.dto.MyProfileResponse;
import com.vomatt.users.dto.UserProfileResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("User Profile Visibility Settings")
class UserProfileVisibilityTest {

    @Mock
    UserRepository userRepository;

    @Mock
    UserPreferenceRepository preferenceRepository;

    @Mock
    UserMapper userMapper;

    @Mock
    RefreshTokenService refreshTokenService;

    @InjectMocks
    UserService userService;

    // ─── helper ───────────────────────────────────────────────────────────────

    private static final UUID USER_UUID = UUID.randomUUID();
    private static final String USER_ID = USER_UUID.toString();
    private static final String USERNAME = "testuser";
    private static final LocalDate BIRTHDAY = LocalDate.of(1995, 4, 12);
    private static final OffsetDateTime JOINED_AT = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private User createTestUser() {
        return User.builder()
                .id(USER_UUID)
                .username(USERNAME)
                .email("test@example.com")
                .phoneNumber("0912345678")
                .firstName("Test")
                .lastName("User")
                .birthday(BIRTHDAY)
                .displayName("TestDisplay")
                .bio("Hello world")
                .location("Taipei")
                .points(100)
                .membershipLevel("SILVER")
                .active(true)
                .build();
    }

    private UserProfileProjection createTestProjection() {
        return createTestProjection(true);
    }

    private UserProfileProjection createTestProjection(boolean active) {
        return new UserProfileProjection() {
            @Override public UUID getId() { return USER_UUID; }
            @Override public String getUsername() { return USERNAME; }
            @Override public String getDisplayName() { return "TestDisplay"; }
            @Override public String getBio() { return "Hello world"; }
            @Override public String getAvatarUrl() { return "bot:boba"; }
            @Override public OffsetDateTime getCreatedAt() { return JOINED_AT; }
            @Override public Long getTotalPolls() { return 5L; }
            @Override public Long getTotalVotes() { return 10L; }
            @Override public Boolean getActive() { return active; }
            @Override public String getFirstName() { return "Test"; }
            @Override public String getLastName() { return "User"; }
            @Override public String getLocation() { return "Taipei"; }
            @Override public Integer getPoints() { return 100; }
            @Override public String getMembershipLevel() { return "SILVER"; }
            @Override public Boolean getFollowing() { return false; }
            @Override public Boolean getBlocked() { return false; }
        };
    }

    private UserPreference createPref(String key, String value) {
        UserPreference pref = new UserPreference();
        pref.setUser(createTestUser());
        pref.setKey(key);
        pref.setValue(value);
        return pref;
    }

    // ─── getMyProfile ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyProfile")
    class GetMyProfileTests {

        @Test
        @DisplayName("should return all fields for authenticated user")
        void shouldReturnAllFieldsForMyProfile() {
            // Given
            User user = createTestUser();
            UserProfileProjection projection = createTestProjection();
            MyProfileResponse expectedResponse = new MyProfileResponse(
                    USER_ID, USERNAME, "test@example.com", "0912345678",
                    "Test", "User", BIRTHDAY, "TestDisplay", "Hello world", "bot:boba", "Taipei",
                    100, "SILVER", true, null,
                    JOINED_AT, 5, 10,
                    Map.of("firstName", false, "lastName", false,
                           "location", false, "points", false, "membershipLevel", false),
                    false
            );

            when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(user));
            when(userRepository.findProfileByUsername(USERNAME)).thenReturn(Optional.of(projection));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(Collections.emptyList());
            when(userMapper.toMyProfileResponse(eq(user), eq(5), eq(10), anyMap(), eq(false)))
                    .thenReturn(expectedResponse);

            // When
            MyProfileResponse result = userService.getMyProfile(USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.username()).isEqualTo(USERNAME);
            assertThat(result.email()).isEqualTo("test@example.com");
            assertThat(result.firstName()).isEqualTo("Test");
            assertThat(result.totalPolls()).isEqualTo(5);
            assertThat(result.totalVotes()).isEqualTo(10);
            assertThat(result.visibilitySettings()).containsEntry("firstName", false).doesNotContainKeys("email", "birthday");
        }

        @Test
        @DisplayName("should default display name and bio to public and others to hidden when unset")
        void shouldShowDisplayNameAndBioByDefault() {
            // Given
            User user = createTestUser();
            when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(user));
            when(userRepository.findProfileByUsername(USERNAME)).thenReturn(Optional.of(createTestProjection()));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(Collections.emptyList());

            // When
            userService.getMyProfile(USER_ID);

            // Then
            verify(userMapper).toMyProfileResponse(eq(user), eq(5), eq(10), argThat(v ->
                    Boolean.TRUE.equals(v.get("displayName")) && Boolean.TRUE.equals(v.get("bio"))
                            && Boolean.FALSE.equals(v.get("firstName")) && Boolean.FALSE.equals(v.get("location"))
                            && !v.containsKey("email") && !v.containsKey("birthday")), eq(false));
        }

        @Test
        @DisplayName("should report display name as hidden after user hides it")
        void shouldHideDisplayNameWhenConfigured() {
            // Given
            User user = createTestUser();
            when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(user));
            when(userRepository.findProfileByUsername(USERNAME)).thenReturn(Optional.of(createTestProjection()));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(List.of(createPref("visibility.displayName", "false")));

            // When
            userService.getMyProfile(USER_ID);

            // Then
            verify(userMapper).toMyProfileResponse(eq(user), eq(5), eq(10),
                    argThat(v -> Boolean.FALSE.equals(v.get("displayName")) && Boolean.TRUE.equals(v.get("bio"))), eq(false));
        }
    }

    // ─── getUserProfile (public)─────────────────────────────────────────────────

    @Nested
    @DisplayName("getUserProfile - public lookup")
    class GetUserProfileTests {

        @Test
        @DisplayName("should return null when all fields are hidden")
        void shouldReturnOnlyBaseFieldsWhenAllHidden() {
            // Given
            UserProfileProjection projection = createTestProjection();
            UserProfileResponse expectedResponse = new UserProfileResponse(
                    USER_ID, USERNAME, "TestDisplay", "Hello world", "bot:boba",
                    JOINED_AT, 5, 10,
                    null, null, null, null, null, null, null
            );

            when(userRepository.findProfileForViewer(USERNAME, null)).thenReturn(Optional.of(projection));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(Collections.emptyList());
            when(userMapper.toPublicProfileResponse(eq(projection), anyMap(), isNull(), isNull()))
                    .thenReturn(expectedResponse);

            // When
            UserProfileResponse result = userService.getUserProfile(USERNAME, false, null);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.username()).isEqualTo(USERNAME);
            assertThat(result.displayName()).isEqualTo("TestDisplay");
            assertThat(result.firstName()).isNull();
            assertThat(result.lastName()).isNull();
            assertThat(result.location()).isNull();
            assertThat(result.points()).isNull();
            assertThat(result.membershipLevel()).isNull();
        }

        @Test
        @DisplayName("should return 404 when user is suspended")
        void shouldReturnNotFoundWhenUserSuspended() {
            when(userRepository.findProfileForViewer(USERNAME, null)).thenReturn(Optional.of(createTestProjection(false)));

            assertThatThrownBy(() -> userService.getUserProfile(USERNAME, false, null))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND);
                    });
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("should show location after visibility.location=true is set, ignoring stale email rows")
        void shouldReturnVisibleFieldsWhenConfigured() {
            // Given
            UserProfileProjection projection = createTestProjection();
            UserPreference locationPref = createPref("visibility.location", "true");
            UserPreference staleEmailPref = createPref("visibility.email", "true");

            UserProfileResponse expectedResponse = new UserProfileResponse(
                    USER_ID, USERNAME, "TestDisplay", "Hello world", "bot:boba",
                    JOINED_AT, 5, 10,
                    null, null, "Taipei", null, null, null, null
            );

            when(userRepository.findProfileForViewer(USERNAME, null)).thenReturn(Optional.of(projection));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(List.of(locationPref, staleEmailPref));
            when(userMapper.toPublicProfileResponse(eq(projection),
                    argThat(v -> Boolean.TRUE.equals(v.get("location")) && Boolean.FALSE.equals(v.get("firstName"))
                            && !v.containsKey("email")), isNull(), isNull()))
                    .thenReturn(expectedResponse);

            // When
            UserProfileResponse result = userService.getUserProfile(USERNAME, false, null);

            // Then
            assertThat(result.location()).isEqualTo("Taipei");
            assertThat(result.firstName()).isNull();
        }
    }

    // ─── UserMapper actual masking behavior ────────────────────────────────────────────────

    @Nested
    @DisplayName("UserMapper.toPublicProfileResponse")
    class MapperMaskingTests {

        @Test
        @DisplayName("should output only visibility=true fields and null for the rest")
        void shouldMaskHiddenFields() {
            UserProfileResponse result = new UserMapper().toPublicProfileResponse(
                    createTestProjection(), Map.of("location", true, "points", true), null, null);

            assertThat(result.location()).isEqualTo("Taipei");
            assertThat(result.points()).isEqualTo(100);
            assertThat(result.firstName()).isNull();
            assertThat(result.lastName()).isNull();
            assertThat(result.membershipLevel()).isNull();
            assertThat(result.joinedAt()).isEqualTo(JOINED_AT);
        }
    }

    @Nested
    @DisplayName("UserMapper.toPublicProfileResponse - display name and bio")
    class MapperDisplayNameBioTests {

        @Test
        @DisplayName("should return null when display name and bio are hidden")
        void shouldHideDisplayNameAndBioWhenHidden() {
            UserProfileResponse result = new UserMapper().toPublicProfileResponse(
                    createTestProjection(), Map.of("displayName", false, "bio", false), null, null);

            assertThat(result.displayName()).isNull();
            assertThat(result.bio()).isNull();
            assertThat(result.username()).isEqualTo(USERNAME);
        }

        @Test
        @DisplayName("should return original values when display name and bio are public")
        void shouldShowDisplayNameAndBioWhenVisible() {
            UserProfileResponse result = new UserMapper().toPublicProfileResponse(
                    createTestProjection(), Map.of("displayName", true, "bio", true), null, null);

            assertThat(result.displayName()).isEqualTo("TestDisplay");
            assertThat(result.bio()).isEqualTo("Hello world");
        }
    }

    // ─── updateVisibility ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateVisibility")
    class UpdateVisibilityTests {

        @Test
        @DisplayName("should update visibility settings")
        void shouldUpdateVisibilitySettings() {
            // Given
            User user = createTestUser();
            when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(user));
            when(preferenceRepository.findByUserIdAndKey(USER_UUID, "visibility.location"))
                    .thenReturn(Optional.empty());
            when(preferenceRepository.save(any(UserPreference.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // query after update returns the new settings
            UserPreference savedPref = createPref("visibility.location", "true");
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(List.of(savedPref));

            // When
            Map<String, Boolean> result = userService.updateVisibility(USER_ID, Map.of("location", true, "email", true));

            // Then: email is no longer controllable, so only the location row is written
            assertThat(result).containsEntry("location", true).doesNotContainKey("email");
            verify(preferenceRepository).save(argThat(p ->
                    "visibility.location".equals(p.getKey()) && "true".equals(p.getValue()) && p.getUser() == user));
            verify(preferenceRepository, never()).save(argThat(p -> "visibility.email".equals(p.getKey())));
        }

        @Test
        @DisplayName("should ignore unknown field names")
        void shouldIgnoreUnknownVisibilityFields() {
            // Given
            User user = createTestUser();
            when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(user));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(Collections.emptyList());

            // When
            Map<String, Boolean> result = userService.updateVisibility(
                    USER_ID, Map.of("unknownField", true, "anotherBad", false));

            // Then — unknown fields are not written
            verify(preferenceRepository, never()).save(any(UserPreference.class));
            // all known fields default to false
            assertThat(result).containsEntry("location", false);
            assertThat(result).containsEntry("firstName", false);
            assertThat(result).doesNotContainKeys("unknownField", "anotherBad");
        }
    }

    // ─── VisibilityField enum ─────────────────────────────────────────────────

    @Nested
    @DisplayName("VisibilityField")
    class VisibilityFieldTests {

        @Test
        @DisplayName("should generate preference key correctly")
        void shouldGenerateCorrectPreferenceKey() {
            assertThat(VisibilityField.LOCATION.preferenceKey()).isEqualTo("visibility.location");
            assertThat(VisibilityField.FIRST_NAME.preferenceKey()).isEqualTo("visibility.firstName");
            assertThat(VisibilityField.MEMBERSHIP_LEVEL.preferenceKey()).isEqualTo("visibility.membershipLevel");
        }

        @Test
        @DisplayName("should look up enum value from field name")
        void shouldFindByFieldName() {
            assertThat(VisibilityField.fromFieldName("location")).contains(VisibilityField.LOCATION);
            assertThat(VisibilityField.fromFieldName("unknown")).isEmpty();
            assertThat(VisibilityField.fromFieldName("email")).isEmpty();
            assertThat(VisibilityField.fromFieldName("birthday")).isEmpty();
        }

        @Test
        @DisplayName("should parse field name from preference key")
        void shouldExtractFieldName() {
            assertThat(VisibilityField.extractFieldName("visibility.location")).isEqualTo("location");
            assertThat(VisibilityField.extractFieldName("noprefix")).isEqualTo("noprefix");
        }
    }

    @Test
    @DisplayName("should never expose email or birthday on the public profile")
    void shouldNeverExposeEmailOrBirthdayOnPublicProfile() {
        assertThat(UserProfileResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("email", "birthday");
        assertThat(UserProfileProjection.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("getEmail", "getBirthday");
    }
}
