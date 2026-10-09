package com.academy.paybridge.ledger.api;

public enum EntryKind {
    TRANSFER, FEE, TAX, REVERSAL, FEE_REVERSAL, TAX_REVERSAL, INBOUND;

    private static final String[] SUFFIXES = {"-FEE-REV", "-TAX-REV", "-REV", "-FEE", "-TAX", "-IN"};

    /** Works out what a posting was for from the suffix of its reference. */
    public static EntryKind of(String reference) {
        if (reference.endsWith("-FEE-REV")) return FEE_REVERSAL;
        if (reference.endsWith("-TAX-REV")) return TAX_REVERSAL;
        if (reference.endsWith("-REV")) return REVERSAL;
        if (reference.endsWith("-FEE")) return FEE;
        if (reference.endsWith("-TAX")) return TAX;
        if (reference.endsWith("-IN")) return INBOUND;
        return TRANSFER;
    }

    /** The transfer reference with any posting suffix removed. */
    public static String transferReference(String reference) {
        for (String suffix : SUFFIXES) {
            if (reference.endsWith(suffix)) {
                return reference.substring(0, reference.length() - suffix.length());
            }
        }
        return reference;
    }
}