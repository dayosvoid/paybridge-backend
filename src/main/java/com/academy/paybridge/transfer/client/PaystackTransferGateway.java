package com.academy.paybridge.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Component
@Profile("paystack")
public class PaystackTransferGateway implements TransferGateway {

    private final RestClient client;

    public PaystackTransferGateway(@Value("${paystack.base-url}") String baseUrl,
                                   @Value("${paystack.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "PAYSTACK_SECRET_KEY is not set. Set it as an environment variable.");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ---------- response shapes (only the fields we use) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveResponse(boolean status, String message, ResolveData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveData(@JsonProperty("account_number") String accountNumber,
                       @JsonProperty("account_name") String accountName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecipientResponse(boolean status, String message, RecipientData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecipientData(@JsonProperty("recipient_code") String recipientCode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransferResponse(boolean status, String message, TransferData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransferData(String status,
                        @JsonProperty("transfer_code") String transferCode,
                        String reference) {}

    // ---------- TransferGateway ----------

    @Override
    public AccountName resolveAccount(String accountNumber, String bankCode) {
        try {
            ResolveResponse response = client.get()
                    .uri(b -> b.path("/bank/resolve")
                            .queryParam("account_number", accountNumber)
                            .queryParam("bank_code", bankCode)
                            .build())
                    .retrieve()
                    .body(ResolveResponse.class);
            if (response == null || response.data() == null) {
                throw new IllegalArgumentException("Could not verify account " + accountNumber);
            }
            return new AccountName(accountNumber, response.data().accountName());
        } catch (HttpClientErrorException e) {
            throw new IllegalArgumentException(
                    "Could not verify account " + accountNumber + ": " + e.getResponseBodyAsString());
        }
    }

    @Override
    public GatewayResult send(TransferInstruction in) {
        String reference = in.reference().toLowerCase();

        // 1. Create the recipient. Nothing has moved yet, so any failure here is a clean FAILED.
        String recipientCode;
        try {
            RecipientResponse recipient = client.post()
                    .uri("/transferrecipient")
                    .body(Map.of(
                            "type", "nuban",
                            "name", in.accountName() == null ? "Recipient" : in.accountName(),
                            "account_number", in.accountNumber(),
                            "bank_code", in.bankCode(),
                            "currency", in.amount().currency().name()))
                    .retrieve()
                    .body(RecipientResponse.class);
            if (recipient == null || recipient.data() == null) {
                return new GatewayResult(GatewayStatus.FAILED, null, "Paystack returned no recipient");
            }
            recipientCode = recipient.data().recipientCode();
        } catch (RuntimeException e) {
            return new GatewayResult(GatewayStatus.FAILED, null, "Could not create recipient: " + describe(e));
        }

        // 2. Initiate the transfer. Amount is already in kobo.
        try {
            TransferResponse response = client.post()
                    .uri("/transfer")
                    .body(Map.of(
                            "source", "balance",
                            "amount", in.amount().minorUnits(),
                            "recipient", recipientCode,
                            "reference", reference,
                            "reason", "PayBridge transfer"))
                    .retrieve()
                    .body(TransferResponse.class);
            if (response == null || response.data() == null) {
                throw new IllegalStateException("Paystack returned an unreadable response");
            }
            return new GatewayResult(
                    mapStatus(response.data().status()),
                    response.data().transferCode(),
                    response.message());
        } catch (HttpClientErrorException e) {
            // 4xx: Paystack rejected the request before creating a transfer, so nothing was sent
            return new GatewayResult(GatewayStatus.FAILED, null, "Paystack rejected the transfer: " + describe(e));
        }
        // Anything else (timeout, 5xx, unreadable body) propagates. TransferService keeps the
        // transfer PENDING and does NOT refund, because the money may really have left.
    }

    /** Paystack's transfer statuses mapped onto ours. Unknown values stay PENDING (safe default). */
    static GatewayStatus mapStatus(String paystackStatus) {
        if (paystackStatus == null) {
            return GatewayStatus.PENDING;
        }
        return switch (paystackStatus.toLowerCase()) {
            case "success" -> GatewayStatus.SUCCESS;
            case "failed", "reversed", "rejected", "blocked", "abandoned" -> GatewayStatus.FAILED;
            default -> GatewayStatus.PENDING;   // pending, otp, received, anything new
        };
    }

    private static String describe(RuntimeException e) {
        return e instanceof HttpClientErrorException h ? h.getResponseBodyAsString() : e.getMessage();
    }

    @Override
    public GatewayResult verify(String reference) {
        try {
            TransferResponse response = client.get()
                    .uri("/transfer/verify/{reference}", reference.toLowerCase())
                    .retrieve()
                    .body(TransferResponse.class);
            if (response == null || response.data() == null) {
                throw new IllegalStateException("Paystack returned an unreadable verify response");
            }
            return new GatewayResult(
                    mapStatus(response.data().status()),
                    response.data().transferCode(),
                    response.message());
        } catch (HttpClientErrorException.NotFound e) {
            return new GatewayResult(GatewayStatus.NOT_FOUND, null, "Paystack has no record of this reference");
        }
        // Everything else (401, 5xx, timeouts) propagates: the reconciler skips and retries next run.
    }
}
