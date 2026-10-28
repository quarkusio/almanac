#!/usr/bin/env bash
# Adds the releases the new-releases workflow extracted to the dataset, and the platform BOMs the dataset lacks (of
# those releases and of older ones whose platform BOM came out later), rebuilds the site data from it, and commits both
# to main. Expects the jar and, under work/, each release's snapshots (work/snapshots/<version>/, with the platform BOM
# when it was out) and BOM pom (work/cache/io/quarkus/quarkus-bom/<version>/, whose Last-Modified dates the release).
# Only releases with all four snapshots (BOM, config, extensions, classes) are added; EXPECTED, the number of releases
# the workflow extracted, makes a run where none of them is complete fail. It starts from origin/main and, when main
# moves before the push, starts over from it; when main's code is no longer the code that built the jar (BUILT, a
# commit), it adds nothing, since the next run extracts the releases again with main's code.
# For a dry run: RESET=no (start from the checkout as it is), COMMIT=no (stage the changes only) or PUSH=no (commit
# without pushing), DEPLOY=no.
set -euo pipefail

JAR=${JAR:-target/quarkus-almanac.jar}
PAUSE=${PAUSE:-0.001}
RESET=${RESET:-yes}
COMMIT=${COMMIT:-yes}
PUSH=${PUSH:-yes}
DEPLOY=${DEPLOY:-yes}
BUILT=${BUILT:-}
EXPECTED=${EXPECTED:-0}

ready=()
for dir in work/snapshots/*/; do
    [ -d "$dir" ] || continue
    v=$(basename "$dir")
    if [ -f "$dir/bom.json" ] && [ -f "$dir/config.json" ] && [ -f "$dir/extensions.json" ] \
        && [ -f "$dir/classes.json" ]; then
        ready+=("$v")
    else
        echo "skipped $v: its snapshots are incomplete" >&2
    fi
done
if [ ${#ready[@]} -eq 0 ] && [ "$EXPECTED" != 0 ]; then
    echo "no release was extracted completely; nothing to add" >&2
    exit 1
fi
versions=$(printf '%s\n' "${ready[@]}" | sort -V | paste -sd, -)
echo "adding ${versions:-no new release}" >&2

changed=no
platforms=
add() {
    if [ -n "$versions" ]; then
        java -jar "$JAR" dataset import --versions "$versions" --kinds bom,config,extensions,classes --pause "$PAUSE"
    fi
    # the platform BOMs the dataset lacks, of the new releases too now that their BOMs are in: those the extraction
    # wrote are reused, the others downloaded. A platform BOM that fails is left to a later run.
    if ! platforms=$(java -jar "$JAR" platform --missing --versions all --pre-releases all --pause "$PAUSE" \
        | tr -d '\r' | paste -sd, -); then
        echo "::warning::the platform BOMs to add could not be listed; a later run adds them" >&2
        platforms=
    fi
    if [ -n "$platforms" ]; then
        if ! java -jar "$JAR" platform --versions "$platforms" --pause "$PAUSE"; then
            echo "::warning::not every platform BOM of $platforms could be read; a later run adds the rest" >&2
        fi
        added=$(for v in ${platforms//,/ }; do
            if [ -f "work/snapshots/$v/platform-bom.json" ]; then echo "$v"; fi
        done | paste -sd, -)
        platforms=$added
        if [ -n "$platforms" ]; then
            java -jar "$JAR" dataset import --versions "$platforms" --kinds platform --pause "$PAUSE"
        fi
    fi
    # neither a release nor a platform BOM to add: a change of the release list alone waits for the next commit
    if [ -z "$versions$platforms" ]; then
        changed=no
        return 0
    fi
    java -jar "$JAR" dataset refresh-releases --pause "$PAUSE"
    java -jar "$JAR" dataset verify --offline --compare-work
    java -jar "$JAR" assemble --offline --pre-releases all
    node --test site/tests/
    git add dataset site/data
    # assemble always renews the date in site/data/versions.json: only a change of the dataset is worth a commit
    if git diff --cached --quiet -- dataset; then
        changed=no
        return 0
    fi
    changed=yes
    if [ "$COMMIT" = yes ]; then
        message="Dataset: ${versions:+Quarkus ${versions//,/, }}${versions:+${platforms:+; }}${platforms:+platform BOMs of ${platforms//,/, }}"
        git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
            commit -q -m "$message" -- dataset site/data
    fi
}

for attempt in 1 2 3; do
    if [ "$RESET" = yes ]; then
        git fetch -q origin main
        git reset -q --hard origin/main
        # BUILT may be older than the shallow checkout: fetch it, and treat it as changed code when it cannot be read
        if [ -n "$BUILT" ]; then
            git fetch -q origin "$BUILT" 2> /dev/null || true
        fi
        if [ -n "$BUILT" ] && ! git diff --quiet "$BUILT" HEAD -- pom.xml src .github/scripts 2> /dev/null; then
            echo "::warning::main's code changed since $BUILT built the jar${versions:+ that extracted $versions}; adding nothing, the next run starts again with main's code" >&2
            exit 0
        fi
    fi
    add
    if [ "$changed" = no ]; then
        echo "nothing new for the dataset${versions:+ (it has $versions already)}; nothing to commit" >&2
        exit 0
    fi
    if [ "$COMMIT" != yes ]; then
        echo "staged, not committed:" >&2
        git diff --cached --stat >&2
        exit 0
    fi
    if [ "$PUSH" != yes ]; then
        echo "committed $(git rev-parse --short HEAD) without pushing" >&2
        exit 0
    fi
    if git push -q origin HEAD:main; then
        echo "pushed $(git rev-parse --short HEAD): $(git log -1 --format=%s)" >&2
        # a push with the workflow's token starts no workflow, so the Pages deploy is started by hand
        if [ "$DEPLOY" = yes ]; then
            for try in 1 2 3; do
                if gh workflow run pages.yml --ref main; then
                    exit 0
                fi
                sleep 10
            done
            echo "pushed, but the Pages deploy could not be started; start it from the Actions tab" >&2
            exit 1
        fi
        exit 0
    fi
    if [ "$RESET" != yes ]; then
        break
    fi
    echo "main moved during attempt $attempt; starting over from it" >&2
done
echo "could not push" >&2
exit 1
