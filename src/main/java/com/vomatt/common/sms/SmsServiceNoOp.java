package com.vomatt.common.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NoOp SMS implementation that only writes a log.
 * Replace with a Twilio / AWS SNS implementation in production.
 */
@Slf4j
@Service
public class SmsServiceNoOp implements SmsService {

    @Override
    public void sendOtp(String phone, String code) {
        log.info("[SMS-NOOP] OTP {} → {}", code, phone);
    }
}
