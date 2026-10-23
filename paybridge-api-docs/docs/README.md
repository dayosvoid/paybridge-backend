# PayBridge API documentation bundle

Generated from the uploaded `paybridge-current.zip` snapshot by inspecting all Java controller mappings, DTOs, security configuration, selected services/providers, enums, scheduled jobs and tests.

- `API_REFERENCE.md`: route-by-route request, response, header, validation, access and side-effect reference.
- `API_TESTING_GUIDE.md`: dependency-ordered manual test sequence using Windows PowerShell `curl.exe` and Postman.
- `API_FLOW_GUIDE.md`: Mermaid sequence/state/flow diagrams for auth, transfers, FX, remittance, webhooks, settlement and reconciliation.
- `PayBridge.postman_collection.json`: importable Postman collection with 33 business endpoint requests grouped into seven folders.
- `PayBridge.postman_environment.json`: local environment template with variables; fill in tokens/account references after creating them.

## Scope and verification

- Static source scan found 33 unique controller mappings; this agrees with `RoleAccessTest`'s explicit route inventory.
- Postman collection JSON and environment JSON parse successfully.
- No Java/application code was changed. The test suite was not executed as part of documentation generation; existing test classes were inspected by name and `RoleAccessTest` was read in detail.
- The collection intentionally uses placeholders for webhook signatures. Follow `API_TESTING_GUIDE.md` to calculate correct local HMAC values; placeholder signatures will fail.
- Settlement is scheduled, not exposed as an HTTP endpoint, and `application.yml` disables it by default.
