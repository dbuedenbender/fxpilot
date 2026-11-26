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
gh workflow run release.yml --repo dbuedenbender/fxpilot --ref v1.1.1
```

For a new version, update `project.version` and `CHANGELOG` first, then push a matching tag:

```bash
git tag -a v1.1.1 -m "FxPilot 1.1.1"
git push origin v1.1.1
```

The workflow refuses to publish unless it runs on the tag that matches `project.version` (`v1.1.1` for `1.1.1`); a manual dispatch from a branch fails before anything is deployed.

## 6. Publish in the Central Portal

`autoPublish` is `false`. After a successful workflow run:

1. Open the Central Portal.
2. Review the deployment.
3. Publish the release.

## 7. Update the launcher scripts

`scripts/fxpilot-mcp.cmd`, `scripts/fxpilot-mcp.sh` and `scripts/fxpilot-jar.ps1` download the default version and check it against a SHA-256 stored in the script.

Run this after the deployment is published in the portal, not before: the hash comes from `fxpilot-<version>.jar.sha256` on Maven Central, and a launcher that checks the new version against the previous hash would reject every download.

```bash
scripts/pin-release.sh 1.1.1
```

```powershell
scripts\pin-release.ps1 -Version 1.1.1
```

Both scripts set the version and the hash in all three files (`-Sha256` / a second argument pins a hash you already have). `LauncherScriptsTest` fails if the three files disagree. The release workflow prints a warning as long as the launchers still pin the previous version.

## Local signing test

```bash
export MAVEN_GPG_PASSPHRASE="..."
./mvnw -Prelease -DskipTests verify
```

This signs the jars locally without uploading them.
