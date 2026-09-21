package com.devnine.tendon.common.api;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    protected AppException(HttpStatus status, String code, String message){
        super(message);
        this.status = status;
        this.code = code;
    }

    protected AppException(HttpStatus status, String code, String message, Throwable cause){
        super(message, cause);
        this.status = status;
        this.code = code;
    }
}
