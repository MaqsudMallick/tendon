package com.devnine.tendon.common.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException exception){
        ErrorResponse response = new ErrorResponse(
                exception.getCode(),
                exception.getMessage()
        );

        return ResponseEntity.status(exception.getStatus()).body(response);
    }
}
