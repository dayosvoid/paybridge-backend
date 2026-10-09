package com.academy.paybridge.transfer.client;

public record GatewayResult(GatewayStatus status, String providerReference, String message) {}
