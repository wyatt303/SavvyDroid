# GVRET protocol — reverse-engineered spec for SavvyDroid

This is the exact wire format SavvyDroid's parser implements, extracted
from the two projects that define the protocol in practice (there is no
independent spec document — the code IS the spec):

- **Firmware (device/server) side**: `collin80/ESP32RET`
  - `src/commbuffer.cpp` — `CommBuffer::sendFrameToBuffer()`, the function
    that serializes an inbound CAN frame onto the wire. This is the
    authoritative source for what SavvyDroid receives.
  - `src/gvret_comm.cpp` / `.h` — `GVRET_Comm_Handler::processIncomingByte()`,
    the state machine that parses commands the client sends TO the device
    (used here to confirm command IDs and the keepalive reply).
  - `src/wifi_manager.cpp` — the UDP discovery beacon.
- **Client (reference implementation) side**: `collin80/SavvyCAN`
  - `connections/gvretserial.cpp` — `procRXChar()` (the receive-side state
    machine SavvyDroid's parser mirrors), `deviceConnected()` (handshake),
    `sendCommValidation()` (keepalive request), `piSendFrame()`.
  - `framefileio.cpp` — `saveNativeCSVFile()`, the on-disk CSV format.
- Cross-checked against `collin80/A0RET` (`gvret_comm.cpp`), a sibling
  firmware by the same author sharing near-identical serialization code —
  used to confirm the checksum byte is present on the wire but its value
  is currently **hardcoded to `0`** in shipped firmware (the XOR
  computation exists as `checksumCalc()` but the call site that would use
  it is commented out). SavvyDroid's parser therefore reads and discards
  this byte rather than validating it — matching SavvyCAN's own client,
  which does not validate it either.

All of the above was fetched and quoted from source on 2026-09-24; if the
upstream firmware/client change this behavior later, this doc will be
stale — re-verify before trusting it blindly for a new firmware version.

## Transport

- **TCP port 23** — the data connection. The client (SavvyDroid) connects
  out to the ESP's IP on this port. (Source: `gvretserial.cpp`
  `connectDevice()`: `tcpClient->connectToHost(getPort(), 23)`.)
- **UDP port 17222** — discovery only, one-way. The ESP32RET firmware
  broadcasts a 4-byte beacon `1C EF AC ED` to the local broadcast address
  on this port roughly once a second (`wifi_manager.cpp`, `lastBroadcast`
  timer). The firmware does **not** listen for a discovery request — it
  is unsolicited. SavvyDroid's discovery therefore just binds a UDP
  socket on `17222` and treats any received 4-byte datagram as a beacon,
  using the sender's source IP as the candidate device address. (No
  official reference client-side discovery listener was found in
  SavvyCAN's current source — this listening behavior is derived directly
  from the firmware's send side, which is the authoritative half of the
  contract. Document the risk: if some other broadcaster on the LAN sends
  a 4-byte UDP packet to port 17222, it would be mistaken for a beacon.
  Mitigate by also requiring a successful TCP connect + handshake on port
  23 to the same address before treating discovery as complete — never
  auto-connect on the UDP beacon alone.)

## Binary mode handshake

Serial/TCP link starts in ASCII/text mode. The client switches it to
binary mode by sending two bytes:

```
0xE7 0xE7
```

(Source: `gvretserial.cpp` `deviceConnected()`.) After that, SavvyCAN's
reference client additionally sends, in order, to prime its own UI state:

```
0xF1 0x0C   -- get number of buses
0xF1 0x06   -- get CAN bus parameters
0xF1 0x07   -- get device info
0xF1 0x01   -- time sync
0xF1 0x09   -- comm validation / keepalive
```

None of these five follow-ups are required to start receiving frames —
the firmware streams frames unconditionally once binary mode is on and a
bus is configured. SavvyDroid's MVP handshake is therefore: send
`E7 E7`, then send the bus setup command (below) to configure speed +
listen-only, then start parsing the stream. The four read-only queries
(`0C`/`06`/`07`/`01`) are not needed for MVP and are skipped; `09`
(keepalive) is sent periodically, see below.

## Command framing

Every command, in both directions, starts with a 1-byte prefix:

```
0xF1
```

