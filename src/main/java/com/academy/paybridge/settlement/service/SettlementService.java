package com.academy.paybridge.settlement.service;

import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.settlement.api.SettlementBatchView;
import com.academy.paybridge.settlement.domain.SettlementBatch;
import com.academy.paybridge.settlement.domain.SettlementItem;
import com.academy.paybridge.settlement.repository.SettlementBatchRepository;
import com.academy.paybridge.settlement.repository.SettlementItemRepository;
import com.academy.paybridge.shared.accounts.SystemAccounts;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.SettlementCandidate;
import com.academy.paybridge.transfer.api.TransferApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementService implements SettlementApi {

    /** Keeps the transaction, and the lock it holds on the settlement account, short. */
    static final int MAX_BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final TransferApi transfers;
    private final LedgerApi ledger;
    private final SettlementBatchRepository batches;
    private final SettlementItemRepository items;
    private final String provider;

    public SettlementService(TransferApi transfers, LedgerApi ledger,
                             SettlementBatchRepository batches, SettlementItemRepository items,
                             @Value("${paybridge.settlement.provider:PAYSTACK}") String provider) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.batches = batches;
        this.items = items;
        this.provider = provider;
    }

    /**
     * One local transaction: lock the candidates, create the batch and items, mark the transfers,
     * post the ledger movement. If any step fails everything rolls back, including the marks, so the
     * same transfers are simply eligible again on the next run.
     */
    @Override
    @Transactional
    public Optional<SettlementBatchView> settleNextBatch(Currency currency, Duration eligibilityDelay,
                                                         int batchSize) {
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (eligibilityDelay == null || eligibilityDelay.isNegative()) {
            throw new IllegalArgumentException("eligibilityDelay must be zero or greater");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        String from = holdingAccount(currency);
        String to = clearingAccount(currency);

        Instant cutoff = Instant.now().minus(eligibilityDelay);
        List<SettlementCandidate> candidates = transfers.lockSettlementCandidates(currency, cutoff, batchSize);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Principal only. Fee and tax already went to their own accounts when the transfer was held.
        long total = 0;
        for (SettlementCandidate c : candidates) {
            total = Math.addExact(total, c.amountMinor());
        }

        String batchReference = "stl-" + UUID.randomUUID();
        SettlementBatch batch = batches.save(
                new SettlementBatch(batchReference, provider, currency, total, candidates.size()));
        items.saveAll(candidates.stream()
                .map(c -> new SettlementItem(batch.getId(), c.reference(), c.amountMinor(), currency))
                .toList());

        transfers.markSettled(candidates.stream().map(SettlementCandidate::reference).toList(), batchReference);

        ledger.post(from, to, Money.ofMinor(total, currency), batchReference);

        log.info("Settlement batch {} posted: provider={} currency={} items={} totalMinor={}",
                batchReference, provider, currency, candidates.size(), total);
        return Optional.of(toView(batch));
    }

    private static String holdingAccount(Currency currency) {
        if (currency == Currency.NGN) {
            return SystemAccounts.SETTLEMENT;
        }
        throw new IllegalArgumentException("Settlement is not configured for " + currency);
    }

    private static String clearingAccount(Currency currency) {
        if (currency == Currency.NGN) {
            return SystemAccounts.PROVIDER_CLEARING;
        }
        throw new IllegalArgumentException("Settlement is not configured for " + currency);
    }

    private static SettlementBatchView toView(SettlementBatch b) {
        return new SettlementBatchView(b.getReference(), b.getProvider(), b.getCurrency(), b.getStatus(),
                b.getTotalMinor(), b.getItemCount(), b.getCreatedAt());
    }
}