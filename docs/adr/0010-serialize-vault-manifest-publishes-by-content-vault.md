# Serialize Vault Manifest Publishes by Content Vault

Short Optimistic Background Publish Vault Operations will serialize through one FIFO queue per Content Vault Identity because every such operation can rewrite the root manifest revision. Operation-specific target keys remain separate for coalescing or pending UI state, while Add-to-Vault import and capture workflows stay outside the queue and use a shared manifest publish gate before their one-chapter manifest writes.

**Considered Options**

- Per-operation queues were rejected because metadata, cover, thumbnail, deletion, and future manifest edits can still race through `content-vault.json`.
- Per-manga queues were rejected because the root manifest is shared across all Vault Manga and must be updated consistently.
- Putting Add-to-Vault inside the same queue was rejected because import and capture are long per-chapter workflows with staging, partial success, task records, and different result semantics.

**Consequences**

The database needs a queue serialization key separate from operation-level coalescing keys, and accepted non-terminal operation jobs need migration into the new queue where possible. The queue worker drains independent jobs and continues after terminal semantic failures, while transient infrastructure failures may retry with a bounded attempt budget. Add-to-Vault publishers must wait for the short-operation queue to drain and take the same process-local manifest publish gate before writing manifests.
