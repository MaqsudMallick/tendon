package com.devnine.tendon.common.api;

import org.springframework.http.HttpStatus;

public class NotFoundException extends AppException {
    public NotFoundException(String message){
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
