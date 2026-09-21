package com.devnine.tendon.common.api;

import org.springframework.http.HttpStatus;

public class OtpCooldownException extends AppException {
    public OtpCooldownException(String message){
        super(HttpStatus.TOO_MANY_REQUESTS, "OTP_COOLDOWN", message);
    }
}
