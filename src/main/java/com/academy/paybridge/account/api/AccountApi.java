package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;

import java.util.List;
import java.util.Optional;

/**
 * One account number can hold one balance per currency. Customer accounts hold an NGN and a USD
 * balance under the same number; system accounts hold a single balance.
 */
public interface AccountApi {

    /**
     * The account's primary balance: its NGN balance if it has one, otherwise its only balance.
     * Use the currency overload whenever the currency matters.
     */
    Optional<AccountView> findByNumber(String accountNumber);

    /** The balance of this account number in exactly this currency. */
    Optional<AccountView> findByNumber(String accountNumber, Currency currency);

    /** Every balance held under this account number (NGN first). */
    List<AccountView> findAllByNumber(String accountNumber);

    /** Finds an account only if it is held at the given bank code (primary balance). */
    Optional<AccountView> findByNumberAndBank(String accountNumber, String bankCode);

    boolean exists(String accountNumber);

    /**
     * Debits the balance in the currency of {@code amount}.
     * Must be called inside a transaction. Throws InsufficientFundsException or AccountRestrictedException.
     */
    void debit(String accountNumber, Money amount);

    /**
     * Credits the balance in the currency of {@code amount}.
     * Must be called inside a transaction. Throws AccountRestrictedException for a closed account.
     */
    void credit(String accountNumber, Money amount);

    /** Locks the balances in the order given. The caller sorts the list. Must be called inside a transaction. */
    void lockInOrder(List<AccountRef> accounts);
}
