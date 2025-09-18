# Gmail SMTP Setup Guide for Vomatt API

## Overview
This guide will help you configure your personal Gmail account to send emails through the Vomatt API application using SMTP.

## Prerequisites
- A Gmail account
- 2-Factor Authentication (2FA) enabled on your Gmail account

## Step 1: Enable 2-Factor Authentication

1. Go to [Google Account Settings](https://myaccount.google.com/)
2. Click on "Security" in the left sidebar
3. Under "Signing in to Google", click on "2-Step Verification"
4. Follow the setup process to enable 2FA using your phone number or authenticator app

## Step 2: Generate App Password

Since Gmail requires 2FA for SMTP access, you need to create an "App Password":

1. Go to [Google Account Settings](https://myaccount.google.com/)
2. Click on "Security" in the left sidebar
3. Under "Signing in to Google", click on "App passwords"
4. You may need to enter your Google password again
5. Select "Mail" from the dropdown menu
6. Select "Other (Custom name)" and enter "Vomatt API"
7. Click "Generate"
8. **Important**: Copy the 16-character app password (it looks like: `xxxx xxxx xxxx xxxx`)

## Step 3: Configure Application Properties

Update your `application.properties` or environment variables:

### Option A: Direct Configuration in application.properties
```properties
# Enable email service
app.email.enabled=true

# Gmail SMTP Configuration
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=your-email@gmail.com
spring.mail.password=your-16-character-app-password
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

### Option B: Environment Variables (Recommended for Security)
Set these environment variables instead of hardcoding in properties file:

```bash
export MAIL_USERNAME=your-email@gmail.com
export MAIL_PASSWORD=your-16-character-app-password
```

Then in your application.properties, keep:
```properties
app.email.enabled=true
spring.mail.host=${MAIL_HOST:smtp.gmail.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:your-email@gmail.com}
spring.mail.password=${MAIL_PASSWORD:your-app-password}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

## Step 4: Current Configuration Analysis

Your current configuration shows:
- Email service is **disabled**: `app.email.enabled=false`
- Placeholder credentials are set: `your-email@gmail.com` and `your-app-password`

## Step 5: Update Your Configuration

Replace the placeholder values in your `application.properties`:

1. Change `app.email.enabled=false` to `app.email.enabled=true`
2. Replace `your-email@gmail.com` with your actual Gmail address
3. Replace `your-app-password` with the 16-character app password from Step 2

## Step 6: Test Email Functionality

After updating the configuration:

1. Restart your application
2. Test the pre-signup endpoint: `POST /api/auth/pre-signup`
3. Check the application logs for email sending status
4. Verification that verification emails are received in the recipient's inbox

## Security Best Practices

### ✅ Do:
- Use environment variables for sensitive data
- Use App Passwords instead of your actual Gmail password
- Keep your app password secure and don't share it
- Use different app passwords for different applications
- Regularly rotate app passwords

### ❌ Don't:
- Hardcode credentials in source code
- Share app passwords
- Use your actual Gmail password for SMTP
- Commit credentials to version control

## Troubleshooting

### Common Issues:

1. **"Username and Password not accepted"**
   - Make sure 2FA is enabled
   - Use App Password, not your regular Gmail password
   - Check that username is your full Gmail address

2. **"Connection timeout"**
   - Check firewall settings
   - Verification SMTP port (587) is not blocked
   - Try port 465 with SSL instead of STARTTLS

3. **"Authentication failed"**
   - Regenerate a new App Password
   - Ensure no spaces in the app password
   - Check that the Gmail account is active

### Alternative Configuration (SSL on port 465):
If port 587 doesn't work, try:
```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=465
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.ssl.enable=true
```

## Testing Your Setup

You can test email functionality using these API endpoints:

1. **Pre-signup email test**:
```bash
curl -X POST http://localhost:8080/api/auth/pre-signup \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "username": "testuser"
  }'
```

2. **Check application logs** for email sending status and any error messages.

## Environment Variables Summary

For production deployment, set these environment variables:
```bash
MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-16-character-app-password
```

## Next Steps

1. Enable email service: Set `app.email.enabled=true`
2. Update credentials with your actual Gmail and app password
3. Test the pre-signup functionality
4. Monitor logs for any email-related errors
5. Consider using a dedicated email service (SendGrid, AWS SES) for production

## Support

If you encounter issues:
1. Check Gmail account security settings
2. Verification 2FA is properly enabled
3. Ensure app password is correctly generated and copied
4. Review application logs for detailed error messages