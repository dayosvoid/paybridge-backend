package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.ConversionApi;
import com.academy.paybridge.fx.api.ConversionQuoteView;
import com.academy.paybridge.fx.api.ConversionView;
import com.academy.paybridge.shared.money.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fx/conversions")
public class ConversionController {

    public record QuoteRequest(
            @NotBlank String accountNumber,
            @NotNull Currency sourceCurrency,
            @NotNull Currency destinationCurrency,
            @Positive long sourceAmountMinor) {}

    public record ConvertRequest(@NotBlank String quoteId) {}

    private final ConversionApi conversions;

    public ConversionController(ConversionApi conversions) {
        this.conversions = conversions;
    }

    /** Step 1: price the conversion. The quote is stored and expires shortly. */
    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversionQuoteView quote(@Valid @RequestBody QuoteRequest request) {
        return conversions.createQuote(request.accountNumber(), request.sourceCurrency(),
                request.destinationCurrency(), request.sourceAmountMinor());
    }

    /** Step 2: convert at the quoted rate. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversionView convert(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @Valid @RequestBody ConvertRequest request) {
        return conversions.convert(request.quoteId(), idempotencyKey);
    }

    @GetMapping("/{reference}")
    public ConversionView get(@PathVariable String reference) {
        return conversions.getByReference(reference);
    }
}
