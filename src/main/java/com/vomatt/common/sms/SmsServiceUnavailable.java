package com.vomatt.common.sms;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Production SmsService until a real provider (Twilio / AWS SNS...) is wired: every SMS OTP request answers 503
 * {@code auth.sms.unavailable}, so the app starts and no code is ever written to the logs.
 */
@Service
@Profile("prod")
public class SmsServiceUnavailable implements SmsService {

    @Override
    public void sendOtp(String phone, String code) {
        throw ApiException.serviceUnavailable(MessageKey.AUTH_SMS_UNAVAILABLE);
    }
}
