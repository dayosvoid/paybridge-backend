package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findForUpdateByAccountNumber(String accountNumber);

    Optional<Account> findByAccountNumber(String accountNumber);
    boolean existsByAccountNumber(String accountNumber);
    boolean existsByCustomerIdAndCurrency(Long customerId, Currency currency);
}