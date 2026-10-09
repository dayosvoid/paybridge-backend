package com.academy.paybridge.ledger.web;

import com.academy.paybridge.ledger.api.StatementPage;
import com.academy.paybridge.ledger.service.StatementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/accounts/{accountNumber}/transactions")
public class StatementController {

    private final StatementService statements;

    public StatementController(StatementService statements) {
        this.statements = statements;
    }

    @GetMapping
    public StatementPage get(@PathVariable String accountNumber,
                             @RequestParam(required = false) String type,
                             @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return statements.statement(accountNumber, type, from, to, page, size);
    }
}
