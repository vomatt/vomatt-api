package com.vomatt.common.sms;

import com.vomatt.common.constant.OtpLogProfiles;
import com.vomatt.common.util.TextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * SmsService that only logs the OTP, for local development and load tests. Loaded only under the dev or loadtest
 * profile (and never with prod), so a deployment with a missing or unknown profile gets {@link SmsServiceUnavailable}
 * instead of printing codes into the logs.
 */
@Slf4j
@Service
@Profile(OtpLogProfiles.ALLOWED)
public class SmsServiceNoOp implements SmsService {

    @Override
    public void sendOtp(String phone, String code) {
        log.info("[SMS-NOOP] OTP {} for {}", code, TextUtil.maskPhone(phone));
    }
}
