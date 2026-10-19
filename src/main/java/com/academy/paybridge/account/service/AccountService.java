package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountRef;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

@Service
public class AccountService implements AccountApi {

    private final AccountRepository repository;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    /**
     * Opens a customer account: one account number holding an NGN balance and a USD balance.
     * Returns the NGN (primary) balance; use {@link #findAllByNumber} for both.
     */
    @Transactional
    public AccountView open(Long customerId, String accountName, String bankCode, String bankName) {
        if (repository.existsByCustomerId(customerId)) {
            throw new IllegalStateException("Customer already has an account");
        }
        boolean customBank = bankCode != null && !bankCode.isBlank();
        String resolvedCode = customBank ? bankCode.trim() : Account.DEFAULT_BANK_CODE;
        String resolvedBankName = (bankName != null && !bankName.isBlank()) ? bankName.trim()
                : customBank ? "Bank " + resolvedCode : Account.DEFAULT_BANK_NAME;
        String resolvedName = (accountName != null && !accountName.isBlank()) ? accountName.trim()
                : Account.DEFAULT_ACCOUNT_NAME;

        String number = newAccountNumber();
        Account ngn = repository.save(new Account(
                number, Currency.NGN, 0L, customerId, resolvedName, resolvedCode, resolvedBankName));
        repository.save(new Account(
                number, Currency.USD, 0L, customerId, resolvedName, resolvedCode, resolvedBankName));
        return toView(ngn);
    }

    @Transactional(readOnly = true)
    public AccountView getByNumber(String accountNumber) {
        return findByNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
    }

    @Transactional(readOnly = true)
    public AccountView getByNumber(String accountNumber, Currency currency) {
        return findByNumber(accountNumber, currency)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account not found: " + accountNumber + " (" + currency + ")"));
    }

    @Transactional(readOnly = true)
    public List<AccountView> getAllByNumber(String accountNumber) {
        List<AccountView> all = findAllByNumber(accountNumber);
        if (all.isEmpty()) {
            throw new ResourceNotFoundException("Account not found: " + accountNumber);
        }
        return all;
    }

    /** Freezing or closing applies to the whole account number, so both balances change together. */
    @Transactional
    public AccountView changeStatus(String accountNumber, AccountStatus status) {
        List<Account> balances = repository.findForUpdateByAccountNumberOrderByCurrency(accountNumber);
        if (balances.isEmpty()) {
            throw new ResourceNotFoundException("Account not found: " + accountNumber);
        }
        for (Account balance : balances) {
            if (balance.getCustomerId() <= 0) {
                throw new IllegalStateException("System accounts cannot change status");
            }
        }
        for (Account balance : balances) {
            balance.changeStatus(status);
        }
        return toView(primary(balances));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findByNumber(String accountNumber) {
        List<Account> balances = repository.findByAccountNumberOrderByCurrency(accountNumber);
        return balances.isEmpty() ? Optional.empty() : Optional.of(toView(primary(balances)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findByNumber(String accountNumber, Currency currency) {
        return repository.findByAccountNumberAndCurrency(accountNumber, currency).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountView> findAllByNumber(String accountNumber) {
        return repository.findByAccountNumberOrderByCurrency(accountNumber).stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findByNumberAndBank(String accountNumber, String bankCode) {
        return findByNumber(accountNumber).filter(a -> a.bankCode().equals(bankCode));
    }

    @Override
    @Transactional(readOnly = true)
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
    public void credit(String accountNumber, Money amount) {
        load(accountNumber, amount).credit(amount.minorUnits());
    }

    @Override
    @Transactional
    public void lockInOrder(List<AccountRef> accounts) {
        for (AccountRef ref : accounts) {
            repository.findForUpdateByAccountNumberAndCurrency(ref.accountNumber(), ref.currency())
                    .orElseThrow(() -> notFoundOrWrongCurrency(ref.accountNumber()));
        }
    }

    private Account load(String accountNumber, Money amount) {
        return repository.findForUpdateByAccountNumberAndCurrency(accountNumber, amount.currency())
                .orElseThrow(() -> notFoundOrWrongCurrency(accountNumber));
    }

    /** The number is unknown, or it exists but has no balance in the requested currency. */
    private IllegalArgumentException notFoundOrWrongCurrency(String accountNumber) {
        return repository.existsByAccountNumber(accountNumber)
                ? new IllegalArgumentException("Currency mismatch on account " + accountNumber)
                : new IllegalArgumentException("Account not found: " + accountNumber);
    }

    /** NGN if the number has an NGN balance, otherwise its first balance. The list is never empty. */
    private static Account primary(List<Account> balances) {
        return balances.stream()
                .filter(a -> a.getCurrency() == Currency.NGN)
                .findFirst()
                .orElse(balances.get(0));
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
