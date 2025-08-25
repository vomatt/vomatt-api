package com.vomattapi.application.security.services;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.vomattapi.domain.member.Member;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class MemberDetailsImpl implements UserDetails {
    private static final long serialVersionUID = 1L;

    private String id;
    private String username;
    private String email;
    
    @JsonIgnore
    private String password;
    
    @JsonIgnore
    private boolean isEmailVerified;
    
    @JsonIgnore
    private boolean isPhoneVerified;
    
    private boolean isActive;

    private Collection<? extends GrantedAuthority> authorities;

    public MemberDetailsImpl(String id, String username, String email, String password,
                             boolean isEmailVerified, boolean isPhoneVerified, boolean isActive,
                             Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.password = password;
        this.isEmailVerified = isEmailVerified;
        this.isPhoneVerified = isPhoneVerified;
        this.isActive = isActive;
        this.authorities = authorities;
    }

    public static MemberDetailsImpl build(Member member) {
        List<GrantedAuthority> authorities = member.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName().name()))
                .collect(Collectors.toList());

        return new MemberDetailsImpl(
                member.getId(),
                member.getUsername(),
                member.getEmail(),
                member.getVerifyCode(),
                member.isEmailVerified(),
                member.isPhoneVerified(),
                member.isActive(),
                authorities);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return isActive;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return isActive;
    }

    // Keeping these methods but always returning true since we've disabled verification
    public boolean isEmailVerified() {
        return true;
    }

    public boolean isPhoneVerified() {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        MemberDetailsImpl user = (MemberDetailsImpl) o;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}