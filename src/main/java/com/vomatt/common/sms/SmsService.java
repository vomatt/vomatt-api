package com.vomatt.common.sms;

/**
 * SMS sending interface: {@link SmsServiceNoOp} outside prod, {@link SmsServiceUnavailable} in prod until a provider
 * (Twilio / AWS SNS...) is wired.
 */
public interface SmsService {

    void sendOtp(String phone, String code);
}
