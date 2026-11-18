# Releasing VMF

How VMF is published to Maven Central (releases and snapshots) and to the
Gradle Plugin Portal. GitHub Actions only builds and tests
(`.github/workflows/gradle.yml`); publishing is done by hand with the scripts
below.

## Artifacts and versions

| Artifact (group `eu.mihosoft.vmf`) | Build |
|---|---|
| `vmf` (code generator) | root build, project `:vmf` (`core/`) |
| `vmf-runtime` | root build, project `:vmf-runtime` (`runtime/`) |
| `vmf-gradle-plugin` + marker `eu.mihosoft.vmf.gradle.plugin` | root build, project `:gradle-plugin` |
| `vmf-jackson` | separate build in `jackson/` |

`vmfedit` (`vmf-edit` on Central) has its own publishing and is not covered here.

One version for all of them: `publication.version` in
`config/common.properties`. Between releases `master` carries the next version
as `-SNAPSHOT`.

The Sonatype namespace is `eu.mihosoft`. It belongs to an organization that
also publishes other projects, and the Central Portal enforces **monthly
publishing limits per organization** (about 7 release events, ~1,100 files and
~78 MB per calendar month; the Usage Center at
<https://central.sonatype.com/publishing/usage> is authoritative). Releases
are therefore batched; snapshots are the channel in between.

## Credentials

The scripts read a Gradle properties file and pass its entries as `-P`
arguments. Nothing is copied into `~/.gradle`. The default is
`/f/gradle.properties` (`F:\gradle.properties`, an encrypted drive: mount it
first); set `VMF_PUBLISH_PROPS` to use another file.

| Property | Used for |
|---|---|
| `mavenCentralUsername`, `mavenCentralPassword` | Central Portal user token (uploads, staging API, Portal API) |
| `signing.keyId`, `signing.password`, `signing.secretKeyRingFile` | GPG signatures (required for releases; snapshots are signed too if present) |
| `gradle.publish.key`, `gradle.publish.secret` | Gradle Plugin Portal |

## Snapshots

Snapshots go to the Central snapshot repository,
<https://central.sonatype.com/repository/maven-snapshots/> (SNAPSHOTs are
enabled for the namespace in the Portal). They are plain uploads: no
validation, no staging, no hand-off. Every push adds a timestamped build that
consumers pick up as the newest `-SNAPSHOT`, and builds are deleted after 90
days. Snapshot uploads **do not count toward the monthly limits** (verified
with VMF-Text on 2026-10-20: two snapshot pushes left the organization's
numbers in the Usage Center unchanged; snapshots are listed under a separate
`eu.mihosoft.snapshots` row).

Publish a snapshot from a clean `master` whose `sh ./build-and-test-all.sh` is
green:

```sh
sh scripts/publish-central.sh
```

The script uploads `vmf`, `vmf-runtime`, `vmf-gradle-plugin` (+ marker), then
publishes them to mavenLocal (the `jackson/` build resolves the plugin and
`vmf` from there), uploads `vmf-jackson`, and finally lists what the snapshot
repository serves. To check later which build is live and which commit it was
built from (`Build-Revision` in the jar manifests), no credentials needed:

```sh
sh scripts/central-staging.sh snapshots
```

Publish snapshots from `master` only, so that the snapshot repository always
shows a merged state; try branches with `publishToMavenLocal`.

### Using a snapshot

`settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven {
            url = uri('https://central.sonatype.com/repository/maven-snapshots/')
            mavenContent { snapshotsOnly() }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
```

`build.gradle`:

```groovy
plugins {
    id 'eu.mihosoft.vmf' version '0.2.12-SNAPSHOT'
}

repositories {
    mavenCentral()
    maven {
        url = uri('https://central.sonatype.com/repository/maven-snapshots/')
        mavenContent { snapshotsOnly() }
    }
}

dependencies {
    // only if needed
    implementation 'eu.mihosoft.vmf:vmf-jackson:0.2.12-SNAPSHOT'
}
```

A snapshot version of the VMF plugin adds the snapshot repository for `vmf`
and `vmf-runtime` by itself (since #89); the project repository above is
needed for other snapshot dependencies such as `vmf-jackson`. Gradle caches
snapshots for 24 hours: use `--refresh-dependencies` to get a build that was
published in the meantime. This includes CI runs that restore a Gradle cache.

## Releases

Ask before publishing a release to Central: the monthly limits are shared.

A release must not depend on `-SNAPSHOT` versions: check the dependencies
first (`grep -n -- "-SNAPSHOT['\"]" build.gradle */build.gradle` lists the
literal ones; the VMF version itself comes from `config/common.properties`).
The current VMF snapshot uses VCollections `0.3.6-SNAPSHOT` for identity-aware
sort events. Before releasing VMF, release VCollections with this fix and update
`vcollectionsVersion` in `runtime/build.gradle` to the released version.

1. **Git** (on `master`, after `sh ./build-and-test-all.sh` is green):
   - set `publication.version=X` in `config/common.properties`,
     `git commit config/common.properties -m "release X"`
   - `git tag vX`
   - set the next `Y-SNAPSHOT`, `git commit config/common.properties -m "development version Y-SNAPSHOT"`
   - `git push origin master && git push origin vX`
2. **Maven Central**, from the tag (`git checkout vX`):

   ```sh
   sh scripts/publish-central.sh            # hand-off for manual review: press "Publish" on the Portal
   sh scripts/publish-central.sh automatic  # hand-off with publishing_type=automatic
   ```

   All four artifacts are uploaded into one staging repository and handed off
   once, so a release is **one** Portal deployment (one release event). The
   script looks up the key of the open staging repository
   (`scripts/central-staging.sh repos open`) and passes it to
   `:vmf:releaseToCentralPortal -PrepositoryKey=<key>`. The hand-off without a
   key (`.../manual/upload/defaultRepository/eu.mihosoft`) failed with HTTP 400
   for this account. `releaseToCentralPortal` skips `-SNAPSHOT` versions.

   Check the result: <https://central.sonatype.com/publishing/deployments>,
   `sh scripts/central-staging.sh status <deploymentId>`, or
   `sh scripts/central-staging.sh published X` (per artifact, Portal API with the
   token; `"published":true` after a minute or two; repo1.maven.org follows
   within about 30 minutes).
3. **Gradle Plugin Portal**, once `vmf` X is published on Central:

   ```sh
   sh ./gradlew :gradle-plugin:publishPlugins --no-daemon $(sh scripts/central-staging.sh props)
   ```

   Group and version come from `gradle-plugin/gradle/publishing.gradle`
   (since #88). 0.2.11 was published with
   `-Pgroup=eu.mihosoft.vmf -Pversion=0.2.11`, before that change.
   `publishPlugins --validate-only` checks without publishing.
4. `git checkout master`.
