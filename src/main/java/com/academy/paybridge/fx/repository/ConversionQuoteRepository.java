package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.ConversionQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversionQuoteRepository extends JpaRepository<ConversionQuote, Long> {

    Optional<ConversionQuote> findByQuoteId(String quoteId);
}
