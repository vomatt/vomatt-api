package com.vomattapi.domain.user;

import com.vomattapi.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @OneToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    private User user;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false, name = "expiry_date")
    private LocalDateTime expiryDate;

    public RefreshToken() {}

    public RefreshToken(User user, String token, LocalDateTime expiryDate) {
        this.user = user;
        this.token = token;
        this.expiryDate = expiryDate;
    }

    public User getUser()                          { return user; }
    public void setUser(User user)                 { this.user = user; }

    public String getToken()                       { return token; }
    public void setToken(String token)             { this.token = token; }

    public LocalDateTime getExpiryDate()           { return expiryDate; }
    public void setExpiryDate(LocalDateTime date)  { this.expiryDate = date; }

    @Override
    public String toString() {
        return "RefreshToken{token='" + token + "', expiryDate=" + expiryDate + "}";
    }
}
