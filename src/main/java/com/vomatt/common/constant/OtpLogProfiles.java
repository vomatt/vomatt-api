package com.vomatt.common.constant;

/** Profiles under which OTPs may be written to the log instead of sent (local development, load tests). */
public final class OtpLogProfiles {

    /** Profile expression for the OTP-logging senders; every other environment fails closed. */
    public static final String ALLOWED = "(dev | loadtest) & !prod";

    private OtpLogProfiles() {
    }
}
