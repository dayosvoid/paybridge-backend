package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferPage;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferApi transfers;

    public TransferController(TransferApi transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferView create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                               @Valid @RequestBody TransferRequest request) {
        return transfers.initiate(request, idempotencyKey);
    }

    @GetMapping("/{reference}")
    public TransferView get(@PathVariable String reference) {
        return transfers.getByReference(reference);
    }

    @GetMapping
    public TransferPage list(@RequestParam String account,
                             @RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return transfers.list(account, status, page, size);
    }
}