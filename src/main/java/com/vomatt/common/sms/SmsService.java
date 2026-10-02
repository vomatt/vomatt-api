package com.vomatt.common.sms;

/**
 * SMS 發送介面，目前提供 NoOp 實作，可接入 Twilio / AWS SNS 等服務。
 */
public interface SmsService {

    void sendOtp(String phone, String code);
}