followed by a 1-byte command ID. (Source: `gvretserial.cpp`
`piSendFrame()` for the client→device direction; `gvret_comm.cpp`'s
`IDLE` state — `if (in_byte == 0xF1) state = GET_COMMAND;` — for the
device-side parser, which is symmetric with what SavvyDroid must
implement to parse the device→client direction.)

Command IDs relevant to SavvyDroid's MVP:

| ID (dec) | Name | Direction | Purpose |
|---|---|---|---|
| 0 | `BUILD_CAN_FRAME` | device → client | a received CAN frame (below) |
| 5 | `SETUP_CANBUS` | client → device | configure bus speed / listen-only |
| 9 | `COMM_VALIDATION` | both | keepalive request / reply |

(IDs 1/2/3/6/7/12/13/20/22 exist in the protocol — time sync, digital/
analog inputs, get-params, get-device-info, get-bus-count,
get-ext-buses, CAN-FD frame, get-FD-settings — but are out of MVP scope
per the spec; SavvyDroid's parser resyncs past any command ID it doesn't
recognize rather than erroring, see "Resync" below.)

## Command 0 — received CAN frame (device → client)

This is the frame SavvyDroid's parser exists to decode. Total length on
the wire, **after** the `0xF1` prefix:

```
offset  size  field
0       1     command id (= 0x00)
1       4     timestamp, little-endian, microseconds, device-clock-relative
5       4     CAN ID, little-endian; bit 31 set => extended (29-bit) ID
9       1     (length_low_nibble | bus_number << 4)
10      N     data bytes, N = low nibble of byte 9, masked 0..15 (spec caps at 8)
10+N    1     checksum byte — present on the wire, value currently always
              0x00 in shipped firmware (XOR computation exists but is
              disabled at the call site); SavvyDroid reads and discards
              this byte, does not reject a frame based on its value
```

So a complete frame is `12 + N` bytes counting the leading `0xF1`
(`1 + 1 + 4 + 4 + 1 + N + 1`). (Source: `commbuffer.cpp`
`sendFrameToBuffer()`, cross-checked against the mirror-image receive
state machine in `gvretserial.cpp` `procRXChar()`'s `BUILD_CAN_FRAME`
state, which reads the identical layout back out: `buildTimestamp` from
4 LE bytes, `buildId` from 4 LE bytes, `buildData.resize(c & 0xF)` +
`bus = (c & 0xF0) >> 4` from the length|bus byte, then that many data
bytes. SavvyCAN's parser does not consume a trailing checksum byte in
the excerpt seen — but the firmware sender always appends one, so a
byte-accurate parser MUST consume it or every subsequent frame will be
misaligned by one byte. This is treated as the correct reading of the
wire format regardless: the firmware's send side is authoritative for
what appears on the wire.)

Extended-ID bit: `id_le_bytes` decode to a `uint32`, then
`isExtended = (raw_id and 0x8000_0000u) != 0u`, and the actual 29-bit or
11-bit ID is `raw_id and 0x1FFF_FFFFu` (masking off the flag bit; the
firmware sets it via `frame.id |= 1u shl 31` and standard 11-bit IDs
never set bits above bit 10, so the mask is safe either way).

## Command 5 — bus setup (client → device, no reply)

