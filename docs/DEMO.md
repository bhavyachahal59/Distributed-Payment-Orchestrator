# Five-minute demo

1. `mvn clean test`
2. `mvn spring-boot:run`
3. Create payment using `merchantId=timeout-demo`, a unique `Idempotency-Key`, and `amount=1250.00`.
4. `POST /api/v1/payments/{id}/process` -> `REQUIRES_RECONCILIATION` (unless scheduler has already processed it).
5. `POST /api/v1/payments/{id}/reconcile` -> `SUCCEEDED`, attempts remain `1`.
6. Retry the original create request with the same key and payload -> same payment ID.
7. Reuse the key with a different amount -> HTTP 409.
8. Inspect `/events` to see the audit trail.

**Note:** The scheduler may process payments before a manual call. Inspect `GET /api/v1/payments/{id}` first. The sandbox is stateful; use new keys for new runs.
