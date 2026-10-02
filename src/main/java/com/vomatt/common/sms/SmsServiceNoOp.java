package com.vomatt.common.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * SMS NoOp 實作，僅記錄 log。
 * 正式環境請替換為 Twilio / AWS SNS 等實作。
 */
@Slf4j
@Service
public class SmsServiceNoOp implements SmsService {

    @Override
    public void sendOtp(String phone, String code) {
        log.info("[SMS-NOOP] OTP {} → {}", code, phone);
    }
}
