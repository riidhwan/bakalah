# APK Signing Playbook

Use this playbook when setting up, verifying, backing up, or rotating the Android keystore used by GitHub Actions to sign Bakalah release APKs.

This keystore signs APK artifacts. It is separate from the GPG key used to sign release tags.

## Before You Start

You need:

- Admin access to repository Actions secrets.
- A trusted personal machine, not a shared shell or throwaway CI runner.
- `keytool` and `base64` installed.
- A maintainer password manager or another approved secret store.
- A secure place to store encrypted keystore backups outside this repository.

Do not store Android keystore files, keystore passwords, or base64 keystore exports in the repository.

## Workflow Secrets

The release workflow signs APKs with `.github/workflows/release.yml` using these repository secrets:

- `SIGNING_KEY`: base64-encoded Android keystore file.
- `ALIAS`: keystore alias.
- `KEY_STORE_PASSWORD`: keystore password.
- `KEY_PASSWORD`: key password.

Set these in:

`GitHub -> repository -> Settings -> Secrets and variables -> Actions -> Repository secrets`

Use one Android signing key for both the release and FOSS APKs unless maintainers intentionally decide to split them.

## Create A New Keystore

Do this only for a new app identity or an intentional APK signing-key rotation. Changing the APK signing key affects whether existing installations can upgrade.

1. Create the keystore on a trusted machine:

   ```shell
   keytool -genkeypair \
     -v \
     -keystore bakalah-release.jks \
     -alias bakalah \
     -keyalg RSA \
     -keysize 4096 \
     -validity 10000
   ```

2. Save the keystore password, key password, and alias in the maintainer password manager.

3. Base64-encode the keystore for GitHub Actions:

   ```shell
   base64 -w 0 bakalah-release.jks > bakalah-release.jks.base64
   ```

   On systems where `base64` does not support `-w`, use:

   ```shell
   base64 bakalah-release.jks | tr -d '\n' > bakalah-release.jks.base64
   ```

4. Add or update repository Actions secrets:

   - `SIGNING_KEY`: contents of `bakalah-release.jks.base64`
   - `ALIAS`: `bakalah`, or the alias chosen during keystore creation
   - `KEY_STORE_PASSWORD`: keystore password
   - `KEY_PASSWORD`: key password

5. Delete temporary plaintext files after the secrets and backup are handled:

   ```shell
   rm bakalah-release.jks.base64
   ```

6. Keep `bakalah-release.jks` only in the approved encrypted backup location, not in the repository.

## Verify Repository Secrets

After adding or rotating APK signing secrets:

1. Trigger a release workflow only with an intentional release tag.
2. Confirm the `Sign APK` step passes for both `release` and `foss`.
3. Confirm the workflow creates the expected renamed APKs.
4. Download the draft release APKs.
5. Verify install or upgrade behavior on a test device before publishing the draft.

If the action fails with keystore, alias, or password errors, delete the draft release if one was created, fix the repository secrets, and rerun the release according to `docs/release-process.md`.

## Back Up The Keystore

Back up:

- Android keystore file.
- Keystore alias.
- Keystore password.
- Key password.

Handle keystore material as sensitive:

- Store it encrypted.
- Keep it out of the repository.
- Do not send it through chat, issue comments, pull requests, or unencrypted email.
- Delete temporary plaintext exports after backup or transfer.

## Move To A New Secret Store

1. Retrieve the encrypted keystore backup on a trusted machine.
2. Confirm the alias is present:

   ```shell
   keytool -list -v -keystore bakalah-release.jks
   ```

3. Recreate the repository Actions secrets from the keystore and password manager values.
4. Run the repository-secret verification flow.
5. Remove temporary plaintext files.

## Rotate The APK Signing Key

Rotate only when maintainers explicitly accept the upgrade and distribution impact. APK signing key rotation can prevent existing installations from upgrading normally unless Android signing lineage or another approved migration path is in place.

Steps:

1. Decide and document why rotation is required.
2. Decide the user upgrade path before changing secrets.
3. Create the replacement keystore.
4. Back up the replacement keystore and passwords.
5. Update repository Actions secrets.
6. Verify the next draft release installs or upgrades according to the planned path.
7. Keep the old keystore backup according to the project retention decision.

## If You Suspect A Leak

1. Stop using the affected keystore immediately.
2. Remove or replace affected Actions secrets.
3. Check APKs created since the suspected leak date.
4. Delete draft releases created with compromised signing material.
5. If a compromised release was published, publish a corrective release and communicate what happened.
6. Plan APK signing-key rotation with maintainers before publishing another public APK.

## What Not To Do

- Do not add an Android keystore to Git.
- Do not paste keystore contents into issue comments, pull requests, chat, or docs.
- Do not assume GitHub account GPG keys can sign APKs.
- Do not rotate the APK signing key casually.
- Do not publish a draft release until install or upgrade behavior has been tested.
