package com.academy.paybridge.compliance.web;

import com.academy.paybridge.compliance.service.AccountDetailsService;
import com.academy.paybridge.transfer.api.ResolvedAccountView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/kyc")
public class AccountDetailsController {

    private final AccountDetailsService accountDetailsService;

    public AccountDetailsController(
            AccountDetailsService accountDetailsService) {
        this.accountDetailsService = accountDetailsService;
    }

    @GetMapping("/account-details")
    public ResolvedAccountView getAccountDetails(
            @RequestParam String accountNumber,
            @RequestParam String bankCode) {
        return accountDetailsService.resolveAccount(accountNumber, bankCode);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest(
            IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", exception.getMessage()));
    }
}