package com.academy.paybridge.transfer.domain;

public final class SystemAccounts {

    /** Holds money while an external transfer is in flight. */
    public static final String SETTLEMENT = "0000000000";

    /** Receives transfer fees. */
    public static final String FEE_INCOME = "0000000003";

    /** Receives the tax collected on fees. */
    public static final String TAX_PAYABLE = "0000000004";

    private SystemAccounts() {}
}
