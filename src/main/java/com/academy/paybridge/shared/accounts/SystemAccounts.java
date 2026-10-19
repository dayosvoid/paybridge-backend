package com.academy.paybridge.shared.accounts;

/**
 * Account numbers of PayBridge's own (system) accounts. They live in {@code shared} because more
 * than one module posts to them. System accounts have a customer id of 0 or below.
 */
public final class SystemAccounts {

    /**
     * Principal of external transfers that PayBridge is holding: while a transfer is in flight, and
     * after it succeeds until a settlement batch moves it to {@link #PROVIDER_CLEARING}.
     */
    public static final String SETTLEMENT = "0000000000";

    /** Receives transfer fees. */
    public static final String FEE_INCOME = "0000000003";

    /** Receives the tax collected on fees. */
    public static final String TAX_PAYABLE = "0000000004";

    /**
     * Cumulative principal that settlement batches have moved here from {@link #SETTLEMENT}: the
     * amount PayBridge has accounted for as handed to the provider. Principal only; fee and tax
     * are already in their own accounts.
     */
    public static final String PROVIDER_CLEARING = "0000000005";

    /** NGN side of currency conversion: receives the sender's NGN in a remittance. */
    public static final String FX_NGN_POOL = "0000000006";

    /** USD side of currency conversion: pays out the beneficiary's USD in a remittance. */
    public static final String FX_USD_POOL = "0000000007";

    /**
     * Holds the NGN of an outbound remittance sent through a provider: the amount plus the provider
     * fee, from the moment the customer is debited until the provider confirms (it then stays here,
     * owed to the provider) or fails (it is refunded to the customer).
     */
    public static final String REMITTANCE_CLEARING = "0000000008";

    private SystemAccounts() {}
}
