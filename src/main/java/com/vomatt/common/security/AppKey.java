package com.vomatt.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The one application secret ({@code APP_KEY}). Every purpose-specific key is derived from it as
 * {@code HMAC-SHA256(APP_KEY, label)}, so there is a single value to provision and rotate; a derived key never
 * reveals the root key or a sibling. Labels in use: {@code otp}.
 */
@Component
public class AppKey {

    private static final String HMAC = "HmacSHA256";
    // Same floor as jwt.secret: 256 bits of entropy when the value is random
    private static final int MIN_BYTES = 32;

    private final byte[] key;

    public AppKey(@Value("${app.key}") String key) {
        byte[] bytes = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_BYTES) {
            throw new IllegalStateException("app.key is too short: need at least " + MIN_BYTES + " bytes, got "
                    + bytes.length + ". Set APP_KEY (>= 32 characters).");
        }
        this.key = bytes;
    }

    /** Key for one purpose; the same label always yields the same key. */
    public byte[] derive(String label) {
        return hmac(key, label);
    }

    /** HMAC-SHA256 of {@code data} under {@code key}. */
    public static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC + " not available", e);
        }
    }

    public static String hmacHex(byte[] key, String data) {
        return HexFormat.of().formatHex(hmac(key, data));
    }

    /** Hex SHA-256 of {@code data}; for Redis keys that must not carry the identifier or token itself. */
    public static String sha256Hex(String data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
