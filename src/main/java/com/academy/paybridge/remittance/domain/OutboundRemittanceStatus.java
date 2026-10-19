package com.academy.paybridge.remittance.domain;

/**
 * PENDING: the naira is held; the provider has not confirmed. COMPLETED: the provider paid out.
 * FAILED: nothing was sent and the held naira was returned to the customer.
 * RETURNED: it was COMPLETED, but the provider later reported the payout came back; the amount was refunded.
 */
public enum OutboundRemittanceStatus { PENDING, COMPLETED, FAILED, RETURNED }
