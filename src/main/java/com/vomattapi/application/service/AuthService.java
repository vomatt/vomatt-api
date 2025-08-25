package com.vomattapi.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private static final int MAX_VERIFY_SIZE = 6;
    private final MemberService memberService;

    private String generateVerificationCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < MAX_VERIFY_SIZE; i++) {
            int digit = (int) (Math.random() * 10);
            code.append(digit);
        }
        return code.toString();
    }

    public String generateVerifyCode(String email) {
        String verifyCode = generateVerificationCode();
        boolean isChanged = memberService.changeVerifyCode(email, verifyCode);
        return isChanged ? verifyCode : null;
    }
}