#!/bin/sh
# Read-only helpers for Maven Central: the Central snapshot repository (no
# credentials needed) and the Central Portal (OSSRH-compatible staging API +
# Portal API). Credentials are read from a Gradle properties file (default
# /f/gradle.properties, override with VMF_PUBLISH_PROPS) and are never printed.
#
#   scripts/central-staging.sh snapshots [version]       latest snapshot build of each artifact + the commit it was built from
#   scripts/central-staging.sh repos [open|closed|released]  staging repositories: key, state, deployment id
#   scripts/central-staging.sh status <deploymentId>      deployment state + purls (Portal API)
#   scripts/central-staging.sh published [version]        whether a release version is published on Central (Portal API)
#   scripts/central-staging.sh props                      the -P arguments for Gradle (for $(...))
set -e
cd "$(dirname "$0")/.."

SNAPSHOTS=https://central.sonatype.com/repository/maven-snapshots
GROUP_PATH=eu/mihosoft/vmf
ARTIFACTS="vmf vmf-runtime vmf-gradle-plugin eu.mihosoft.vmf.gradle.plugin vmf-jackson"

props_file() {
  PROPS="${VMF_PUBLISH_PROPS:-/f/gradle.properties}"
  [ -f "$PROPS" ] || { echo "credentials file not found: $PROPS (mount the drive or set VMF_PUBLISH_PROPS)" >&2; exit 2; }
}

bearer() {
  props_file
  user=$(grep -E '^mavenCentralUsername' "$PROPS" | sed -E 's/^[^=]*=//' | tr -d '\r')
  pass=$(grep -E '^mavenCentralPassword' "$PROPS" | sed -E 's/^[^=]*=//' | tr -d '\r')
  printf '%s:%s' "$user" "$pass" | base64 | tr -d '\n'
}

case "$1" in
  snapshots)
    VERSION="${2:-$(grep -E '^publication.version' config/common.properties | sed -E 's/^[^=]*=\s*//' | tr -d '\r ')}"
    case "$VERSION" in *-SNAPSHOT) ;; *) echo "not a snapshot version: $VERSION" >&2; exit 2 ;; esac
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    for a in $ARTIFACTS; do
      base="$SNAPSHOTS/$GROUP_PATH/$a/$VERSION"
      if ! curl -sf -o "$TMP/$a.xml" "$base/maven-metadata.xml"; then
        printf '%-32s (none)\n' "$a"
        continue
      fi
      ts=$(sed -n 's:.*<timestamp>\(.*\)</timestamp>.*:\1:p' "$TMP/$a.xml" | head -1)
      bn=$(sed -n 's:.*<buildNumber>\(.*\)</buildNumber>.*:\1:p' "$TMP/$a.xml" | head -1)
      rev=""
      if [ "$a" != "eu.mihosoft.vmf.gradle.plugin" ] && command -v unzip >/dev/null 2>&1; then
        file="$a-$(echo "$VERSION" | sed 's/-SNAPSHOT$//')-$ts-$bn.jar"
        if curl -sf -o "$TMP/$a.jar" "$base/$file"; then
          rev=$(unzip -p "$TMP/$a.jar" META-INF/MANIFEST.MF 2>/dev/null | sed -n 's/^Build-Revision: *//p' | tr -d '\r')
        fi
      fi
      printf '%-32s build %-3s %s  %s\n' "$a" "$bn" "$ts" "${rev:+commit $rev}"
    done
    ;;
  repos)
    curl -s -H "Authorization: Bearer $(bearer)" \
      "https://ossrh-staging-api.central.sonatype.com/manual/search/repositories?state=${2:-open}" \
      | python -c 'import json,sys; d=json.load(sys.stdin); [print(r.get("key"), r.get("state"), r.get("portal_deployment_id")) for r in d.get("repositories",[])] or print("(no repositories)")'
    ;;
  status)
    [ -n "$2" ] || { echo "usage: $0 status <deploymentId>" >&2; exit 2; }
    curl -s -X POST -H "Authorization: Bearer $(bearer)" \
      "https://central.sonatype.com/api/v1/publisher/status?id=$2" \
      | python -c 'import json,sys; d=json.load(sys.stdin); print("state:", d.get("deploymentState")); print("purls:", d.get("purls")); print("errors:", d.get("errors"))'
    ;;
  published)
    VERSION="${2:-$(grep -E '^publication.version' config/common.properties | sed -E 's/^[^=]*=\s*//' | tr -d '\r ')}"
    for a in $ARTIFACTS; do
      [ "$a" = "eu.mihosoft.vmf.gradle.plugin" ] && continue
      printf '%-32s %s ' "$a" "$VERSION"
      curl -s -H "Authorization: Bearer $(bearer)" \
        "https://central.sonatype.com/api/v1/publisher/published?namespace=$(echo "$GROUP_PATH" | tr / .)&name=$a&version=$VERSION"
      echo
    done
    ;;
  props)
    props_file
    grep -E '^[A-Za-z]' "$PROPS" | tr -d '\r' | sed 's/^/-P/'
    ;;
  *) echo "usage: $0 snapshots [version] | repos [state] | status <deploymentId> | published [version] | props" >&2; exit 2 ;;
esac
