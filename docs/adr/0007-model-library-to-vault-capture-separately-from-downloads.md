# Model Library-to-Vault Capture separately from Downloads

Library-to-Vault Capture adds selected chapters from source-backed Library manga to the Content Vault without making normal Downloads part of the capture workflow. Missing, queued, downloading, or failed chapters are fetched through capture-owned staging, while chapters already downloaded before capture starts are copied into staging and left untouched; capture then publishes canonical CBZ content with unconditional tall-image splitting, records partial success one chapter at a time, reports failed chapter details, and cleans staging best-effort.

This keeps normal Downloads user-owned and preference-driven, while Vault Capture remains explicit, disposable, cancellable, and safe for long source-backed operations where partial network failures are expected.
