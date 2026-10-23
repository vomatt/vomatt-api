package com.vomatt.common.sms;

import com.vomatt.common.util.TextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * SmsService that only logs the OTP, for local development. Never loaded under the prod profile: without a real
 * provider (Twilio / AWS SNS...) production must fail to start rather than print codes into the logs.
 */
@Slf4j
@Service
@Profile("!prod")
public class SmsServiceNoOp implements SmsService {

    @Override
    public void sendOtp(String phone, String code) {
        log.info("[SMS-NOOP] OTP {} for {}", code, TextUtil.maskPhone(phone));
    }
}
