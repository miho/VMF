#!/bin/sh
# Publishes the version in config/common.properties to Maven Central:
# vmf, vmf-runtime and vmf-gradle-plugin (+ plugin marker) from the root build,
# and vmf-jackson.
#
# - a -SNAPSHOT version goes to the Central snapshot repository: plain uploads,
#   no staging, no hand-off, and it does not count toward the Portal's monthly
#   publishing limits
# - a release version is uploaded to the staging API and handed off as ONE Portal
#   deployment (counts as one release event)
#
# Run from a clean checkout of the commit to publish, after
# `sh ./build-and-test-all.sh` is green on it.
#
#   sh scripts/publish-central.sh            # release: manual review, press Publish on the Portal
#   sh scripts/publish-central.sh automatic  # release: hand-off with publishing_type=automatic
#
# Credentials: VMF_PUBLISH_PROPS (default /f/gradle.properties), see
# docs/RELEASING.md. The Gradle Plugin Portal step (publishPlugins) is separate.
set -e
cd "$(dirname "$0")/.."
VERSION=$(grep -E '^publication.version' config/common.properties | sed -E 's/^[^=]*=\s*//' | tr -d '\r ')
PROPS=$(sh scripts/central-staging.sh props)
REV=$(git rev-parse --short HEAD)
echo "==> publishing VMF $VERSION (commit $REV)"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "WARNING: tracked files have uncommitted changes, the artifacts will not match commit $REV"
fi

echo "==> vmf, vmf-runtime, vmf-gradle-plugin (+ marker): upload"
sh ./gradlew publishAllPublicationsToMavenCentralRepository --no-daemon $PROPS
echo "==> publish to mavenLocal (the vmf-jackson build resolves the plugin and vmf $VERSION from there)"
sh ./gradlew publishToMavenLocal --no-daemon -q
echo "==> vmf-jackson: upload"
(cd jackson && sh ./gradlew publishAllPublicationsToMavenCentralRepository --no-daemon $PROPS)

case "$VERSION" in
  *-SNAPSHOT)
    echo "==> SNAPSHOT: no staging hand-off needed; on https://central.sonatype.com/repository/maven-snapshots/ within a minute:"
    sh scripts/central-staging.sh snapshots "$VERSION"
    exit 0 ;;
esac

echo "==> staging repositories (open)"
sh scripts/central-staging.sh repos open
KEY=$(sh scripts/central-staging.sh repos open | awk 'NR==1 && $1 != "(no" {print $1}')
[ -n "$KEY" ] || { echo "no open staging repository found" >&2; exit 1; }
TYPE="${1:-user_managed}"
echo "==> hand-off of $KEY (publishing_type=$TYPE)"
sh ./gradlew :vmf:releaseToCentralPortal -PsonatypeNamespace=eu.mihosoft -PrepositoryKey="$KEY" -PpublishingType="$TYPE" --no-daemon $(sh scripts/central-staging.sh props | grep mavenCentral)
echo "==> released repositories"
sh scripts/central-staging.sh repos released
DEP=$(sh scripts/central-staging.sh repos released | awk '$3 != "None" {print $3}' | head -1)
[ -n "$DEP" ] && { echo "==> deployment $DEP"; sh scripts/central-staging.sh status "$DEP"; }
echo "==> done. Check https://central.sonatype.com/publishing/deployments and the Usage Center."
