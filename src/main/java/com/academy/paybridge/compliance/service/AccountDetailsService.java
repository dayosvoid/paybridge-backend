package com.academy.paybridge.compliance.service;

import com.academy.paybridge.transfer.api.ResolvedAccountView;
import com.academy.paybridge.transfer.api.TransferApi;
import org.springframework.stereotype.Service;

@Service
public class AccountDetailsService {

    private final TransferApi transferApi;

    public AccountDetailsService(TransferApi transferApi) {
        this.transferApi = transferApi;
    }

    public ResolvedAccountView resolveAccount(
            String accountNumber, String bankCode) {
        return transferApi.resolveAccount(accountNumber, bankCode);
    }
}