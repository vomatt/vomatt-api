package com.vomatt.common.annotation;

import com.vomatt.common.config.OpenAPIConfig;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 組合常見 HTTP 回應碼的 Swagger 文件 annotation，
 * 等同於在每個 endpoint 分別標註 200 / 400 / 401 / 403 / 429；
 * 錯誤回應共用 OpenAPIConfig 註冊的 ErrorResponse schema 與範例。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(responseCode = "200", description = "Success")
@ApiResponse(responseCode = "400", description = "Validation failed or malformed request (errorCode `common.validation_failed`, `common.bad_request`, ...)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_VALIDATION)))
@ApiResponse(responseCode = "401", description = "Missing, expired or invalid access token (errorCode `common.unauthorized`, `auth.token.expired`, `auth.token.invalid`)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_UNAUTHORIZED)))
@ApiResponse(responseCode = "403", description = "Authenticated but not allowed (errorCode `common.forbidden`)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_FORBIDDEN)))
@ApiResponse(responseCode = "429", description = "Rate limit exceeded; see `Retry-After` header (errorCode `common.rate_limited`)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_RATE_LIMITED)))
public @interface CommonApiResponses {
}
