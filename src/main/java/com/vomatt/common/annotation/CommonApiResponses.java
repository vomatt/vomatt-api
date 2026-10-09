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
 * Swagger annotation bundling the common responses of an authenticated endpoint: 200 / 400 / 401 / 403 / 429.
 * Error responses share the ErrorResponse schema and examples registered in OpenAPIConfig.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(responseCode = "200", description = "Success")
@ApiResponse(responseCode = "400", description = "Validation failed or malformed request (errorCode `common.validation_failed`, `common.bad_request`, ...)",
        content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_VALIDATION)))
@ApiResponse(responseCode = "401", description = "Missing, expired or invalid access token (errorCode `common.unauthorized`, `auth.token.expired`, `auth.token.invalid`)",
        content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_UNAUTHORIZED)))
@ApiResponse(responseCode = "403", description = "Authenticated but not allowed (errorCode `common.forbidden`)",
        content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_FORBIDDEN)))
@ApiResponse(responseCode = "429", description = "Rate limit exceeded; see `Retry-After` header (errorCode `common.rate_limited`)",
        content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_RATE_LIMITED)))
public @interface CommonApiResponses {
}
