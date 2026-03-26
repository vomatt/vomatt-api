package com.vomattapi.application.dto.common;

import lombok.Data;

@Data
public class BaseResponse {
    private boolean success;
    private String errorCode;

    public BaseResponse() {}

    public BaseResponse(boolean success) {
        this.success = success;
    }

    public BaseResponse(boolean success, String errorCode) {
        this.success = success;
        this.errorCode = errorCode;
    }
}