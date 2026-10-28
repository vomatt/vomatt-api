package com.vomatt.entity;

import com.vomatt.entity.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A passkey (WebAuthn credential) a user registered. Converted to and from webauthn4j's CredentialRecord only in
 * {@link com.vomatt.auth.PasskeyService}; deleted with its user (ON DELETE CASCADE).
 */
@Entity
@Table(name = "passkey_credentials")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@ToString(exclude = "user")
public class PasskeyCredential extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "credential_id", nullable = false, unique = true, updatable = false)
    private byte[] credentialId;

    /** webauthn4j AttestedCredentialDataConverter bytes: AAGUID, credential id and COSE public key. */
    @Column(name = "attested_credential_data", nullable = false, updatable = false)
    private byte[] attestedCredentialData;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    /** AuthenticatorTransport values (internal, hybrid, usb...) the browser reported at registration. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "transports", nullable = false, columnDefinition = "text[]")
    private String[] transports;

    @Column(name = "uv_initialized", nullable = false)
    private boolean uvInitialized;

    @Column(name = "backup_eligible", nullable = false, updatable = false)
    private boolean backupEligible;

    @Column(name = "backed_up", nullable = false)
    private boolean backedUp;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;
}
