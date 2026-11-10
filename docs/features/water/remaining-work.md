# Remaining Work and Limits

- A POST retry idempotency key is not part of this MVP. If a network connection fails after the server commits, the client cannot safely infer whether the log was created. Inspect the daily history before a manual retry.
- The account has no stored timezone preference. GET requests use the device-supplied IANA timezone; viewing the same instant under another timezone can place it on a different calendar date by design.
- Goals are fixed at 2,000 ml/day. Goal CRUD, log edit/delete, reminders, offline synchronization, and pagination are not implemented.
- A device or emulator walkthrough remains to verify touch interaction, layout, accessibility, and network error presentation against the live backend. MongoDB restart persistence was verified by restarting the backend container; see [verification-report.md](verification-report.md).
