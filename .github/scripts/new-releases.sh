#!/usr/bin/env bash
# The releases the new-releases workflow adds to the dataset: those of quarkusio/quarkus's GitHub releases (or of
# REQUESTED, a comma-separated list) that Maven Central serves for the almanac's selection (every release, pre-releases
# included) and that the site data does not have yet. Nothing waits for the jars: the BOM of each release measured so
# far was on Maven Central before its GitHub release, by about 2 hours at least since 2022, and the extract job's
# available command fails on what Maven Central lacks, for the next run to read the release again: a jar missing when
# it is read would be missing from the dataset for good.
# Prints, in the format of $GITHUB_OUTPUT, versions=<JSON array> and count=<n>, and platforms=<n>: how many releases of
# the dataset lack a platform BOM that Maven Central now serves (it often comes out days after the release). What stays
# out of those lists is a notice only: the versions that only GitHub or only Maven Central has, and the releases without
# API data or a dependency profile (both optional). When main's site is not the one of the last deploy, a deploy is
# started. Needs java, node, git and gh (with GH_TOKEN, and actions: write to start the deploy).
set -euo pipefail

JAR=${JAR:-target/quarkus-almanac.jar}
PAUSE=${PAUSE:-0.001}
WORK=${WORK:-work}
# what the notices name: on its own, assemble leaves out every pre-release but the newest
ASSEMBLE='assemble --offline --pre-releases all'

# The lines of $1 that $2 does not have.
minus() {
    LC_ALL=C comm -23 <(LC_ALL=C sort -u <<< "$1") <(LC_ALL=C sort -u <<< "$2")
}

# The list $1 on one line as --versions takes it, the first 20 of it.
named() {
    local shown
    shown=$(head -n 20 <<< "$1" | paste -sd, -)
    if [ "$(grep -c . <<< "$1")" -gt 20 ]; then shown="$shown ..."; fi
    echo "$shown"
}

if [ -n "${REQUESTED:-}" ]; then
    wanted=$(tr ', ' '\n\n' <<< "$REQUESTED" | grep -v '^$' || true)
else
    wanted=$(gh api 'repos/quarkusio/quarkus/releases?per_page=100' --jq '.[] | select(.draft | not) | .tag_name')
fi
# One release a line (version, kind, stream...), then a count; the log goes to stderr. The empty dataset leaves
# versions no list to fall back on, so a maven-metadata.xml that cannot be read fails the run instead of finding nothing.
served=$(java -jar "$JAR" versions --select all --pre-releases all --pause "$PAUSE" --work "$WORK" \
    --dataset "$(mktemp -d)" | awk 'NF >= 2 && index($1, ".") { print $1 }')
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
else
    # nothing to act on yet, as a release often reaches GitHub days after Maven Central. But a version with no GitHub
    # release, or one the almanac cannot read, comes in no other way: these notices are all that says so
    only_central=$(minus "$(minus "$served" "$have")" "$wanted" | sort -V)
    only_github=$(minus "$(minus "$wanted" "$have")" "$served" | sort -V)
    if [ -n "$only_central" ]; then
        how="a run from the Actions tab that names them adds them, 13 hours after the BOM if it lists what Central"
        how="$how never got; one that never gets a GitHub release goes into HistoryBuilder.WITHOUT_GITHUB_RELEASE first"
        echo "::notice::on Maven Central, no GitHub release yet: $(named "$only_central"); $how" >&2
    fi
    if [ -n "$only_github" ]; then
        how="they come in once it does, if the almanac can read their version"
        echo "::notice::GitHub releases Maven Central does not list for the almanac: $(named "$only_github"); $how" >&2
    fi
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
        how="api --versions <list>, dataset import --versions <list> --kinds api, then $ASSEMBLE, adds them"
        echo "::notice::releases of the dataset without API data ($api_count): $(named "$missing_api"); $how" >&2
    fi
else
    echo "::warning::the releases without API data could not be listed" >&2
fi

# the releases of the site without a dependency profile: the same, as nothing else remembers them
if missing_profiles=$(java -jar "$JAR" profile --missing | tr -d '\r'); then
    profile_count=$(grep -c . <<< "$missing_profiles" || true)
    if [ "$profile_count" != 0 ]; then
        what="releases of the site without a dependency profile ($profile_count)"
        echo "::notice::$what: $(named "$missing_profiles"); profile --versions <list>, then $ASSEMBLE, adds them" >&2
    fi
else
    echo "::warning::the releases without a dependency profile could not be listed" >&2
fi

# a deploy that failed or never started leaves the site behind main, and a run deploys only what it commits: the files
# of the site are compared with those of the last deploy that went well, or that is still running, over the paths that
# start pages.yml. Last by its start, as a re-run deploys its old commit again and keeps its place in the list, which
# comes newest first: reversed, so that the newer of two runs started in the same second counts. A commit that cannot
# be read counts as different. What gh prints stays out of standard output, which is the job's output
good='map(select(.conclusion == "success" or .status != "completed"))'
if deployed=$(gh run list --workflow pages.yml --branch main --limit 100 --json status,conclusion,headSha,startedAt \
    --jq "$good | reverse | sort_by(.startedAt) | last | .headSha // \"\""); then
    git fetch -q --depth=1 origin "$deployed" 2> /dev/null || true
    if ! git diff --quiet "$deployed" HEAD -- site .github/workflows/pages.yml 2> /dev/null; then
        echo "::warning::main's site is not that of the last deploy (${deployed:-none found}): starting its deploy" >&2
        gh workflow run pages.yml --ref main >&2 \
            || echo "::warning::the deploy could not be started; the next run tries again" >&2
    fi
else
    echo "::notice::the last deploy could not be read, so the site may be behind main" >&2
fi

echo "new releases: $count${new:+ ($(paste -sd' ' - <<< "$new"))}" >&2
echo "platform BOMs to add to releases of the dataset: $platform_count${platforms:+ ($(paste -sd' ' - <<< "$platforms"))}" >&2
echo "versions=$json"
echo "count=$count"
echo "platforms=$platform_count"
