package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

import java.security.SecureRandom;
import java.util.Optional;

@Service
public class AccountService implements AccountApi {

    private final AccountRepository repository;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AccountView open(Long customerId, Currency currency,
                            String accountName, String bankCode, String bankName) {
        if (repository.existsByCustomerIdAndCurrency(customerId, currency)) {
            throw new IllegalStateException("Customer already has a " + currency + " account");
        }
        boolean customBank = bankCode != null && !bankCode.isBlank();
        String resolvedCode = customBank ? bankCode.trim() : Account.DEFAULT_BANK_CODE;
        String resolvedBankName = (bankName != null && !bankName.isBlank()) ? bankName.trim()
                : customBank ? "Bank " + resolvedCode : Account.DEFAULT_BANK_NAME;
        String resolvedName = (accountName != null && !accountName.isBlank()) ? accountName.trim()
                : Account.DEFAULT_ACCOUNT_NAME;

        Account saved = repository.save(new Account(
                newAccountNumber(), currency, 0L, customerId, resolvedName, resolvedCode, resolvedBankName));
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public AccountView getByNumber(String accountNumber) {
        return findByNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
    }

    @Transactional
    public AccountView changeStatus(String accountNumber, AccountStatus status) {
        Account account = repository.findForUpdateByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
        if (account.getCustomerId() <= 0) {
            throw new IllegalStateException("System accounts cannot change status");
        }
        account.changeStatus(status);
        return toView(account);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findByNumber(String accountNumber) {
        return repository.findByAccountNumber(accountNumber).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findByNumberAndBank(String accountNumber, String bankCode) {
        return repository.findByAccountNumber(accountNumber)
                .filter(a -> a.getBankCode().equals(bankCode))
                .map(this::toView);
    }

    @Override
    public boolean exists(String accountNumber) {
        return repository.existsByAccountNumber(accountNumber);
    }

    @Override
    @Transactional
    public void debit(String accountNumber, Money amount) {
        load(accountNumber, amount).debit(amount.minorUnits());
    }

    @Override
    @Transactional
    public void lockInOrder(List<String> accountNumbers) {
        for (String number : accountNumbers) {
            repository.findForUpdateByAccountNumber(number)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + number));
        }
    }

    @Override
    @Transactional
    public void credit(String accountNumber, Money amount) {
        load(accountNumber, amount).credit(amount.minorUnits());
    }

    private Account load(String accountNumber, Money amount) {
        Account account = repository.findForUpdateByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountNumber));
        if (account.getCurrency() != amount.currency()) {
            throw new IllegalArgumentException("Currency mismatch on account " + accountNumber);
        }
        return account;
    }

    private AccountView toView(Account a) {
        return new AccountView(a.getAccountNumber(), a.getCurrency(), a.getBalanceMinor(),
                a.getCustomerId(), a.getStatus(), a.getBankCode(), a.getBankName(), a.getAccountName());
    }

    private String newAccountNumber() {
        String number;
        do {
            number = String.format("%010d", random.nextLong(10_000_000_000L));
        } while (repository.existsByAccountNumber(number));
        return number;
    }
}