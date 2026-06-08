# Model Sensitive Vault Content Through Labels

Bakalah will model default-hidden Vault Manga through Sensitive Vault Labels: a Vault Label can be marked sensitive in the remote Vault Catalogue, and any Vault Manga with at least one Sensitive Vault Label is excluded from the default Vault Surface unless the user explicitly includes sensitive content or directly filters to that sensitive label. The include-sensitive viewing choice is device-local, while label sensitivity is vault-owned metadata so the meaning of a label travels with the Content Vault instead of depending on magic label names or per-device hidden lists.

**Considered Options**

- Hard-code specific label names such as `18+`: rejected because label names are user-owned, renameable, and culturally/contextually variable.
- Store hidden state directly on Vault Manga: rejected because visibility should be derived from current organization labels rather than duplicated per manga.
- Keep sensitive labels device-local only: rejected because another device would not understand that the same label should be hidden by default.
- Reuse Library Categories: rejected because Vault Labels are separate from Library organization and the Vault Feature is intentionally independent from Library.
