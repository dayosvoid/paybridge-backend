package com.academy.paybridge.account.domain;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.shared.exception.AccountRestrictedException;
import com.academy.paybridge.shared.exception.InsufficientFundsException;
import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "accounts")
public class Account {

    public static final String DEFAULT_BANK_CODE = "999";
    public static final String DEFAULT_BANK_NAME = "PayBridge MFB";
    public static final String DEFAULT_ACCOUNT_NAME = "PayBridge Customer";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @ColumnDefault("'ACTIVE'")
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "bank_code", nullable = false, length = 10)
    @ColumnDefault("'999'")
    private String bankCode = DEFAULT_BANK_CODE;

    @Column(name = "bank_name", nullable = false)
    @ColumnDefault("'PayBridge MFB'")
    private String bankName = DEFAULT_BANK_NAME;

    @Column(name = "account_name", nullable = false)
    @ColumnDefault("'PayBridge Customer'")
    private String accountName = DEFAULT_ACCOUNT_NAME;

    @Version
    private Long version;

    protected Account() {}

    public Account(String accountNumber, Currency currency, long balanceMinor, Long customerId) {
        this(accountNumber, currency, balanceMinor, customerId,
                DEFAULT_ACCOUNT_NAME, DEFAULT_BANK_CODE, DEFAULT_BANK_NAME);
    }

    public Account(String accountNumber, Currency currency, long balanceMinor, Long customerId,
                   String accountName, String bankCode, String bankName) {
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.balanceMinor = balanceMinor;
        this.customerId = customerId;
        this.accountName = accountName;
        this.bankCode = bankCode;
        this.bankName = bankName;
    }

    /** Only an ACTIVE account can send money. */
    public void debit(long amountMinor) {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountRestrictedException(
                    "Account " + accountNumber + " is " + status + " and cannot be debited");
        }
        if (balanceMinor < amountMinor) {
            throw new InsufficientFundsException("Insufficient funds in " + accountNumber);
        }
        balanceMinor -= amountMinor;
    }

    /** ACTIVE and FROZEN accounts can receive money. CLOSED cannot. */
    public void credit(long amountMinor) {
        if (status == AccountStatus.CLOSED) {
            throw new AccountRestrictedException(
                    "Account " + accountNumber + " is CLOSED and cannot be credited");
        }
        balanceMinor = Math.addExact(balanceMinor, amountMinor);
    }

    public void changeStatus(AccountStatus newStatus) {
        if (newStatus == status) {
            return;
        }
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStateException("A closed account cannot be reopened");
        }
        if (newStatus == AccountStatus.CLOSED && balanceMinor != 0) {
            throw new IllegalStateException("An account with a balance cannot be closed");
        }
        this.status = newStatus;
    }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public Currency getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public Long getCustomerId() { return customerId; }
    public AccountStatus getStatus() { return status; }
    public String getBankCode() { return bankCode; }
    public String getBankName() { return bankName; }
    public String getAccountName() { return accountName; }
}