# PayBridge

A Spring Boot modular monolith for learning:
local money transfer, cross-border settlement, and USD → NGN remittance.

## Run
    mvn spring-boot:run

## Module rule
A module may only use another module through its `api/` package.
Never call another module's repository or service directly.

## Modules
| Module       | Responsibility                                      |
|--------------|-----------------------------------------------------|
| shared       | Money value object, global errors, config, auditing |
| customer     | Customer onboarding and KYC profile                 |
| account      | NGN and USD accounts, balances                      |
| ledger       | Double-entry bookkeeping                            |
| transfer     | Local account-to-account transfers                  |
| fx           | USD→NGN rates, quotes, rate locking                 |
| remittance   | Inbound USD paid out in NGN                         |
| settlement   | Cross-border settlement and reconciliation          |
| compliance   | AML, sanctions screening, limits                    |
| notification | Email/SMS alerts via events                         |
