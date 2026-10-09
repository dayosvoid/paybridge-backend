package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.shared.money.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    public record OpenAccountRequest(
            @NotNull Long customerId,
            @NotNull Currency currency,
            @Size(max = 100) String accountName,
            @Pattern(regexp = "\\d{3,6}", message = "must be 3 to 6 digits") String bankCode,
            @Size(max = 100) String bankName) {}

    public record ChangeStatusRequest(@NotNull AccountStatus status) {}

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView open(@Valid @RequestBody OpenAccountRequest request) {
        return service.open(request.customerId(), request.currency(),
                request.accountName(), request.bankCode(), request.bankName());
    }

    @GetMapping("/{accountNumber}")
    public AccountView get(@PathVariable String accountNumber) {
        return service.getByNumber(accountNumber);
    }

    @PutMapping("/{accountNumber}/status")
    public AccountView changeStatus(@PathVariable String accountNumber,
                                    @Valid @RequestBody ChangeStatusRequest request) {
        return service.changeStatus(accountNumber, request.status());
    }
}