package com.vomattapi.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Simplified SMS service that doesn't use Twilio dependencies
 */
@Service
@ConditionalOnProperty(name = "app.sms.enabled", havingValue = "true", matchIfMissing = false)
public class SmsService {

    private static final Logger logger = LoggerFactory.getLogger(SmsService.class);
    
    @Value("${app.sms.enabled:false}")
    private boolean smsEnabled;

    /**
     * Send verification code via SMS (disabled)
     */
    public boolean sendVerificationSms(String phoneNumber, String verificationCode) {
        logger.info("SMS service is disabled. Verification SMS not sent to: {}", phoneNumber);
        return false;
    }

    /**
     * Send welcome SMS to new member (disabled)
     */
    public boolean sendWelcomeSms(String phoneNumber, String username) {
        logger.info("SMS service is disabled. Welcome SMS not sent to: {}", phoneNumber);
        return false;
    }
}