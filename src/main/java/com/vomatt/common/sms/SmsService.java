package com.vomatt.common.sms;

/**
 * SMS sending interface. Currently only a NoOp implementation exists; can be wired to Twilio / AWS SNS, etc.
 */
public interface SmsService {

    void sendOtp(String phone, String code);
}
