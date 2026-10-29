#!/usr/bin/env bash
# The releases the new-releases workflow adds to the dataset: those of quarkusio/quarkus's GitHub releases published at
# least 6 hours ago (or of REQUESTED, a comma-separated list) that Maven Central serves for the almanac's selection
# (every release, pre-releases included) and that the site data does not have yet. The 6 hours let every jar of a
# release reach Maven Central before it is read: a jar missing then would be missing from the dataset for good.
# Prints, in the format of $GITHUB_OUTPUT, versions=<JSON array> and count=<n>, and platforms=<n>: how many releases of
# the dataset lack a platform BOM that Maven Central now serves (it often comes out days after the release). The
# releases of the dataset without API data (it is optional) are a notice only. Needs java, node and gh (with
# GH_TOKEN).
set -euo pipefail

JAR=${JAR:-target/quarkus-almanac.jar}
PAUSE=${PAUSE:-0.001}
WORK=${WORK:-work}

if [ -n "${REQUESTED:-}" ]; then
    wanted=$(tr ', ' '\n\n' <<< "$REQUESTED" | grep -v '^$' || true)
else
    wanted=$(gh api 'repos/quarkusio/quarkus/releases?per_page=100' \
        --jq '.[] | select((.draft | not) and .published_at < (now - 6 * 3600 | todate)) | .tag_name')
fi
# One release a line (version, kind, stream...), then a count; the log goes to stderr. The empty dataset leaves
# versions no list to fall back on, so a maven-metadata.xml that cannot be read fails the run instead of finding nothing.
served=$(java -jar "$JAR" versions --select all --pre-releases all --pause "$PAUSE" --work "$WORK" \
    --dataset "$(mktemp -d)" | awk 'NF >= 2 { print $1 }')
have=$(node -e 'for (const r of JSON.parse(require("fs").readFileSync("site/data/versions.json", "utf8")).versions) console.log(r.v)')

new=$(LC_ALL=C comm -12 <(LC_ALL=C sort -u <<< "$wanted") <(LC_ALL=C sort -u <<< "$served") \
    | LC_ALL=C comm -23 - <(LC_ALL=C sort -u <<< "$have") | sort -V)
if [ -n "${REQUESTED:-}" ]; then
    while read -r v; do
        [ -n "$v" ] || continue
        if ! grep -qxF "$v" <<< "$served"; then
            echo "::notice::$v is left out: Maven Central does not serve it for the almanac" >&2
        elif grep -qxF "$v" <<< "$have"; then
            echo "::notice::$v is left out: the site data has it already" >&2
        fi
    done <<< "$wanted"
fi
json=$(node -e 'process.stdout.write(JSON.stringify(require("fs").readFileSync(0, "utf8").split("\n").filter(Boolean)))' <<< "$new")
count=$(grep -c . <<< "$new" || true)

# a platform list that cannot be read only warns: the new releases go ahead, and a later run adds the platform BOMs
if ! platforms=$(java -jar "$JAR" platform --missing --versions all --pre-releases all --pause "$PAUSE" --work "$WORK" \
    | tr -d '\r'); then
    echo "::warning::the platform BOMs to add could not be listed; a later run adds them" >&2
    platforms=
fi
platform_count=$(grep -c . <<< "$platforms" || true)

# the releases of the dataset with classes but no API data: read from the dataset alone, a notice, never a failure
if missing_api=$(java -jar "$JAR" api --missing --pause "$PAUSE" --work "$WORK" | tr -d '\r'); then
    api_count=$(grep -c . <<< "$missing_api" || true)
    if [ "$api_count" != 0 ]; then
        shown=$(head -n 20 <<< "$missing_api" | paste -sd' ' -)
        if [ "$api_count" -gt 20 ]; then shown="$shown ..."; fi
        how="api --versions <list> extracts them, dataset import --kinds api adds them"
        echo "::notice::$api_count releases of the dataset have no API data ($shown); $how" >&2
    fi
else
    echo "::warning::the releases without API data could not be listed" >&2
fi

echo "new releases: $count${new:+ ($(paste -sd' ' - <<< "$new"))}" >&2
echo "platform BOMs to add to releases of the dataset: $platform_count${platforms:+ ($(paste -sd' ' - <<< "$platforms"))}" >&2
echo "versions=$json"
echo "count=$count"
echo "platforms=$platform_count"
