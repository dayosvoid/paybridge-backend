package com.academy.paybridge.account.web;

import org.springframework.security.access.prepost.PreAuthorize;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.shared.money.Currency;
import java.util.List;
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
        return service.open(request.customerId(),
                request.accountName(), request.bankCode(), request.bankName());
    }

    /** The NGN balance by default, or the balance in {@code ?currency=USD}. */
    @GetMapping("/{accountNumber}")
    @PreAuthorize("@accountAccess.canView(authentication, #accountNumber)")
    public AccountView get(@PathVariable String accountNumber,
                           @RequestParam(required = false) Currency currency) {
        return currency == null
                ? service.getByNumber(accountNumber)
                : service.getByNumber(accountNumber, currency);
    }

    /** Every balance held under the account number: NGN and USD. */
    @GetMapping("/{accountNumber}/balances")
    @PreAuthorize("@accountAccess.canView(authentication, #accountNumber)")
    public List<AccountView> balances(@PathVariable String accountNumber) {
        return service.getAllByNumber(accountNumber);
    }

    @PutMapping("/{accountNumber}/status")
    public AccountView changeStatus(@PathVariable String accountNumber,
                                    @Valid @RequestBody ChangeStatusRequest request) {
        return service.changeStatus(accountNumber, request.status());
    }
}