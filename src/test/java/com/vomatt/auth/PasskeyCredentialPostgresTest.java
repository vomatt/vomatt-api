package com.vomatt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.vomatt.entity.PasskeyCredential;
import com.vomatt.entity.User;
import com.vomatt.repository.PasskeyCredentialRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.challenge.DefaultChallenge;

@DisplayName("PasskeyCredentialRepository (Postgres)")
class PasskeyCredentialPostgresTest extends PostgresRepositoryTest {

    @Autowired PasskeyCredentialRepository passkeyCredentialRepository;
    @Autowired UserRepository userRepository;

    /** A credential from the emulator, parsed the way registration parses it (signature checks are 04's concern). */
    private static CredentialRecord emulatedRecord() {
        PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                new PublicKeyCredentialRpEntity("vomatt.com", "Vomatt"),
                new PublicKeyCredentialUserEntity(UUID.randomUUID().toString().getBytes(), "user", "User"),
                new DefaultChallenge(),
                List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                null, List.of(), new AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.REQUIRED,
                        UserVerificationRequirement.PREFERRED), AttestationConveyancePreference.NONE, null);
        String json = PasskeyEmulator.toJson(new PasskeyEmulator("https://vomatt.com").create(options));
        RegistrationData data = WebAuthnManager.createNonStrictWebAuthnManager(new ObjectConverter()).parseRegistrationResponseJSON(json);
        return new CredentialRecordImpl(data.getAttestationObject(), data.getCollectedClientData(), data.getClientExtensions(),
                data.getTransports());
    }

    private PasskeyCredential save(User user, CredentialRecord record) {
        return passkeyCredentialRepository.saveAndFlush(PasskeyService.toEntity(user, record, "MacBook"));
    }

    @Test
    @DisplayName("should rebuild the same CredentialRecord (credential id, public key, flags) after a round trip through the table")
    void shouldRestoreCredentialRecordWhenReadBack() {
        User user = persistUser("passkey-owner");
        CredentialRecord original = emulatedRecord();
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
        CredentialRecord record = emulatedRecord();
        save(persistUser("passkey-first"), record);
        User other = persistUser("passkey-second");

        assertThatThrownBy(() -> save(other, record)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should list only the given user's passkeys")
    void shouldReturnOnlyOwnCredentialsWhenFindingByUserId() {
        User alice = persistUser("passkey-alice");
        User bob = persistUser("passkey-bob");
        PasskeyCredential a1 = save(alice, emulatedRecord());
        PasskeyCredential a2 = save(alice, emulatedRecord());
        save(bob, emulatedRecord());
        em.clear();

        assertThat(passkeyCredentialRepository.findByUserIdOrderByCreatedAt(alice.getId()))
                .extracting(PasskeyCredential::getId).containsExactly(a1.getId(), a2.getId());
    }

    @Test
    @DisplayName("should delete a user's passkeys with the user (ON DELETE CASCADE)")
    void shouldDeleteCredentialsWhenUserDeleted() {
        User user = persistUser("passkey-deleted");
        PasskeyCredential saved = save(user, emulatedRecord());
        em.clear();

        // The same JPA delete UserService.deleteUser issues
        userRepository.delete(userRepository.findById(user.getId()).orElseThrow());
        userRepository.flush();
        em.clear();

        assertThat(passkeyCredentialRepository.findById(saved.getId())).isEmpty();
    }
}
