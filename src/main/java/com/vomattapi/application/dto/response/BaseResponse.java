package com.vomattapi.application.dto.response;

import lombok.Data;

@Data
public class BaseResponse {
    private boolean success;
    private String errorType;

    public BaseResponse() {}

    public BaseResponse(boolean success) {
        this.success = success;
    }

    public BaseResponse(boolean success, String errorType) {
        this.success = success;
        this.errorType = errorType;
    }

    public boolean isSuccess()              { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorType()            { return errorType; }
    public void setErrorType(String t)      { this.errorType = t; }
}