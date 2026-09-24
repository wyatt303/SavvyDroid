# SavvyDroid

An Android app that connects over WiFi to a device speaking the GVRET
protocol (ESP32RET / ESP32S3RET firmware) and captures CAN traffic on a
motorcycle without a laptop. Logs are written in SavvyCAN's own native
CSV format, so they open in SavvyCAN with no conversion step. Analysis
happens later, on a PC, in SavvyCAN — this app only captures.

See `docs/PROTOCOL.md` for the exact wire format this implements, with
citations back to the firmware/client source it was reverse-engineered
from.

## Status: MVP

Implemented and unit-tested (see "Building & testing" below):

- GVRET binary protocol parser, robust against TCP fragmentation and
  stream corruption (`gvret/`).
- SavvyCAN-native CSV writer, including the marker-row convention.
- Connection layer: handshake, keepalive, reconnect-with-backoff,
  one-way UDP discovery of the ESP's beacon.
- Foreground recording service (buffered, flushed every ~1s, screen can
  be off).
- Basic UI: manual IP entry, discovery, live per-ID "overwrite" table
  with changed-byte highlighting, fps/dropped counters, record/stop
  (with share sheet), marker button.

**Not yet verified against real hardware or a real Android device/
emulator** — this host has neither. Everything here was verified by:
compiling (`assembleDebug`, both debug and release variants) and
running the `:gvret` module's 43 JVM unit tests (including a fake
in-process GVRET TCP server, so the connection layer's handshake/
keepalive/reconnect logic IS exercised for real, just not against an
actual ESP32). Treat the UI and foreground-service wiring as
compile-verified only until someone runs it on a phone against real
firmware.

One specific known gap, flagged in `docs/PROTOCOL.md`: the exact byte
layout of the `SETUP_CANBUS` command (bus speed + listen-only) was not
independently re-derived from firmware source for this MVP — it's
isolated behind `GvretCommands.setupCanBus()` so it can be corrected in
one place. Until verified, the app may fail to actually configure the
bus even though the frame *parsing* path (the part that matters for
"capture and save a file SavvyCAN can open") works regardless, since
ESP32RET streams whatever the bus is already configured to.

## Building & testing

No Android SDK/Gradle/JDK is required on your machine if you use the
same container-based flow this was developed and verified with:

```bash
# Pure-Kotlin protocol/parser/CSV/connection-layer tests (fast, no Android):
podman run --rm -v "$PWD:/workspace:Z" -v gradle-cache:/root/.gradle:Z \
  -w /workspace localhost/motocan-android:latest \
  ./gradlew :gvret:test --console=plain

# Full build, both modules, debug + release:
podman run --rm -v "$PWD:/workspace:Z" -v gradle-cache:/root/.gradle:Z \
  -w /workspace localhost/motocan-android:latest \
  ./gradlew test assembleDebug --console=plain
```

(If you don't have `localhost/motocan-android:latest` locally: any
image with JDK 17 + Android SDK platform 35 / build-tools 35.0.0 works —
`ANDROID_HOME` just needs to point at it. `./gradlew` downloads Gradle
8.7 itself on first run.)

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Flashing ESP32S3RET

1. Firmware source: <https://github.com/collin80/ESP32S3RET> (same
   protocol/config as ESP32RET, just targeting the S3 chip — see
   `docs/PROTOCOL.md` for which parts of this doc were verified against
   which repo).
2. Build/flash with PlatformIO (Arduino IDE also works per the
   firmware's own README, but PlatformIO avoids the manual "Minimal
   SPIFFS partition scheme" step PlatformIO's `platformio.ini` already
   encodes):
   ```bash
   pio run -t upload
   ```
   (Or, container-based, the same pattern used for this project's sibling
   MotoCAN firmware repo: build a PlatformIO image, mount the firmware
   checkout, `pio run -t upload` with the ESP32-S3 connected via USB.)
3. First boot creates its own WiFi access point — default SSID
   `ESP32RETSSID` (or `A0RETSSID` on some boards), default WPA2 password
   `aBigSecret`. This is enough to connect immediately without any
   configuration: join that AP from your phone, the device is reachable
   at `192.168.4.1` (ESP32 SoftAP default).

## WiFi configuration via the serial console

To use your own WiFi network / phone hotspot instead of the device's
own AP (recommended once you're set up — one less network to switch
your phone to on the bike), connect a USB-serial terminal at
**1,000,000 baud** and type:

```
SSID=YourNetworkName
WPA2KEY=YourNetworkPassword
WIFIMODE=1
```

(`WIFIMODE=1` = join that network as a client; `WIFIMODE=2` = go back to
creating its own AP; `WIFIMODE=0` = WiFi off.) The device reboots into
the new mode. There is no serial command to print the IP it was
assigned when joining your network as a client — either check your
router's/hotspot's connected-devices list, or just use SavvyDroid's
**discovery** (it listens for the device's own UDP broadcast beacon on
port 17222 and shows the sender's address — no typing an IP needed,
whichever network either device is on, as long as they're on the same
one).

## Connecting the app

1. Make sure your phone and the ESP32S3RET are on the same WiFi network
   (either your phone joined the ESP's own AP, or the ESP joined your
   phone's hotspot / home WiFi — either direction, see above).
2. Open SavvyDroid. If a beacon is heard, its address appears under
   "Discovered: ...". Otherwise, type the IP manually.
3. Tap **Connect**. Status goes Connecting → Connected. If the
   connection drops it reconnects automatically with backoff — no need
   to tap Connect again.
4. Tap **Start recording** once connected. Tap **MARKER** at any moment
   worth flagging (e.g. right when you press a handlebar button) — it's
   saved as a zero-length frame with ID `0x000` in the same file.
5. Tap **Stop recording** — the share sheet opens with the finished
   `.csv` file. Send it to yourself, save it, or share it however's
   convenient; it's also left on the phone under the app's own external
   files directory (`.../Android/data/pl.linuch.savvydroid/files/captures/`)
   if you didn't pick a share target.

The foreground notification keeps the connection and any in-progress
recording alive with the screen off.

## Opening the log in SavvyCAN

Nothing to convert — SavvyCAN's own "Load Trace File" (or drag-and-drop)
opens the `.csv` directly, since it's written in SavvyCAN's native
format (see `docs/PROTOCOL.md` "SavvyCAN 'native' CSV file format"). The
marker row(s) show up as ordinary rows with ID `000` — filter/search on
that ID in SavvyCAN's grid to jump straight to the marked moments.

## Out of scope for this MVP

Sending frames, DBC decoding, charts, BLE, USB — see the original spec.
