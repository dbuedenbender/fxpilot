# Maven Central publishing

FxPilot uses the Sonatype Central Portal and the `central-publishing-maven-plugin`.

## 1. Verify the namespace

1. Sign in at https://central.sonatype.com/ with GitHub.
2. Add and verify the namespace `io.github.dbuedenbender`.
3. The verification uses the GitHub account `dbuedenbender`.

## 2. Create a signing key

A dedicated GPG key is used only for release signing.

```bash
gpg --quick-generate-key "dbuedenbender <dbuedenbender@users.noreply.github.com>" rsa4096 cert,sign 2y
```

Export and upload the public key:

```bash
FPR=$(gpg --with-colons --list-secret-keys --fingerprint | awk -F: '/^fpr:/ {print $10; exit}')
gpg --armor --export "$FPR" > public-key.asc
gpg --keyserver keyserver.ubuntu.com --send-keys "$FPR"
```

Export the private key for the GitHub secret:

```bash
gpg --armor --export-secret-keys "$FPR" > private-key.asc
```

Do not commit `private-key.asc` or the passphrase.

## 3. Create a Central Portal token

In the Central Portal, open the account menu and generate a user token. It consists of a username and a password.

## 4. Configure GitHub secrets

```bash
gh secret set MAVEN_CENTRAL_USERNAME --repo dbuedenbender/fxpilot
gh secret set MAVEN_CENTRAL_PASSWORD --repo dbuedenbender/fxpilot
gh secret set MAVEN_GPG_PRIVATE_KEY --repo dbuedenbender/fxpilot < private-key.asc
gh secret set MAVEN_GPG_PASSPHRASE --repo dbuedenbender/fxpilot
```

## 5. Trigger the release workflow

For an existing tag:

```bash
gh workflow run release.yml --repo dbuedenbender/fxpilot --ref v1.0.0
```

For a new version, update `project.version` and `CHANGELOG` first, then push a matching tag:

```bash
git tag -a v1.0.1 -m "FxPilot 1.0.1"
git push origin v1.0.1
```

The workflow refuses to publish when the tag (`v1.0.1`) and `project.version` (`1.0.1`) do not match.

## 6. Publish in the Central Portal

`autoPublish` is `false`. After a successful workflow run:

1. Open the Central Portal.
2. Review the deployment.
3. Publish the release.

## Local signing test

```bash
export MAVEN_GPG_PASSPHRASE="..."
mvn -Prelease -DskipTests verify
```

This signs the jars locally without uploading them.
