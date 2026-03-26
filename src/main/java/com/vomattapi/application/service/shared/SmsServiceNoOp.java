package com.vomattapi.application.service.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * No-operation implementation of SMS service used when SMS functionality is disabled
 */
@Service
@ConditionalOnProperty(name = "app.sms.enabled", havingValue = "false")
public class SmsServiceNoOp {
    
    private static final Logger logger = LoggerFactory.getLogger(SmsServiceNoOp.class);
    
    /**
     * Send verification code via SMS (no-op implementation)
     */
    public boolean sendVerificationSms(String phoneNumber, String verificationCode) {
        logger.debug("SMS service disabled: Skipping verification SMS to {}", phoneNumber);
        return false;
    }
    
    /**
     * Send welcome SMS to new member (no-op implementation)
     */
    public boolean sendWelcomeSms(String phoneNumber, String username) {
        logger.debug("SMS service disabled: Skipping welcome SMS to {}", phoneNumber);
        return false;
    }
}