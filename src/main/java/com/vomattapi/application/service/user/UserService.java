package com.vomattapi.application.service.user;

import com.vomattapi.application.dto.user.MyProfileResponse;
import com.vomattapi.application.dto.user.UpdateProfileRequest;
import com.vomattapi.application.dto.user.UserDto;
import com.vomattapi.application.dto.user.UserProfileResponse;
import com.vomattapi.domain.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;

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

    /**
     * 取得認證使用者自己的完整個人資料
     */
    MyProfileResponse getMyProfile(String userId);

    /**
     * 更新欄位顯示設定
     */
    Map<String, Boolean> updateVisibility(String userId, Map<String, Boolean> visibility);
}
