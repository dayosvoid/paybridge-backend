package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceRequest;
import com.academy.paybridge.remittance.api.RemittanceView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/remittances")
public class RemittanceController {

    private final RemittanceApi remittances;

    public RemittanceController(RemittanceApi remittances) {
        this.remittances = remittances;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RemittanceView create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @Valid @RequestBody RemittanceRequest request) {
        return remittances.initiate(request, idempotencyKey);
    }

    @GetMapping("/{reference}")
    public RemittanceView get(@PathVariable String reference) {
        return remittances.getByReference(reference);
    }
}
