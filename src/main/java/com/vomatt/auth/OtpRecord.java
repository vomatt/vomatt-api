package com.vomatt.auth;

import java.time.Instant;

/** {@code codeHash} is {@code HMAC(derive("otp"), identifier:code)}, never the code itself (see AuthService.otpHash). */
record OtpRecord(String codeHash, String type, Instant createdAt, int failCount) {
}
