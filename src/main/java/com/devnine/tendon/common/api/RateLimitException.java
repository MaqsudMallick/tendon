package com.devnine.tendon.common.api;

import org.springframework.http.HttpStatus;

public class RateLimitException extends AppException{
    public RateLimitException(String message){
        super(HttpStatus.TOO_MANY_REQUESTS, "OTP_COOLDOWN", message);
    }
}
