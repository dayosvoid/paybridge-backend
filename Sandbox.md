Yes, your project covers both: local NGN-to-NGN transfers and inbound remittance from abroad paid out in naira. Several Nigerian payment companies offer free test environments where you sign up with an email, get test API keys, and move fake money. I can't search the web right now, so check each provider's current developer docs before class. Sandbox rules and sign-up requirements change.

## For local NGN transfers (account to account)

**Paystack** is probably the best starting point for students. Its documentation is very clear and test mode is free once you create an account. The Transfers API teaches the real Nigerian flow:

1. Resolve the account number to confirm the account holder's name.
2. Create a "transfer recipient".
3. Initiate the transfer.
4. Receive a webhook telling you whether it succeeded or failed.

That sequence is exactly the vocabulary your students need: name enquiry, beneficiary, debit, webhook and transfer status.

**Monnify** (by Moniepoint) is excellent for the *receiving* side. Its sandbox lets you create reserved (virtual) accounts, which are dedicated account numbers assigned to a customer. You then simulate money being paid into them. It also has a disbursement API for sending money out.

**Squad** (by GTCO) and **Interswitch** both have developer sandboxes too. Interswitch is worth mentioning to students because it is core infrastructure in Nigerian payments, though its onboarding is usually heavier than Paystack's.

## For cross-border remittance (USD in, NGN out)

**Flutterwave** is the strongest single choice if you want one provider for everything. Its sandbox covers local NGN transfers, collections and multi-currency transactions, and it has a large pan-African footprint.

**Fincra** focuses on cross-border payments. It is well suited to your `fx` and `remittance` modules: currency conversion and quotes, then a payout in naira.

**Korapay** also offers collections and payouts with a sandbox, including cross-border features.

## If you want real bank accounts in the simulation

**Anchor** is a banking-as-a-service platform. Its sandbox lets you create customers, open accounts and move money between them. This maps almost one-to-one onto your `customer`, `account` and `transfer` modules.

## How this fits your project

I'd suggest choosing **Paystack** for local transfers and **Flutterwave or Fincra** for the remittance flow. Two providers is enough for students to see that every payment company has a different API but the same underlying concepts. Each provider integration lives in that module's `client/` folder. You already have `fx/client`, and you could add `transfer/client` and `remittance/client` the same way. Behind a Java interface such as `TransferGateway`, the students can then swap Paystack for Flutterwave without touching the service layer. That is a powerful Spring lesson in dependency injection.

## Practical tips for class

- **Webhooks need a public URL.** Your laptop isn't reachable from the internet, so use a tunnelling tool like ngrok to expose your local app. Webhooks are where many students get stuck, so plan time for it.
- **Never commit API keys, even test keys.** Put them in environment variables and reference them from `application.yml` as `${PAYSTACK_SECRET_KEY}`. This is a good security habit to build on day one.
- **Sandboxes are optimistic.** They usually return success instantly. Real transfers can be pending for minutes, fail after succeeding, or time out. Have students handle "pending" and "failed" statuses deliberately, and use idempotency keys so a retry never sends money twice.
- **Going live is a different matter.** Test mode is typically free with just an email. Live mode requires business registration and KYC documents, so students should stay in test mode.