package com.vomatt.users.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class UserDto {

    private String id;
    private String username;
    private String email;
    private String phoneNumber;
    private String firstName;
    private String lastName;
    private String location;
    private int points;
    private String membershipLevel;
    private boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastLoginAt;
}
