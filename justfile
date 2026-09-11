# Velocity fork, compiled through buck2. Run inside the devshell (`direnv exec . just ...`).

root := justfile_directory()

# The buck2 to drive the build with. The devshell's by default; export BUCK2 (e.g. in
# .envrc.local) to run every build through a different buck2 binary instead.
buck2 := env("BUCK2", "buck2")

_default:
    @just --list --unsorted

# the shipped proxy jar (which builds every module), and mccrypt
build:
    @{{buck2}} build //proxy:all '//mccrypt:mccrypt[cdylib]' //mccrypt:bench --show-simple-output

# every test in the repo, or under a pattern
test pattern="//...":
    @{{buck2}} test {{pattern}}

# Any buck2 command with the remote workers as execution platforms, e.g.
# `just re build //proxy:all`. Needs RE configured. Actions run on the Linux workers except the
# Mach-O link of the macOS cipher slot, which is exec-constrained to macOS and stays here; the
# tools the workers use come out of nix/flake.nix, built on the worker the first time.
re *args:
    @{{buck2}} {{args}} -c re.enabled=true

# The proxy jar with the revision stamped into Implementation-Version, the way upstream's
# gradle build stamps snapshots: "<version> (git-<rev>)", plus the jj bookmark when there is
# one. The revision reaches the build as config (-c), so only the final assembly step re-runs.
release:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root}}"
    version="$(sed -n 's/^version=//p' gradle.properties)"
    if jj --ignore-working-copy root >/dev/null 2>&1; then
        rev="$(jj --ignore-working-copy log -r @- --no-graph -T 'commit_id.short(8)')"
        bookmark="$(jj --ignore-working-copy log -r @- --no-graph -T 'bookmarks.map(|b| b.name()).join(",")')"
    else
        rev="$(git rev-parse --short=8 HEAD)"
        bookmark="$(git symbolic-ref --short -q HEAD || true)"
    fi
    stamp="$version (git-$rev${bookmark:+, $bookmark})"
    mkdir -p dist
    ${BUCK2:-buck2} build //proxy:all -c "velocity.implementation_version=$stamp" --out dist/velocity-proxy-all.jar
    echo "dist/velocity-proxy-all.jar: $(unzip -p dist/velocity-proxy-all.jar META-INF/MANIFEST.MF | grep Implementation-Version)"

# mccrypt/Cargo.lock -> third-party/rust/BUCK (see reindeer.toml). Re-run after changing
# mccrypt's dependencies; the diff of the generated file is the review.
lock-rust:
    @reindeer buckify

# Ask gradle -- the resolver upstream actually uses -- for the resolved graph of every module,
# then regenerate third-party/jvm/{BUCK,classpaths.bzl} from it. Re-run after an upstream merge
# touches gradle/libs.versions.toml or a build.gradle.kts; the diff of the generated files is
# the review.
lock:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root}}"
    ./gradlew -q -I tools/dumpdeps.init.gradle.kts \
        :velocity-api:dumpDeps :velocity-native:dumpDeps :velocity-proxy:dumpDeps :deprecated-configurate3:dumpDeps
    python3 tools/gen-third-party.py build/deps third-party/jvm
