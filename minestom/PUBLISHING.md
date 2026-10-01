# Publishing Grim for Minestom

The only published artifact is `net.aechronis:grim-minestom`. It contains the adapter,
native hooks, common engine, and libraries that are unavailable on Maven Central.
Its POM and Gradle metadata retain normal dependencies for the libraries on Central.
Consumers provide Minestom themselves and use Java 25.

## Account setup

Verify `net.aechronis` in the [Central Portal](https://central.sonatype.com/publishing/namespaces).
Create a [publishing user token](https://central.sonatype.com/account), then configure
these [GitHub Actions secrets](https://github.com/Aechronis/Grim/settings/secrets/actions):

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central user-token password |
| `MAVEN_SIGNING_KEY` | ASCII-armored private signing key |
| `MAVEN_SIGNING_PASSWORD` | Signing-key passphrase |

Use the token credentials, not the account login password. Keep private keys and
credentials out of the repository and workflow logs.

The public signing key is [signing-key.asc](signing-key.asc), fingerprint
`8C3A54A9C057451D2922327A3BAF17FECF6887B6`. It is distributed through
`hkps://keyserver.ubuntu.com`. When rotating the key, update both signing secrets,
the public key file, and this fingerprint; publish the new public key before releasing.

## Verify a release

Choose an explicit version, for example `2.3.74-minestom.1`. The upstream Grim version
comes first and the Minestom release counter distinguishes changes to this port.
Each published release version is immutable. Without `-PminestomVersion`, local
builds use a commit-qualified snapshot version.

The **Minestom Maven Central** workflow runs publication and consumer checks on
pushes to `minestom` and relevant pull requests. It does not publish those builds.
For a manual check, run that workflow with the desired version and leave **publish**
unchecked.

The equivalent local commands are:

```sh
./gradlew -PminestomOnly=true -PmavenLocalOverride=false \
  -PminestomVersion=2.3.74-minestom.1 \
  :minestom:publishAllPublicationsToLocalBuildRepository \
  -x :minestom:signMavenPublication
python3 minestom/verify-publication.py minestom/build/repository 2.3.74-minestom.1
./gradlew -p minestom/consumer-check \
  -PlibraryRepository="$PWD/minestom/build/repository" \
  -PlibraryVersion=2.3.74-minestom.1 run
./gradlew -p minestom/consumer-check -PpomOnly=true \
  -PlibraryRepository="$PWD/minestom/build/repository" \
  -PlibraryVersion=2.3.74-minestom.1 run
```

The independent consumer project uses only this local artifact repository and
Maven Central. Both metadata paths compile and start a real Minestom/Grim runtime,
initialize SQLite, register commands, and shut down cleanly. The publication check
validates coordinates, metadata, checksums, embedded classes, sources, documentation,
and separation from Minestom's own libraries.

For signed local verification, supply these environment variables using a secure
secret store, omit `-x :minestom:signMavenPublication`, and import `signing-key.asc`
into GPG before running `verify-publication.py` with `--signed`:

```text
ORG_GRADLE_PROJECT_signingInMemoryKey
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

## Publish

Run **Minestom Maven Central** on the `minestom` branch, choose the release version,
and enable **publish**. It builds and verifies the artifact first, then signs it,
uploads it, and requests release through the Central Portal. Publishing is enabled
only for manual workflow runs on that branch.

For local publishing, also provide `ORG_GRADLE_PROJECT_mavenCentralUsername` and
`ORG_GRADLE_PROJECT_mavenCentralPassword`, then run:

```sh
./gradlew -PminestomOnly=true -PmavenLocalOverride=false \
  -PminestomVersion=2.3.74-minestom.1 :minestom:publishAndReleaseToMavenCentral
```

After Central finishes publishing, consumers can use:

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("net.aechronis:grim-minestom:2.3.74-minestom.1")
    implementation("net.minestom:minestom:2026.09.12-26.2")
}
```

Available upstream dependency sources are included in the sources JAR and license notices in
both the library and sources JARs. Keep these notices and dependency pins current
when following upstream. The Minestom publishing configuration does not publish
or rename upstream Bukkit/Fabric artifacts.
