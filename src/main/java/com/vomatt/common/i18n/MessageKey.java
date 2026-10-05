package com.vomatt.common.i18n;

public enum MessageKey {

    // === 通用 ===
    COMMON_BAD_REQUEST("common.bad_request"),
    COMMON_INTERNAL_ERROR("common.internal_error"),
    COMMON_SERVICE_UNAVAILABLE("common.service_unavailable"),
    COMMON_UNAUTHORIZED("common.unauthorized"),
    COMMON_FORBIDDEN("common.forbidden"),
    COMMON_NOT_FOUND("common.not_found"),
    COMMON_CONFLICT("common.conflict"),
    COMMON_VALIDATION_FAILED("common.validation_failed"),
    COMMON_MISSING_PARAM("common.missing_param"),
    COMMON_TYPE_MISMATCH("common.type_mismatch"),
    COMMON_ADMIN_ONLY("common.admin_only"),
    COMMON_FILE_REQUIRED("common.file_required"),
    COMMON_FILE_READ_FAILED("common.file_read_failed"),
    COMMON_FILE_TOO_LARGE("common.file_too_large"),
    COMMON_INVALID_CONTENT_TYPE("common.invalid_content_type"),
    COMMON_IMAGE_UPLOAD_FAILED("common.image_upload_failed"),
    COMMON_SIGN_URL_FAILED("common.sign_url_failed"),
    COMMON_INVALID_STATUS("common.invalid_status"),
    COMMON_CURSOR_INVALID("common.cursor_invalid"),

    // === Auth ===
    AUTH_OTP_SENT("auth.otp.sent"),
    AUTH_LOGIN_SUCCESS("auth.login.success"),
    AUTH_LOGOUT_SUCCESS("auth.logout.success"),
    AUTH_TOKEN_REFRESHED("auth.token.refreshed"),
    AUTH_TOKEN_INVALID("auth.token.invalid"),
    AUTH_TOKEN_EXPIRED("auth.token.expired"),
    AUTH_INVALID_OTP("auth.invalid_otp"),
    AUTH_OTP_EXPIRED("auth.otp.expired"),
    AUTH_OTP_INVALID_OR_EXPIRED("auth.otp.invalid_or_expired"),
    AUTH_OTP_TOO_MANY_ATTEMPTS("auth.otp.too_many_attempts"),
    AUTH_OTP_RESEND_COOLDOWN("auth.otp.resend_cooldown"),
    AUTH_INVALID_CREDENTIALS("auth.invalid_credentials"),
    AUTH_USER_NOT_FOUND("auth.user.not_found"),
    AUTH_ACCOUNT_ALREADY_EXISTS("auth.account.already_exists"),
    AUTH_ADMIN_FORBIDDEN("auth.admin.forbidden"),
    AUTH_IDENTIFIER_REQUIRED("auth.identifier.required"),
    AUTH_EMAIL_SEND_FAILED("auth.email.send_failed"),
    AUTH_PHONE_NOT_REGISTERED("auth.phone.not_registered"),
    AUTH_SMS_SEND_FAILED("auth.sms.send_failed"),
    AUTH_INVALID_USER_ID("auth.invalid_user_id"),
    AUTH_REFRESH_TOKEN_INVALID("auth.refresh_token.invalid"),
    AUTH_SUSPICIOUS_LOGIN("auth.suspicious_login"),
    AUTH_GOOGLE_NOT_CONFIGURED("auth.google.not_configured"),
    AUTH_GOOGLE_NO_EMAIL("auth.google.no_email"),
    AUTH_GOOGLE_INVALID_AUDIENCE("auth.google.invalid_audience"),
    AUTH_LINE_NOT_CONFIGURED("auth.line.not_configured"),
    AUTH_LINE_TOKEN_EXCHANGE_FAILED("auth.line.token_exchange_failed"),
    AUTH_LINE_PROFILE_FAILED("auth.line.profile_failed"),
    AUTH_APPLE_INVALID_TOKEN("auth.apple.invalid_token"),
    AUTH_APPLE_KEY_NOT_FOUND("auth.apple.key_not_found"),
    AUTH_APPLE_INVALID_SIGNATURE("auth.apple.invalid_signature"),
    AUTH_APPLE_INVALID_ISSUER("auth.apple.invalid_issuer"),
    AUTH_APPLE_INVALID_AUDIENCE("auth.apple.invalid_audience"),
    AUTH_APPLE_TOKEN_EXPIRED("auth.apple.token_expired"),
    AUTH_APPLE_NO_EMAIL("auth.apple.no_email"),
    AUTH_APPLE_LOGIN_FAILED("auth.apple.login_failed"),
    AUTH_EMAIL_NOT_VERIFIED("auth.email_not_verified"),
    AUTH_ACCOUNT_BANNED("auth.account_banned"),

    // === User / Profile ===
    USER_NOT_FOUND("user.not_found"),
    USER_UPDATE_SUCCESS("user.update.success"),
    USER_PROFILE_NOT_FOUND("user.profile.not_found"),

    USER_USERNAME_TAKEN("user.username.taken"),
    USER_EMAIL_TAKEN("user.email.taken"),
    USER_PHONE_TAKEN("user.phone.taken"),
    USER_DELETE_FORBIDDEN("user.delete.forbidden"),

    // === Vote ===
    VOTE_NOT_FOUND("vote.not_found"),
    VOTE_OPTION_NOT_FOUND("vote.option.not_found"),
    VOTE_OPTION_NOT_IN_VOTE("vote.option.not_in_vote"),
    VOTE_NOT_ALLOWED("vote.not_allowed"),
    VOTE_ENDED("vote.ended"),
    VOTE_MULTIPLE_NOT_ALLOWED("vote.multiple.not_allowed"),
    VOTE_END_TIME_PAST("vote.end_time.past"),
    VOTE_END_BEFORE_START("vote.end_time.before_start"),
    VOTE_FORBIDDEN("vote.forbidden"),
    VOTE_OPTIONS_MIN("vote.options.min"),
    VOTE_OPTIONS_MAX("vote.options.max"),
    VOTE_DURATION_EXCEEDED("vote.duration.exceeded"),

    // === Comment ===
    COMMENT_NOT_FOUND("comment.not_found"),
    COMMENT_FORBIDDEN("comment.forbidden"),

    // === Tag ===
    TAG_NOT_FOUND("tag.not_found"),
    TAG_NAME_EXISTS("tag.name.exists"),
    TAG_SLUG_EXISTS("tag.slug.exists"),
    TAG_IN_USE("tag.in_use"),
    TAG_IDS_INVALID("tag.ids.invalid"),

    // === Lookup ===
    LOOKUP_NOT_FOUND("lookup.not_found"),
    LOOKUP_EXISTS("lookup.exists"),
    ;

    private final String code;

    MessageKey(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
