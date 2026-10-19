package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findForUpdateByAccountNumberAndCurrency(String accountNumber, Currency currency);

    /** Locks every balance of the number, in currency order (the same order the ledger locks in). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Account> findForUpdateByAccountNumberOrderByCurrency(String accountNumber);

    Optional<Account> findByAccountNumberAndCurrency(String accountNumber, Currency currency);

    List<Account> findByAccountNumberOrderByCurrency(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    boolean existsByCustomerId(Long customerId);
}
