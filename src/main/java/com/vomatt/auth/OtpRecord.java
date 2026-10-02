package com.vomatt.auth;

import java.time.Instant;

record OtpRecord(String code, String type, Instant createdAt, int failCount) {
}
