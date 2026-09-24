#!/usr/bin/env bash
# Applies this directory's patches on top of the pinned firmware/ESP32RET
# submodule commit. Idempotent: safe to run again if already applied.
#
# Why a patch file and not a commit inside the submodule: a submodule
# commit has to exist on the submodule's own configured remote
# (upstream collin80/ESP32RET) for anyone else's `git submodule update`
# to fetch it. We don't have push access there, so a locally-made commit
# would be unreachable for anyone who isn't this exact checkout. A
# patch, tracked in the SavvyDroid repo itself, works for everyone who
# clones this repo instead.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
submodule_dir="$script_dir/../ESP32RET"

for patch in "$script_dir"/*.patch; do
    if git -C "$submodule_dir" apply --check --reverse "$patch" 2>/dev/null; then
        echo "already applied: $(basename "$patch")"
        continue
    fi
    echo "applying: $(basename "$patch")"
    git -C "$submodule_dir" apply "$patch"
done
