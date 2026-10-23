package com.academy.paybridge.auth.web;

import com.academy.paybridge.auth.service.InvalidCredentialsException;
import com.academy.paybridge.auth.service.InvalidRefreshTokenException;
import com.academy.paybridge.shared.exception.GlobalExceptionHandler.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ErrorResponse> invalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(HttpStatus.UNAUTHORIZED.getReasonPhrase(), e.getMessage()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ErrorResponse> invalidRefreshToken(InvalidRefreshTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(HttpStatus.UNAUTHORIZED.getReasonPhrase(), e.getMessage()));
    }
}
