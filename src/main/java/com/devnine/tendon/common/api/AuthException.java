package com.devnine.tendon.common.api;

import org.springframework.http.HttpStatus;

public class AuthException extends AppException {
    public AuthException(String message){
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }
}
