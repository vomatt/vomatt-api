package com.vomatt.repository;

import com.vomatt.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, UUID> {

    /**
     * 批次查詢指定前綴的偏好設定（如 visibility.*）
     */
    List<UserPreference> findByUserIdAndKeyStartingWith(UUID userId, String keyPrefix);

    /**
     * 查詢單一偏好設定
     */
    Optional<UserPreference> findByUserIdAndKey(UUID userId, String key);
}
