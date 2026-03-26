package com.vomattapi.application.mapper;

import com.vomattapi.application.dto.user.UserDto;
import com.vomattapi.domain.user.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserDto toDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId().toString());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhoneNumber(user.getPhoneNumber());
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        dto.setLocation(user.getLocation());
        dto.setPoints(user.getPoints());
        dto.setMembershipLevel(user.getMembershipLevel());
        dto.setActive(user.isActive());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setLastLoginAt(user.getLastLoginAt());
        return dto;
    }
}
