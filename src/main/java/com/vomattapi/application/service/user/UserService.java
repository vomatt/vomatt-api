package com.vomattapi.application.service.user;

import com.vomattapi.application.dto.user.UpdateProfileRequest;
import com.vomattapi.application.dto.user.UserDto;
import com.vomattapi.application.dto.user.UserProfileResponse;
import com.vomattapi.domain.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    User updateProfile(String userId, String username, String email, String phoneNumber);

    boolean changePassword(String userId, String currentPassword, String newPassword);

    void handleFailedLogin(String username);

    void recordLogin(String userId, String ipAddress, String userAgent);

    User getUserById(String userId);

    User getUserByEmail(String email);

    User getUserByUsername(String username);

    boolean changeVerificationCode(String email, String verificationCode);

    void deleteUser(String userId);

    Page<UserDto> searchUsersByUsername(String username, Pageable pageable);

    UserProfileResponse getUserProfile(String username);

    UserProfileResponse updateMyProfile(String userId, UpdateProfileRequest request);
}
