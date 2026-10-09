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
 * 公開端點（不需認證）的 Swagger 文件 annotation：200 成功，以及共用 ErrorResponse 的 400 / 404 / 429。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(responseCode = "200", description = "Success")
@ApiResponse(responseCode = "400", description = "Validation failed or malformed request (errorCode `common.validation_failed`, `common.bad_request`, ...)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_VALIDATION)))
@ApiResponse(responseCode = "404", description = "Resource not found (errorCode e.g. `vote.not_found`)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_NOT_FOUND)))
@ApiResponse(responseCode = "429", description = "Rate limit exceeded; see `Retry-After` header (errorCode `common.rate_limited`)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_RATE_LIMITED)))
public @interface PublicApiResponse {
}
