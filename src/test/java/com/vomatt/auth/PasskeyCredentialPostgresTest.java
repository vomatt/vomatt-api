package com.vomatt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.vomatt.entity.PasskeyCredential;
import com.vomatt.entity.User;
import com.vomatt.repository.PasskeyCredentialRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.webauthn4j.credential.CredentialRecord;

@DisplayName("PasskeyCredentialRepository (Postgres)")
class PasskeyCredentialPostgresTest extends PostgresRepositoryTest {

    @Autowired PasskeyCredentialRepository passkeyCredentialRepository;
    @Autowired UserRepository userRepository;

    private PasskeyCredential save(User user, CredentialRecord record) {
        return passkeyCredentialRepository.saveAndFlush(PasskeyService.toEntity(user, record, "MacBook"));
    }

    @Test
    @DisplayName("should rebuild the same CredentialRecord (credential id, public key, flags) after a round trip through the table")
    void shouldRestoreCredentialRecordWhenReadBack() {
        User user = persistUser("passkey-owner");
        CredentialRecord original = PasskeyEmulator.registeredRecord();
        PasskeyCredential saved = save(user, original);
        em.clear();

        PasskeyCredential found = passkeyCredentialRepository.findById(saved.getId()).orElseThrow();
        CredentialRecord restored = PasskeyService.toRecord(found);

        assertThat(found.getName()).isEqualTo("MacBook");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getLastUsedAt()).isNull();
        assertThat(found.getCredentialId()).isEqualTo(original.getAttestedCredentialData().getCredentialId());
        assertThat(restored.getAttestedCredentialData().getCredentialId())
                .isEqualTo(original.getAttestedCredentialData().getCredentialId());
        assertThat(restored.getAttestedCredentialData().getCOSEKey())
                .isEqualTo(original.getAttestedCredentialData().getCOSEKey());
        assertThat(restored.getAttestedCredentialData().getAaguid()).isEqualTo(original.getAttestedCredentialData().getAaguid());
        assertThat(restored.getCounter()).isEqualTo(original.getCounter());
        assertThat(restored.getTransports()).isEqualTo(original.getTransports());
        assertThat(restored.isBackupEligible()).isEqualTo(original.isBackupEligible());
        assertThat(restored.isBackedUp()).isEqualTo(original.isBackedUp());
        assertThat(restored.isUvInitialized()).isEqualTo(original.isUvInitialized());
    }

    @Test
    @DisplayName("should reject a second row with the same credential id")
    void shouldFailWhenCredentialIdDuplicated() {
        CredentialRecord record = PasskeyEmulator.registeredRecord();
        save(persistUser("passkey-first"), record);
        User other = persistUser("passkey-second");

        assertThatThrownBy(() -> save(other, record)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should list only the given user's passkeys")
    void shouldReturnOnlyOwnCredentialsWhenFindingByUserId() {
        User alice = persistUser("passkey-alice");
        User bob = persistUser("passkey-bob");
        PasskeyCredential a1 = save(alice, PasskeyEmulator.registeredRecord());
        PasskeyCredential a2 = save(alice, PasskeyEmulator.registeredRecord());
        save(bob, PasskeyEmulator.registeredRecord());
        em.clear();

        assertThat(passkeyCredentialRepository.findByUserIdOrderByCreatedAt(alice.getId()))
                .extracting(PasskeyCredential::getId).containsExactly(a1.getId(), a2.getId());
    }

    @Test
    @DisplayName("should delete a user's passkeys with the user (ON DELETE CASCADE)")
    void shouldDeleteCredentialsWhenUserDeleted() {
        User user = persistUser("passkey-deleted");
        PasskeyCredential saved = save(user, PasskeyEmulator.registeredRecord());
        em.clear();

        // The same JPA delete UserService.deleteUser issues
        userRepository.delete(userRepository.findById(user.getId()).orElseThrow());
        userRepository.flush();
        em.clear();

        assertThat(passkeyCredentialRepository.findById(saved.getId())).isEmpty();
    }
}
