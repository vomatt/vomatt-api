package com.vomatt.common.audit;

import com.vomatt.entity.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "audit_logs")
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AuditLog extends AuditableEntity {

    @Column(name = "user_id")
    private String userId;

    @Column(name = "username")
    private String username;

    @Column(name = "action")
    private String action;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "request_uri")
    private String requestUri;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "success")
    private boolean success;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    public AuditLog(String userId, String username, String action, String resourceType, String resourceId) {
        this.userId = userId;
        this.username = username;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.success = true;
    }
}
