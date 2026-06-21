# Own Vault Operation Notifications by Queue State

Vault Operation Notifications will be owned by accepted non-terminal Vault Operation queue state rather than by individual WorkManager worker lifecycles. WorkManager foreground-service notifications may still be required while a worker runs, but they must use separate runtime notification ownership so worker shutdown cannot remove the user-visible notification for queued or running Vault Operations.

**Considered Options**

- Reusing one notification for queue visibility and WorkManager foreground service was rejected because WorkManager may remove foreground notifications when a worker finishes, while the durable queue can still contain accepted non-terminal Vault Operations.
- Making workers responsible for notification lifetime was rejected because workers poll the database queue and can exit between enqueue events; operation visibility must follow the durable queue state instead.
