# Email i18n Locale Detection Implementation

## Overview

The email system now automatically detects locale from API requests and sends emails in the appropriate language. The system supports:

- **English** (`en`, `en-US`)
- **Traditional Chinese** (`zh-TW`) - Default fallback
- **Simplified Chinese** (`zh-CN`, `zh`)

## How Locale Detection Works

### 1. Automatic Detection Flow

When an API request is made:

```
HTTP Request with Accept-Language header
    ↓
LocaleService.getCurrentRequestLocale()
    ↓
1. Try Spring's LocaleContextHolder
2. Try HttpServletRequest.getLocale()
3. Fallback to Traditional Chinese
    ↓
EmailService uses detected locale
```

### 2. Implementation Components

#### LocaleService (`LocaleService.java`)
- `getCurrentRequestLocale()` - Automatically detects from request context
- `getLocaleFromRequest(HttpServletRequest)` - Explicit request-based detection
- `isChineseLocale()` - Check if current locale is Chinese
- `isEnglishLocale()` - Check if current locale is English

#### InternationalizationConfig (`InternationalizationConfig.java`)
- Configures MessageSource for i18n messages
- Sets up AcceptHeaderLocaleResolver
- Defines supported locales with fallback behavior

#### EmailService Auto-detection
- `sendPreSignupEmail(to, code)` - Uses auto-detected locale
- `sendVerificationEmail(to, code)` - Uses auto-detected locale
- `sendPreSignupEmail(to, code, locale)` - Uses explicit locale
- `sendVerificationEmail(to, code, locale)` - Uses explicit locale

## API Request Examples

### English Email (using Accept-Language header)
```bash
curl -X POST http://localhost:8080/api/auth/pre-signup \
  -H "Accept-Language: en-US,en;q=0.9" \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "username": "testuser"}'
```
**Result**: Email sent in English

### Traditional Chinese Email
```bash
curl -X POST http://localhost:8080/api/auth/pre-signup \
  -H "Accept-Language: zh-TW,zh;q=0.8" \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "username": "testuser"}'
```
**Result**: Email sent in Traditional Chinese

### Default Fallback (no Accept-Language)
```bash
curl -X POST http://localhost:8080/api/auth/pre-signup \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "username": "testuser"}'
```
**Result**: Email sent in Traditional Chinese (default)

## Email Templates

Both email templates support i18n:

### Pre-signup Email Template
- **File**: `src/main/resources/templates/email/pre-signup-email-verification.html`
- **Usage**: When user requests pre-signup verification
- **Content**: Welcome message + verification code

### Login Verification Email Template
- **File**: `src/main/resources/templates/email/email-verification.html`
- **Usage**: When user requests login verification
- **Content**: Login verification + verification code

### Template Features
- Automatic locale detection from request context
- Thymeleaf i18n expressions (`#{message.key}`)
- Responsive HTML design
- Consistent styling across languages

## Message Properties

### Supported Languages

#### English (`messages_en.properties`)
```properties
email.verification.title=Email Verification
email.verification.subject=Vomatt - Email Verification Code
email.verification.greeting=Hello,
email.verification.pre.signup.message=Thank you for your interest in Vomatt. Please use the following verification code to complete your pre-signup verification:
# ... more messages
```

#### Traditional Chinese (`messages_zh_TW.properties`)
```properties
email.verification.title=電子郵件驗證
email.verification.subject=Vomatt - 電子郵件驗證碼
email.verification.greeting=您好，
email.verification.pre.signup.message=感謝您註冊Vomatt帳戶。請使用以下驗證碼完成您的電子郵件驗證：
# ... more messages
```

## Service Usage

### Automatic Locale Detection (Recommended)
```java
@Autowired
private EmailService emailService;

// Automatically uses locale from HTTP request context
public void sendPreSignupEmail(String email, String code) {
    emailService.sendPreSignupEmail(email, code);
}

public void sendLoginVerification(String email, String code) {
    emailService.sendVerificationEmail(email, code);
}
```

### Explicit Locale Override
```java
// Force specific locale regardless of request
emailService.sendPreSignupEmail(email, code, Locale.ENGLISH);
emailService.sendVerificationEmail(email, code, Locale.TRADITIONAL_CHINESE);
```

## Testing Locale Detection

### Unit Test Example
```java
@Test
public void testEnglishLocaleDetection() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Accept-Language", "en-US,en;q=0.9");

    ServletRequestAttributes attributes = new ServletRequestAttributes(request);
    RequestContextHolder.setRequestAttributes(attributes);

    Locale locale = localeService.getCurrentRequestLocale();
    assertEquals("en", locale.getLanguage());
}
```

## Configuration

### Application Properties
```properties
# Enable email service
app.email.enabled=true

# Email configuration
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=${MAIL_USERNAME}
spring.mail.password=${MAIL_PASSWORD}
```

### Default Locale Setting
The default locale is set to Traditional Chinese (`zh_TW`) and can be changed in:
```java
// InternationalizationConfig.java
localeResolver.setDefaultLocale(Locale.TRADITIONAL_CHINESE);
```

## Troubleshooting

### Common Issues

1. **Emails always in Chinese despite Accept-Language**
   - Check if LocaleService is properly injected
   - Verify Accept-Language header format
   - Ensure InternationalizationConfig is loaded

2. **Missing translations**
   - Check message properties files exist
   - Verify message keys match template expressions
   - Ensure UTF-8 encoding for Chinese characters

3. **Locale not detected in background jobs**
   - Use explicit locale parameter for async operations
   - Background threads don't have request context

### Debug Tips

Enable debug logging:
```properties
logging.level.com.vomattapi.application.service.LocaleService=DEBUG
logging.level.com.vomattapi.application.service.EmailServiceImpl=DEBUG
```

Check locale detection:
```java
@Autowired
private LocaleService localeService;

public void debugLocale() {
    Locale current = localeService.getCurrentRequestLocale();
    log.debug("Detected locale: {}", current);
    log.debug("Is Chinese: {}", localeService.isChineseLocale());
    log.debug("Is English: {}", localeService.isEnglishLocale());
}
```

## Summary

The email system now provides seamless internationalization that:

✅ **Automatically detects locale** from HTTP Accept-Language headers
✅ **Falls back gracefully** to Traditional Chinese when no locale specified
✅ **Supports multiple languages** with consistent templates
✅ **Maintains clean API** - no code changes needed for basic usage
✅ **Provides flexibility** for explicit locale override when needed
✅ **Follows Spring best practices** for i18n implementation