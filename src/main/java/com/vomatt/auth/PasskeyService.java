package com.vomatt.auth;

import com.vomatt.entity.PasskeyCredential;
import com.vomatt.entity.User;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Passkeys (WebAuthn). All webauthn4j code lives here, so a webauthn4j upgrade touches only this class. */
@Service
public class PasskeyService {

    // webauthn4j's own converter: its CBOR / COSE modules are not registered on the app's JSON mapper
    private static final ObjectConverter OBJECT_CONVERTER = new ObjectConverter();
    private static final AttestedCredentialDataConverter ATTESTED_CREDENTIAL_DATA_CONVERTER =
            new AttestedCredentialDataConverter(OBJECT_CONVERTER);

    static PasskeyCredential toEntity(User user, CredentialRecord record, String name) {
        AttestedCredentialData data = record.getAttestedCredentialData();
        Set<AuthenticatorTransport> transports = record.getTransports() == null ? Set.of() : record.getTransports();
        return PasskeyCredential.builder()
                .user(user)
                .credentialId(data.getCredentialId())
                .attestedCredentialData(ATTESTED_CREDENTIAL_DATA_CONVERTER.convert(data))
                .signCount(record.getCounter())
                .transports(transports.stream().map(AuthenticatorTransport::getValue).sorted().toArray(String[]::new))
                .uvInitialized(Boolean.TRUE.equals(record.isUvInitialized()))
                .backupEligible(Boolean.TRUE.equals(record.isBackupEligible()))
                .backedUp(Boolean.TRUE.equals(record.isBackedUp()))
                .name(name)
                .build();
    }

    static CredentialRecord toRecord(PasskeyCredential credential) {
        // Attestation is "none" and client data / extensions are not needed after registration, so they are not kept
        return new CredentialRecordImpl(null, credential.isUvInitialized(), credential.isBackupEligible(),
                credential.isBackedUp(), credential.getSignCount(),
                ATTESTED_CREDENTIAL_DATA_CONVERTER.convert(credential.getAttestedCredentialData()), null, null, null,
                Arrays.stream(credential.getTransports()).map(AuthenticatorTransport::create).collect(Collectors.toSet()));
    }
}
