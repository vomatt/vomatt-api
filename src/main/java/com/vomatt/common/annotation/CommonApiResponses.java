package com.vomatt.common.annotation;

import io.swagger.v3.oas.annotations.responses.ApiResponse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 組合常見 HTTP 回應碼的 Swagger 文件 annotation，
 * 等同於在每個 endpoint 分別標註 200 / 401 / 403。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(responseCode = "200", description = "成功")
@ApiResponse(responseCode = "401", description = "未認證")
@ApiResponse(responseCode = "403", description = "權限不足")
public @interface CommonApiResponses {
}
