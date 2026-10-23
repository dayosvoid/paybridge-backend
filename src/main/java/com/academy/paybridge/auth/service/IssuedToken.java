package com.academy.paybridge.auth.service;

public record IssuedToken(String token, long expiresInSeconds) {}
