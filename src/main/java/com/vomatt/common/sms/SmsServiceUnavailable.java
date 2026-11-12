package com.vomatt.common.sms;

import com.vomatt.common.constant.OtpLogProfiles;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * SmsService everywhere {@link SmsServiceNoOp} is not loaded (prod, and any other or missing profile) until a real
 * provider (Twilio / AWS SNS...) is wired: every SMS OTP request answers 503 {@code auth.sms.unavailable}, so the app
 * starts and no code is ever written to the logs.
 */
@Service
@Profile("!(" + OtpLogProfiles.ALLOWED + ")")
public class SmsServiceUnavailable implements SmsService {

    @Override
    public void sendOtp(String phone, String code) {
        throw ApiException.serviceUnavailable(MessageKey.AUTH_SMS_UNAVAILABLE);
    }
}
