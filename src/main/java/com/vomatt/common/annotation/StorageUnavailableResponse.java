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

/** Swagger annotation for endpoints backed by the object storage bucket: 503 {@code common.storage_unavailable}. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(responseCode = "503", description = "Image storage not configured or unreachable (errorCode `common.storage_unavailable`)",
        content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                examples = @ExampleObject(value = """
                        {"success":false,"data":null,"message":"Image storage is temporarily unavailable, please try again later","errorCode":"common.storage_unavailable","error":"Image storage is temporarily unavailable, please try again later"}""")))
public @interface StorageUnavailableResponse {
}
