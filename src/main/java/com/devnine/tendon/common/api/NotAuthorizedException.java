package com.devnine.tendon.common.api;

import org.springframework.http.HttpStatus;

public class NotAuthorizedException extends AppException{
    public NotAuthorizedException(String message){
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
