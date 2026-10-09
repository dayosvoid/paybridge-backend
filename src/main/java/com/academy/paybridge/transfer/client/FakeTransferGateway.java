package com.academy.paybridge.transfer.client;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.transfer.domain.SystemAccounts;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline stand-in for a provider, active unless the "paystack" profile is on.
 *
 * If the destination is a PayBridge account held at the given bank code, the transfer is
 * simulated end to end and the beneficiary is really credited. Otherwise the last digit of the
 * destination decides what happens:
 *   9 -> FAILED now          8 -> PENDING now, SUCCESS when verified
 *   7 -> PENDING now, FAILED when verified          anything else -> SUCCESS now
 */
@Component
@Profile("!paystack")
public class FakeTransferGateway implements TransferGateway {

    private final AccountApi accounts;
    private final LedgerApi ledger;

    /** What this "provider" will answer when asked about a reference later. Lost on restart, like an outage. */
    private final Map<String, GatewayStatus> verifyAnswers = new ConcurrentHashMap<>();

    public FakeTransferGateway(AccountApi accounts, LedgerApi ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Override
    public AccountName resolveAccount(String accountNumber, String bankCode) {
        if (accountNumber == null || !accountNumber.matches("\\d{10}")) {
            throw new IllegalArgumentException("Account number must be 10 digits");
        }
        Optional<AccountView> local = accounts.findByNumberAndBank(accountNumber, bankCode);
        if (local.isPresent()) {
            return new AccountName(accountNumber, local.get().accountName());
        }
        if (accounts.exists(accountNumber)) {
            throw new IllegalArgumentException(
                    "Account " + accountNumber + " is not held at bank " + bankCode);
        }
        return new AccountName(accountNumber, "FAKE ACCOUNT HOLDER");
    }

    @Override
    public GatewayResult send(TransferInstruction instruction) {
        String reference = instruction.reference();

        Optional<AccountView> local =
                accounts.findByNumberAndBank(instruction.accountNumber(), instruction.bankCode());
        if (local.isPresent()) {
            GatewayResult delivered = deliverToLocalAccount(instruction, local.get());
            verifyAnswers.put(reference, delivered.status());
            return delivered;
        }

        String providerRef = "FAKE-" + UUID.randomUUID();
        char last = instruction.accountNumber().charAt(instruction.accountNumber().length() - 1);
        return switch (last) {
            case '9' -> {
                verifyAnswers.put(reference, GatewayStatus.FAILED);
                yield new GatewayResult(GatewayStatus.FAILED, providerRef, "Fake bank rejected the transfer");
            }
            case '8' -> {
                verifyAnswers.put(reference, GatewayStatus.SUCCESS);
                yield new GatewayResult(GatewayStatus.PENDING, providerRef, "Fake bank is still processing");
            }
            case '7' -> {
                verifyAnswers.put(reference, GatewayStatus.FAILED);
                yield new GatewayResult(GatewayStatus.PENDING, providerRef, "Fake bank is still processing");
            }
            default -> {
                verifyAnswers.put(reference, GatewayStatus.SUCCESS);
                yield new GatewayResult(GatewayStatus.SUCCESS, providerRef, "Fake transfer sent");
            }
        };
    }

    @Override
    public GatewayResult verify(String reference) {
        GatewayStatus answer = verifyAnswers.get(reference);
        if (answer == null) {
            return new GatewayResult(GatewayStatus.NOT_FOUND, null, "Fake provider has no record of this reference");
        }
        return new GatewayResult(answer, "FAKE-VERIFIED", "Fake provider reports " + answer);
    }

    private GatewayResult deliverToLocalAccount(TransferInstruction instruction, AccountView beneficiary) {
        try {
            ledger.post(SystemAccounts.SETTLEMENT, beneficiary.accountNumber(),
                    instruction.amount(), instruction.reference() + "-IN");
            return new GatewayResult(GatewayStatus.SUCCESS, "SIM-" + instruction.reference(),
                    "Credited to " + beneficiary.bankName());
        } catch (RuntimeException e) {
            return new GatewayResult(GatewayStatus.FAILED, null,
                    "Beneficiary bank rejected the credit: " + e.getMessage());
        }
    }
}