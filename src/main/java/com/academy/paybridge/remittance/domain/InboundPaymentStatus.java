package com.academy.paybridge.remittance.domain;

/**
 * RECEIVED: recorded, not yet credited (only seen if the process stopped mid-way).
 * CREDITED: the account's USD balance was credited.
 * UNMATCHED: no account number, or no USD balance, matches. Needs an operator.
 * FAILED: the account exists but the credit was refused (for example a closed account). Needs an operator.
 * REVERSED: the provider took the payment back (a chargeback) and the money was taken from the customer.
 * REVERSAL_FAILED: the provider took it back but the customer's USD balance could not cover it (already
 *   converted or sent). PayBridge is exposed. Needs an operator; a repeated provider report retries it.
 */
public enum InboundPaymentStatus { RECEIVED, CREDITED, UNMATCHED, FAILED, REVERSED, REVERSAL_FAILED }
