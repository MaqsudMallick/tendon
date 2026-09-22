package com.devnine.tendon.common.api;

import org.springframework.http.HttpStatus;

public class InternalException extends AppException {
    public InternalException(String message){
        super(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message);
    }
}
