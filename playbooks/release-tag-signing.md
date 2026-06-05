# Release Tag Signing Playbook

Use this playbook when setting up or using the maintainer GPG key that signs Bakalah release tags.

This key only signs Git tags. It does not sign APKs, and adding its public key to GitHub does not give GitHub Actions access to sign artifacts.

## Before You Start

You need:

- Access to the GitHub account that can create protected `v*` release tags.
- A trusted personal machine, not a shared shell or throwaway CI runner.
- `gpg` and `git` installed.
- A verified GitHub email that matches the GPG signing key identity.
- A secure place to store encrypted GPG backups outside this repository.

Do not continue on a machine you do not control.

## Setup

1. Check whether you already have a usable signing key:

   ```shell
   gpg --list-secret-keys --keyid-format=long
   ```

2. If there is no suitable secret key, create one:

   ```shell
   gpg --full-generate-key
   ```

3. Use these choices unless you have a stronger personal GPG setup already:

   - Key type: ECC with signing support.
   - Expiration: one or two years.
   - Name and email: your maintainer Git identity.
   - Passphrase: yes, unless this is only a disposable local test key.

4. Copy the long key ID from the `sec` line.

5. Configure Git to use the key:

   ```shell
   git config --global user.signingkey KEY_ID
   git config --global tag.gpgsign true
   git config --global gpg.program gpg
   ```

6. If the key should only be used for Bakalah, run the same `git config` commands inside the repository without `--global`.

## Register The Public Key With GitHub

1. Export the public key:

   ```shell
   gpg --armor --export KEY_ID
   ```

2. Add the exported block to GitHub:

   `GitHub -> Settings -> SSH and GPG keys -> New GPG key`

3. Confirm the key email is verified on the GitHub account.

4. Do not upload or paste any private-key export. Public key blocks start with `BEGIN PGP PUBLIC KEY BLOCK`.

## Test Signing

Run this once after setup and again after moving to a new machine:

```shell
git tag -s test-signing -m "test signing"
git tag -v test-signing
git tag -d test-signing
```

Expected result:

- GPG asks for your passphrase if the agent has not cached it.
- `git tag -v test-signing` reports a good signature.
- The test tag is deleted locally.

Do not push test tags.

## Sign A Release Tag

Use this only after the release-prep pull request is merged.

1. Update local `main`:

   ```shell
   git fetch origin main --tags
   git switch main
   git pull --ff-only origin main
   ```

2. Confirm the release-prep commit is at `HEAD`:

   ```shell
   git log --oneline -n 3
   ```

3. Create the signed annotated tag:

   ```shell
   git tag -s vMAJOR.MINOR.PATCH -m "Bakalah vMAJOR.MINOR.PATCH"
   ```

4. Verify the signature:

   ```shell
   git tag -v vMAJOR.MINOR.PATCH
   ```

5. Confirm the tag points at `HEAD`:

   ```shell
   git rev-list -n 1 vMAJOR.MINOR.PATCH
   git rev-parse HEAD
   ```

   The two hashes must match for the normal release flow.

6. Push only the tag:

   ```shell
   git push origin vMAJOR.MINOR.PATCH
   ```

7. Watch GitHub Actions until the release workflow creates a draft release.

## Back Up The GPG Key

Export GPG backup files into a temporary private directory:

```shell
gpg --armor --export-secret-keys KEY_ID > private-key.asc
gpg --armor --export-secret-subkeys KEY_ID > private-subkeys.asc
gpg --output revoke.asc --gen-revoke KEY_ID
```

Handle `private-key.asc`, `private-subkeys.asc`, and `revoke.asc` as sensitive files:

- Store them encrypted.
- Keep them out of the repository.
- Delete temporary plaintext exports after backup or transfer.
- Do not send them through chat, issue comments, pull requests, or unencrypted email.

## Move To A New Machine

1. Import the encrypted backup on the new trusted machine.
2. Verify the imported secret key exists:

   ```shell
   gpg --list-secret-keys --keyid-format=long
   ```

3. Configure Git with the imported key ID.
4. Run the local signing test.
5. Remove any temporary plaintext backup files.

## Rotate The GPG Key

Rotate when:

- The key is near expiration.
- You lose confidence in the machine where it lived.
- A maintainer leaves release duties.
- The passphrase, backup, or private key may have leaked.

Steps:

1. Create or choose the replacement key.
2. Register the new public key with GitHub.
3. Run the local signing test.
4. Back up the new key and revocation certificate.
5. Stop using the old key.
6. Revoke the old key if it is compromised or should no longer be trusted.
7. Remove the old public key from GitHub when it should no longer verify future release tags.

## If You Suspect A Leak

1. Stop using the key immediately.
2. Remove the public key from GitHub.
3. Revoke the key if you have a revocation certificate.
4. Check release tags created since the suspected leak date.
5. Delete draft releases created from untrusted tags.
6. If an untrusted release was published, publish a corrective release and communicate what happened.

Do not rewrite published release tags unless maintainers explicitly decide the security risk is worse than the disruption. Prefer a new corrective tag and release once users may have seen or downloaded artifacts.

## What Not To Do

- Do not use another maintainer's private key.
- Do not store a personal maintainer GPG private key in CI secrets.
- Do not assume adding a GPG key to GitHub gives Actions access to sign APKs.
- Do not sign release tags from a machine you do not control.
- Do not push release tags before verifying the tag signature and target commit.
- Do not push test tags.
