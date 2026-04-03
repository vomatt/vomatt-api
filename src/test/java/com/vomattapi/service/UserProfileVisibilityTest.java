package com.vomattapi.service;

import com.vomattapi.application.dto.user.MyProfileResponse;
import com.vomattapi.application.dto.user.UserProfileResponse;
import com.vomattapi.application.mapper.UserMapper;
import com.vomattapi.application.service.user.UserServiceImpl;
import com.vomattapi.application.service.user.VisibilityField;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.UserPreference;
import com.vomattapi.domain.user.repository.UserActivityRepository;
import com.vomattapi.domain.user.repository.UserPreferenceRepository;
import com.vomattapi.domain.user.repository.UserProfileProjection;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.infrastructure.redis.CacheUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("使用者個人資料顯示設定")
class UserProfileVisibilityTest {

    @Mock
    UserRepository userRepository;

    @Mock
    UserPreferenceRepository preferenceRepository;

    @Mock
    UserActivityRepository activityRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    CacheUtil cacheUtil;

    @Mock
    UserMapper userMapper;

    @InjectMocks
    UserServiceImpl userService;

    // ─── helper ───────────────────────────────────────────────────────────────

    private static final UUID USER_UUID = UUID.randomUUID();
    private static final String USER_ID = USER_UUID.toString();
    private static final String USERNAME = "testuser";

    private User createTestUser() {
        User user = new User();
        user.setId(USER_UUID);
        user.setUsername(USERNAME);
        user.setEmail("test@example.com");
        user.setPhoneNumber("0912345678");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setDisplayName("TestDisplay");
        user.setBio("Hello world");
        user.setLocation("Taipei");
        user.setPoints(100);
        user.setMembershipLevel("SILVER");
        user.setActive(true);
        return user;
    }

    private UserProfileProjection createTestProjection() {
        return new UserProfileProjection() {
            @Override public UUID getId() { return USER_UUID; }
            @Override public String getUsername() { return USERNAME; }
            @Override public String getDisplayName() { return "TestDisplay"; }
            @Override public String getBio() { return "Hello world"; }
            @Override public LocalDateTime getCreatedAt() { return LocalDateTime.of(2025, 1, 1, 0, 0); }
            @Override public Long getTotalPolls() { return 5L; }
            @Override public Long getTotalVotes() { return 10L; }
            @Override public String getEmail() { return "test@example.com"; }
            @Override public String getFirstName() { return "Test"; }
            @Override public String getLastName() { return "User"; }
            @Override public String getLocation() { return "Taipei"; }
            @Override public Integer getPoints() { return 100; }
            @Override public String getMembershipLevel() { return "SILVER"; }
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
        @DisplayName("應該回傳認證使用者的所有欄位")
        void shouldReturnAllFieldsForMyProfile() {
            // Given
            User user = createTestUser();
            UserProfileProjection projection = createTestProjection();
            MyProfileResponse expectedResponse = new MyProfileResponse(
                    USER_ID, USERNAME, "test@example.com", "0912345678",
                    "Test", "User", "TestDisplay", "Hello world", "Taipei",
                    100, "SILVER", true, null,
                    LocalDateTime.of(2025, 1, 1, 0, 0), 5, 10,
                    Map.of("email", false, "firstName", false, "lastName", false,
                           "location", false, "points", false, "membershipLevel", false)
            );

            when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(user));
            when(userRepository.findProfileByUsername(USERNAME)).thenReturn(Optional.of(projection));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(Collections.emptyList());
            when(userMapper.toMyProfileResponse(eq(user), eq(5), eq(10), anyMap()))
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
            assertThat(result.visibilitySettings()).containsEntry("email", false);
        }
    }

    // ─── getUserProfile（公開）─────────────────────────────────────────────────

    @Nested
    @DisplayName("getUserProfile - 公開查詢")
    class GetUserProfileTests {

        @Test
        @DisplayName("應該在所有欄位隱藏時回傳 null")
        void shouldReturnOnlyBaseFieldsWhenAllHidden() {
            // Given
            UserProfileProjection projection = createTestProjection();
            UserProfileResponse expectedResponse = new UserProfileResponse(
                    USERNAME, "TestDisplay", "Hello world",
                    LocalDateTime.of(2025, 1, 1, 0, 0), 5, 10,
                    null, null, null, null, null, null
            );

            when(userRepository.findProfileByUsername(USERNAME)).thenReturn(Optional.of(projection));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(Collections.emptyList());
            when(userMapper.toPublicProfileResponse(eq(projection), anyMap()))
                    .thenReturn(expectedResponse);

            // When
            UserProfileResponse result = userService.getUserProfile(USERNAME);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.username()).isEqualTo(USERNAME);
            assertThat(result.displayName()).isEqualTo("TestDisplay");
            assertThat(result.email()).isNull();
            assertThat(result.firstName()).isNull();
            assertThat(result.lastName()).isNull();
            assertThat(result.location()).isNull();
            assertThat(result.points()).isNull();
            assertThat(result.membershipLevel()).isNull();
        }

