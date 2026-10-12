
package com.academy.paybridge.transfer.api;

public record ResolvedAccountView(
        String accountNumber,
        String accountName,
        String bankName
) {}
