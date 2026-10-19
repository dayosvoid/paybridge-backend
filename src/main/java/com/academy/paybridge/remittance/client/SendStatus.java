package com.academy.paybridge.remittance.client;

/** What a provider says about a payment. FAILED means the provider confirms nothing was sent. */
public enum SendStatus { SUCCESS, PENDING, FAILED, NOT_FOUND }
