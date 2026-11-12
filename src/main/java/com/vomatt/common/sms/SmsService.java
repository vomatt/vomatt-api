package com.vomatt.common.sms;

/**
 * SMS sending interface: {@link SmsServiceNoOp} under dev / loadtest, {@link SmsServiceUnavailable}
 * everywhere else until a provider
 * (Twilio / AWS SNS...) is wired.
 */
public interface SmsService {

    void sendOtp(String phone, String code);
}