Sent once after the binary-mode handshake to configure the bus this MVP
cares about. (Source: `gvretserial.cpp` command ID table — ID 5 =
"CAN bus setup, no reply" — and `gvret_comm.cpp`'s `SETUP_CANBUS` state.)
Exact byte layout for the speed+listen-only fields was not indepedently
re-derived byte-by-byte from source for this MVP (the SETUP_CANBUS state
machine in `gvret_comm.cpp` was not fully quoted during research); rather
than guess bytes, **SavvyDroid's `GvretClient` isolates this command
behind a single `buildSetupCanBusCommand()` function** so the exact byte
layout can be corrected in one place once verified against real ESP32RET
hardware / a fresh read of `gvret_comm.cpp`'s `SETUP_CANBUS` case,
without touching the parser or anything else. Placeholder layout used
until then (best-effort, modeled on the SavvyCAN UI's known bus-speed
list and the single-bus MVP scope):

```
offset  size  field
0       1     command id (= 0x05)
1       4     bus 0 speed, little-endian, bits (top bit = bus enabled,
              next bit = listen-only, low 29ish bits = speed in bps)
5       4     bus 1 speed, little-endian (unused bus, sent as 0 = disabled)
```

**This layout is the one part of this doc explicitly flagged
NOT-YET-VERIFIED against source** — do not trust it the way the rest of
this document has been cross-checked. Verify against real hardware
before relying on bus configuration actually taking effect; the frame
receive/parse path (the part that matters for the MVP's core "capture
and save a file SavvyCAN can open" goal) does not depend on this command
succeeding, since ESP32RET streams whatever the bus is already
configured to (including any prior configuration set via its own serial
console) even if this command is silently ignored.

## Command 9 — comm validation / keepalive

- Client → device request: `0xF1 0x09` (2 bytes, no payload). SavvyCAN's
  reference client sends this every 250ms while connected
  (`sendCommValidation()`).
- Device → client reply: `0xF1 0x09 0xDE 0xAD` (4 bytes — the `DE AD`
  suffix is a fixed marker, not derived from anything). (Source: both
  `ESP32RET/src/gvret_comm.cpp`'s `PROTO_KEEPALIVE` case and the sibling
  `A0RET/gvret_comm.cpp`, byte-identical.)

SavvyDroid sends this every 3s (looser than SavvyCAN's 250ms — this is a
liveness check for reconnect logic, not a tight protocol requirement) and
treats two consecutive missed replies as connection-lost, triggering
reconnect-with-backoff.

## Resync

The stream is a byte pipe with no message-boundary framing beyond the
`0xF1` prefix + command ID + fixed-or-computed-length body described
above. TCP can fragment or coalesce packets arbitrarily, and a dropped/
corrupted byte can desync the parser. SavvyDroid's parser therefore:

- Buffers incoming bytes and only consumes a full frame's worth at a
  time — never assumes a single `read()` lines up with a frame boundary.
- On any unrecognized command ID (not 0, 5, or 9) or any byte where
  `0xF1` was expected but not found, discards one byte and re-scans for
  the next `0xF1`, rather than throwing/crashing. This mirrors
  `gvret_comm.cpp`'s own `IDLE` state, which only leaves `IDLE` on seeing
  `0xF1` and otherwise just drops the byte.
- Never blocks indefinitely waiting for a full frame that never
  completes (e.g. because the sender crashed mid-frame) — a partial frame
  sitting in the buffer with no forward progress across N further bytes
  received is discarded byte-by-byte the same way, not held forever.

## SavvyCAN "native" CSV file format (the save target)

SavvyDroid's recording output MUST be openable in SavvyCAN without
conversion — this is the format `framefileio.cpp`'s `saveNativeCSVFile()`
writes and (necessarily, since it's the same function used to load its
own saves) reads back:

```
Header (exact, first line):
Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8

Per-row, comma separated, exactly 14 fields:
  Time Stamp  decimal microseconds (device-clock-relative, same units as
              the protocol's timestamp field — NOT wall-clock epoch ms)
  ID          hex, UPPERCASE, zero-padded to 8 characters, no "0x" prefix
              e.g. 000005EB
  Extended    literal "true" or "false"
  Dir         "Rx" or "Tx" — always "Rx" for SavvyDroid (capture-only,
              MVP sends no frames)
  Bus         decimal bus number
  LEN         decimal data length, 0-8
  D1..D8      hex, UPPERCASE, zero-padded to 2 characters each, ALL 8
              columns always present even when LEN < 8 — unused trailing
              columns are "00", not blank
```

Example row: `39747828,000005EB,false,Rx,0,8,E8,45,85,4B,4A,28,36,69`

File extension: `.csv`.

## Marker encoding (SavvyDroid-specific, not part of upstream GVRET)

Per the MVP spec's requirement to pick one approach and document it:
**a marker is a synthetic row in the same CSV**, using a reserved CAN ID
that does not collide with real vehicle traffic, rather than a second
file. Reserved ID: `0x00000000` with `Extended=false`, `LEN=0`, all data
columns `00`, `Dir=Rx`. Rationale: keeps the whole capture in one
self-contained file (nothing to lose track of / forget to attach when
sharing), and SavvyCAN's grid view will show it inline at the correct
position in time, sortable/filterable by ID like any other row — a user
scrubbing the capture can find a marker exactly where the button press
happened without needing a second tool or file. Documented in the
top-level README as "ID 0x000 = SavvyDroid marker, not real bus
traffic."
