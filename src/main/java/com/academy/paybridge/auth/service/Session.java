package com.academy.paybridge.auth.service;

import com.academy.paybridge.auth.api.UserView;

/** What a successful login or refresh hands back: a new access token, the next refresh token, and the user. */
public record Session(IssuedToken accessToken, IssuedRefreshToken refreshToken, UserView user) {}