        @Test
        @DisplayName("應該在設定 visibility.email=true 後顯示 email")
        void shouldReturnVisibleFieldsWhenConfigured() {
            // Given
            UserProfileProjection projection = createTestProjection();
            UserPreference emailPref = createPref("visibility.email", "true");

            UserProfileResponse expectedResponse = new UserProfileResponse(
                    USERNAME, "TestDisplay", "Hello world",
                    LocalDateTime.of(2025, 1, 1, 0, 0), 5, 10,
                    "test@example.com", null, null, null, null, null
            );

            when(userRepository.findProfileByUsername(USERNAME)).thenReturn(Optional.of(projection));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(List.of(emailPref));
            when(userMapper.toPublicProfileResponse(eq(projection), anyMap()))
                    .thenReturn(expectedResponse);

            // When
            UserProfileResponse result = userService.getUserProfile(USERNAME);

            // Then
            assertThat(result.email()).isEqualTo("test@example.com");
            assertThat(result.firstName()).isNull();
        }
    }

    // ─── updateVisibility ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateVisibility")
    class UpdateVisibilityTests {

        @Test
        @DisplayName("應該成功更新顯示設定")
        void shouldUpdateVisibilitySettings() {
            // Given
            User user = createTestUser();
            when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(user));
            when(preferenceRepository.findByUserIdAndKey(USER_UUID, "visibility.email"))
                    .thenReturn(Optional.empty());
            when(preferenceRepository.save(any(UserPreference.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // 更新後查詢返回新設定
            UserPreference savedPref = createPref("visibility.email", "true");
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(List.of(savedPref));

            // When
            Map<String, Boolean> result = userService.updateVisibility(USER_ID, Map.of("email", true));

            // Then
            assertThat(result).containsEntry("email", true);
            verify(preferenceRepository).save(any(UserPreference.class));
            verify(cacheUtil).evictUserCache(USER_ID);
        }

        @Test
        @DisplayName("應該忽略未知的欄位名稱")
        void shouldIgnoreUnknownVisibilityFields() {
            // Given
            User user = createTestUser();
            when(userRepository.findById(USER_UUID)).thenReturn(Optional.of(user));
            when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_UUID, "visibility."))
                    .thenReturn(Collections.emptyList());

            // When
            Map<String, Boolean> result = userService.updateVisibility(
                    USER_ID, Map.of("unknownField", true, "anotherBad", false));

            // Then — 未知欄位不會寫入
            verify(preferenceRepository, never()).save(any(UserPreference.class));
            // 所有已知欄位預設為 false
            assertThat(result).containsEntry("email", false);
            assertThat(result).containsEntry("firstName", false);
        }
    }

    // ─── VisibilityField enum ─────────────────────────────────────────────────

    @Nested
    @DisplayName("VisibilityField")
    class VisibilityFieldTests {

        @Test
        @DisplayName("應該正確生成偏好設定 key")
        void shouldGenerateCorrectPreferenceKey() {
            assertThat(VisibilityField.EMAIL.preferenceKey()).isEqualTo("visibility.email");
            assertThat(VisibilityField.FIRST_NAME.preferenceKey()).isEqualTo("visibility.firstName");
            assertThat(VisibilityField.MEMBERSHIP_LEVEL.preferenceKey()).isEqualTo("visibility.membershipLevel");
        }

        @Test
        @DisplayName("應該從欄位名稱查找列舉值")
        void shouldFindByFieldName() {
            assertThat(VisibilityField.fromFieldName("email")).contains(VisibilityField.EMAIL);
            assertThat(VisibilityField.fromFieldName("unknown")).isEmpty();
        }

        @Test
        @DisplayName("應該從偏好 key 解析欄位名稱")
        void shouldExtractFieldName() {
            assertThat(VisibilityField.extractFieldName("visibility.email")).isEqualTo("email");
            assertThat(VisibilityField.extractFieldName("noprefix")).isEqualTo("noprefix");
        }
    }
}
