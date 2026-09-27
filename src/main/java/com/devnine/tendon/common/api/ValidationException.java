package com.devnine.tendon.common.api;

import org.springframework.http.HttpStatus;

public class ValidationException extends AppException {
    public ValidationException(String message){
        super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
