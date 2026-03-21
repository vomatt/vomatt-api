package com.vomattapi.service;

import com.vomattapi.application.dto.request.SignupRequest;
import com.vomattapi.application.dto.response.ErrorType;
import com.vomattapi.application.service.EmailService;
import com.vomattapi.application.service.SignupService;
import com.vomattapi.application.service.ValidationService;
import com.vomattapi.application.service.ValidationService.ValidationResult;
import com.vomattapi.application.service.VerificationCodeService;
import com.vomattapi.domain.user.ERole;
import com.vomattapi.domain.user.Role;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.RoleRepository;
import com.vomattapi.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignupService")
class SignupServiceTest {

    @Mock ValidationService validationService;
    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock EmailService emailService;
    @Mock VerificationCodeService verificationCodeService;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks
    SignupService signupService;

    private SignupRequest validRequest;
    private Map<String, Object> validPreSignupData;

    @BeforeEach
    void setUp() {
        validRequest = new SignupRequest();
        validRequest.setUsername("testuser");
        validRequest.setEmail("test@example.com");
        validRequest.setVerificationCode("123456");
        validRequest.setFirstName("Test");
        validRequest.setLastName("User");

        validPreSignupData = Map.of(
                "verificationCode", "123456",
                "username", "testuser"
        );
    }

    @Nested
    @DisplayName("processSignup")
    class ProcessSignupTests {

        @Test
        @DisplayName("應該在 pre-signup 資料不存在時回傳 VERIFICATION_CODE_EXPIRED 失敗")
        void shouldFailWhenNoPreSignupData() {
            when(verificationCodeService.getVerificationData("pre_signup", "test@example.com:testuser"))
                    .thenReturn(null);

            SignupService.SignupResult result = signupService.processSignup(validRequest);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorType()).isEqualTo(ErrorType.VERIFICATION_CODE_EXPIRED);
        }

        @Test
        @DisplayName("應該在驗證碼不符時回傳 INVALID_VERIFICATION_CODE 失敗")
        void shouldFailWhenCodeMismatch() {
            Map<String, Object> data = Map.of("verificationCode", "999999", "username", "testuser");
            when(verificationCodeService.getVerificationData("pre_signup", "test@example.com:testuser"))
                    .thenReturn(data);

            SignupService.SignupResult result = signupService.processSignup(validRequest);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorType()).isEqualTo(ErrorType.INVALID_VERIFICATION_CODE);
        }

        @Test
        @DisplayName("應該在用戶名已存在時回傳驗證失敗")
        void shouldFailWhenUsernameAlreadyTaken() {
            when(verificationCodeService.getVerificationData(anyString(), anyString()))
                    .thenReturn(validPreSignupData);
            when(validationService.validatePreSignupData("testuser", "test@example.com"))
                    .thenReturn(ValidationResult.invalid(ErrorType.USERNAME_EXISTS));

            SignupService.SignupResult result = signupService.processSignup(validRequest);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorType()).isEqualTo(ErrorType.USERNAME_EXISTS);
        }

        @Test
        @DisplayName("應該在所有驗證通過後成功建立使用者")
        void shouldCreateUserOnSuccess() {
            when(verificationCodeService.getVerificationData(anyString(), anyString()))
                    .thenReturn(validPreSignupData);
            when(validationService.validatePreSignupData("testuser", "test@example.com"))
                    .thenReturn(ValidationResult.valid());
            when(passwordEncoder.encode("123456")).thenReturn("hashed-credential");
            Role userRole = new Role();
            when(roleRepository.findByName(ERole.ROLE_USER)).thenReturn(Optional.of(userRole));
            User savedUser = new User();
            savedUser.setEmail("test@example.com");
            savedUser.setUsername("testuser");
            when(userRepository.save(any(User.class))).thenReturn(savedUser);

            SignupService.SignupResult result = signupService.processSignup(validRequest);

            assertThat(result.isSuccess()).isTrue();
            verify(userRepository).save(any(User.class));
            verify(emailService).sendWelcomeEmail("test@example.com", "testuser");
        }
    }
}
