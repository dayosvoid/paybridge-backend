package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.ProviderQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProviderQuoteRepository extends JpaRepository<ProviderQuote, Long> {

    Optional<ProviderQuote> findByQuoteId(String quoteId);
}
