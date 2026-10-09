package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.money.Money;

public record TransferInstruction(
        String reference, String accountNumber, String bankCode, String accountName, Money amount) {}