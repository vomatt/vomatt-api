package com.vomattapi.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BaseResponse {
    private boolean success;
    private String errorCode;

    public BaseResponse(boolean success) {
        this.success = success;
    }
}