package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.Conversion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversionRepository extends JpaRepository<Conversion, Long> {

    Optional<Conversion> findByIdempotencyKey(String idempotencyKey);

    Optional<Conversion> findByReference(String reference);

    boolean existsByQuoteId(String quoteId);
}
