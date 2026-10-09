package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.money.Money;

import java.util.Optional;
import java.util.List;

public interface AccountApi {
    Optional<AccountView> findByNumber(String accountNumber);

    /** Finds an account only if it is held at the given bank code. */
    Optional<AccountView> findByNumberAndBank(String accountNumber, String bankCode);

    boolean exists(String accountNumber);

    /** Must be called inside a transaction. Throws InsufficientFundsException or AccountRestrictedException. */
    void debit(String accountNumber, Money amount);

    /** Must be called inside a transaction. Throws AccountRestrictedException for a closed account. */
    void credit(String accountNumber, Money amount);

    /** Locks the accounts in the order given. The caller sorts the list. Must be called inside a transaction. */
    void lockInOrder(List<String> accountNumbers);
}
