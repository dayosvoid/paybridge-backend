package com.academy.paybridge.remittance.client;

public record SendResult(SendStatus status, String providerReference, String message) {}
