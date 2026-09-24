# firmware/

This directory vendors the ESP32 firmware SavvyDroid talks to, as git
submodules pointed at their real upstream repos (not copies) — so a
`git clone --recurse-submodules` of this repo gets you a build of both
sides, the phone app and the device firmware, self-contained.

| Path | Upstream | Why it's here |
|---|---|---|
| `ESP32RET/` | [collin80/ESP32RET](https://github.com/collin80/ESP32RET) (MIT) | The firmware itself. `docs/PROTOCOL.md` was reverse-engineered directly from this source. |
| `Arduino/libraries/esp32_can/` | [collin80/esp32_can](https://github.com/collin80/esp32_can) (MIT) | Build dependency — the CAN driver library. |
| `Arduino/libraries/esp32_mcp2517fd/` | [collin80/esp32_mcp2517fd](https://github.com/collin80/esp32_mcp2517fd) (MIT) | Build dependency — MCP2517FD CAN controller support. |
| `Arduino/libraries/can_common/` | [collin80/can_common](https://github.com/collin80/can_common) (MIT) | Build dependency — shared CAN structures. |
| `Arduino/libraries/FastLED/` | [FastLED/FastLED](https://github.com/FastLED/FastLED) (MIT), **pinned to tag `3.9.20`** | Build dependency — status LED. **Not master**: see "Why FastLED is pinned" below. |

`ESP32RET/platformio.ini` expects the four `Arduino/libraries/*`
dependencies in `~/Arduino/libraries` (that's upstream's own documented
convention, not a PlatformIO `lib_deps` entry) — which is exactly why
they're laid out under `firmware/Arduino/libraries/` here: point
`$HOME` at `firmware/` when building (see below) and PlatformIO finds
them without anyone needing to hand-install anything.

## Why FastLED is pinned (not tracking master)

`ESP32RET/src/config.h` does `#define BRIGHTNESS 190` — a bare,
unnamespaced macro. FastLED's current master (since roughly 3.10.4)
added `enum class ClearFlags { ..., BRIGHTNESS, ... }`, and the C
preprocessor doesn't know the difference between a macro and an enum
member: `BRIGHTNESS` inside that enum gets textually replaced with
`190`, which fails to compile
(`error: expected identifier before numeric constant`). This is
confirmed, not guessed — reproduced the exact failure against FastLED
master, then bisected to `3.9.20` (last release before the enum was
introduced) and confirmed it builds clean there. Pin stays until either
upstream ESP32RET renames its macro or FastLED renames that enum
member — whichever happens first.

## Board-specific patches (`patches/`)

`ESP32RET/` stays pinned exactly to upstream `collin80/ESP32RET` — any
board-specific customization we need lives as a **patch file** in
`firmware/patches/`, applied on top, rather than as a commit inside the
submodule. That's deliberate, not incidental: a commit made inside the
submodule would only exist in whichever local checkout made it — it has
to exist on the submodule's own configured remote (upstream
`collin80/ESP32RET`, which we don't have push access to) for anyone
else's `git submodule update` to fetch it. A patch tracked in the
SavvyDroid repo itself works for everyone who clones this repo.

Apply before building:
```bash
firmware/patches/apply.sh
```
Idempotent — safe to run again if already applied. Reversible with
`git -C firmware/ESP32RET checkout -- .` (discards the patch, back to
the pristine pinned commit).

**`0001-seeed-xiao-esp32s3-dummy-tx-pin.patch`**: adds a
`[env:seeed_xiao_esp32s3]` PlatformIO environment and a new
`systemType == 4` in `ESP32RET.cpp` (deliberately a new number, not a
change to the existing `systemType == 3` "EVTV ESP32-S3 Board" path,
which stays untouched). Wires CAN the same way this project's sibling
repo `DucatiMonster937CanBus` does on the same board (see its
`firmware/include/config.h`): D1/GPIO2 is the real transceiver RXD, but
D0/GPIO1 — physically wired to the transceiver's TXD — is held
statically HIGH as a plain GPIO, never connected to the TWAI
peripheral at all; TWAI's own TX is instead routed to D3/GPIO4, which
is physically unconnected on this board. This is a second, physical
safety layer independent of whatever `TWAI_MODE_LISTEN_ONLY` (a real
hardware guarantee per Espressif's own driver header: "will not
influence the bus — no transmissions or acknowledgments") the
`SETUP_CANBUS` command configures at runtime — worth having since that
command's exact byte layout is the one part of the GVRET protocol
`docs/PROTOCOL.md` flags as not yet independently verified.

Verified: builds clean with the patch applied (`pio run -e
seeed_xiao_esp32s3`), and `stable`/`stable-s3` still build clean
afterward too (this patch only adds new code paths gated behind
`-D SAVVYDROID_XIAO_ESP32S3`, doesn't touch existing ones). **Not
verified**: actually flashed and run on real XIAO ESP32-S3 hardware —
compiling clean confirms the source is correct, not that the wiring is
right on a real board. Confirm your own board's D0/D1 label-to-GPIO
mapping matches before trusting this blindly, especially if using a
different XIAO variant or a different transceiver board than
DucatiMonster937CanBus's.

## Building

Self-contained — the `Containerfile` here needs nothing from this
project's own Android build image, just git + PlatformIO:

```bash
podman build -f firmware/Containerfile -t savvydroid-firmware:latest firmware/

# Plain ESP32 (EVTV ESP32Due, Macchina A0, ...):
podman run --rm --entrypoint pio -e HOME=/workspace/firmware \
  -v "$PWD:/workspace:Z" -v savvydroid-firmware-data:/root/.platformio:Z \
  -w /workspace/firmware/ESP32RET savvydroid-firmware:latest run -e stable

# ESP32-S3 (native support in this firmware's platformio.ini, no
# separate fork needed -- see docs/PROTOCOL.md's firmware note):
podman run --rm --entrypoint pio -e HOME=/workspace/firmware \
  -v "$PWD:/workspace:Z" -v savvydroid-firmware-data:/root/.platformio:Z \
  -w /workspace/firmware/ESP32RET savvydroid-firmware:latest run -e stable-s3

# Seeed XIAO ESP32-S3 with DucatiMonster937CanBus-style wiring -- apply
# the patch first (see "Board-specific patches" above):
firmware/patches/apply.sh
podman run --rm --entrypoint pio -e HOME=/workspace/firmware \
  -v "$PWD:/workspace:Z" -v savvydroid-firmware-data:/root/.platformio:Z \
  -w /workspace/firmware/ESP32RET savvydroid-firmware:latest run -e seeed_xiao_esp32s3
```

The `-e HOME=/workspace/firmware` is what makes `~/Arduino/libraries`
in `platformio.ini` resolve to the vendored libraries above — without
it, PlatformIO looks for them in the container's real home directory
and fails with `fatal error: esp32_can.h: No such file or directory`.

**Build `stable` and `stable-s3` as two separate `pio run` invocations,
not `pio run -e stable -e stable-s3` in one command** — confirmed this
matters, not just stylistic preference. `stable` and `stable-s3` pull
different major versions of the `pioarduino/platform-espressif32`
toolchain (v51 vs v55); running both envs in a single `pio run` call
left the second env's Python/venv resolution broken
(`Error: Python executable not found` at the `checkprogsize` step,
`stable-s3` failing while `stable` in the same invocation succeeded).
Re-running `stable-s3` alone, same image and volume, succeeded clean —
so this is a real PlatformIO multi-env interaction quirk with mixed
toolchain versions, not a fluke or a problem with the vendored source.

Verified for real in this session (both environments, from a freshly
built image, no pre-warmed cache, each as its own `pio run` invocation
per the note above):

```
stable               SUCCESS  Flash: 96.4% (1894437 / 1966080 bytes), RAM: 22.5%
stable-s3            SUCCESS  Flash: 42.8% (1347343 / 3145728 bytes), RAM: 19.3%
seeed_xiao_esp32s3   SUCCESS  Flash: 42.6% (1341135 / 3145728 bytes), RAM: 19.5%  (with patch applied)
```

Output binaries land in `ESP32RET/.pio/build/<env>/firmware.bin`
(+ `bootloader.bin`, `partitions.bin`).

**`stable`'s flash usage is worth watching** — 96.3% full on a plain
ESP32's default 2MB app partition (`minspiff.csv`). Not a problem today,
but there's very little headroom left in that image for upstream to add
anything before it stops fitting.

**Not yet done**: flashing this onto real hardware and confirming
`GET /rides`-style end-to-end capture against SavvyDroid — no ESP32
hardware exists on this host. Compiling clean is real, verified
evidence the source is buildable and correct for the target chip; it is
not evidence the firmware behaves correctly on a live CAN bus. Say so
plainly rather than implying otherwise.

## Updating the pinned submodules

```bash
cd firmware/ESP32RET && git fetch && git checkout <new-commit-or-tag> && cd -
git add firmware/ESP32RET
git commit -m "firmware: bump ESP32RET to <ref>"
```

Same pattern for the `Arduino/libraries/*` ones. For FastLED
specifically, re-check the `BRIGHTNESS` collision (see above) before
bumping past `3.9.20` — don't just bump to `latest` and assume it still
builds.
