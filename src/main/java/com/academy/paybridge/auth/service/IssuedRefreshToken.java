package com.academy.paybridge.auth.service;

public record IssuedRefreshToken(String token, long expiresInSeconds) {}
