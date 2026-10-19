# SIM Relay protocol v1 (MIT)

Shared by the host (`sim-relay`, GPL-3.0) and the client (`sim-relay-client`, proprietary).
Schema: `simrelay.proto`. Lessons it encodes come from the M1 spike (`spikes/m1/RESULTS.md`).

## Transport
Host = BLE peripheral (advertises the service UUID), client = central.

| UUID | Role | Properties / security |
| --- | --- | --- |
| `0e9c0001-0971-48af-ba0b-2c5c254faec0` | primary service | advertised |
| `0e9c0002-0971-48af-ba0b-2c5c254faec0` | `CTRL_TX` host -> client | notify, encrypted (bonding required) |
| `0e9c0003-0971-48af-ba0b-2c5c254faec0` | `CTRL_RX` client -> host | write with response, encrypted |
| `0e9c0004-0971-48af-ba0b-2c5c254faec0` | `AUDIO_PSM` | read, encrypted: u16 LE L2CAP PSM (M5) |

## Framing
Each direction of the control channel is an ordered, reliable byte stream (ATT notifications /
write-with-response preserve order). Concatenate the payloads and split into frames:
`[u16 little-endian length][Envelope protobuf bytes]`, max frame 4096 bytes. A frame may span several
notifications or writes (MTU is typically 185-517 on iOS). Reset both stream buffers on reconnect.

## Session rules
- After subscribing to `CTRL_TX`, the client sends `Hello`; the host answers `Hello` + `HostStatus`,
  then replays undelivered `SmsReceived` and the state of any ongoing call (`CallEvent`).
- Host sends `KeepAlive` every 60 s while idle; the client answers with `KeepAlive` (same `n`).
- `SmsReceived` is store-and-forward: the host keeps it until `SmsAck`, resending on each reconnect.
- The host only serves the bonded client whose `install_id` it pinned at pairing; anything else gets
  `Error{NOT_PAIRED}` and is disconnected.
- Host must request a short connection interval while a call is ringing/active (idle-relaxed links
  made a ring take 1.9 s in M1).
- Numbers: the host sends `number_raw` and, when possible, `number_e164` (normalised with the SIM's
  country) so the client can match the iPhone Contacts list.

## Remote mode (internet)

Used when Bluetooth is out of range and the user switched Remote mode on. Bluetooth always wins when both are up.

**Key.** The client makes 32 random bytes `K` and sends `RemoteSetup{enabled, key}` **over Bluetooth only** (the host
ignores it on the internet path). Derived keys, all HKDF-SHA256 with salt `"tosbisim-remote-v1"`, 32 bytes:

| info | use |
| --- | --- |
| `room-token` | `room_id = hex(sha256(room_token))`; the server checks this and nothing else |
| `control` | control messages |
| `audio` | call audio |

**Control.** Both phones join the Reverb (Pusher protocol 7) presence channel `presence-room.<room_id>`
(`GET /api/v1/remote` gives the WebSocket URL; `POST /api/v1/remote/auth {socket_id, channel_name, room_token
(hex), role: host|ios}` signs the subscription; member ids are `host` / `ios`). An envelope travels as client event
`client-m` with data `{"b": base64(nonce12 || ChaCha20-Poly1305(control key, nonce, plaintext))}`, plaintext =
`[u64 BE unix ms][Envelope bytes]`, AAD = `"h2c"` (host to client) or `"c2h"`. Receivers drop messages older than
120 s or whose nonce they have already seen. The session rules are the same as over Bluetooth (Hello first; the
host serves only the pinned install_id).

**Audio.** Each side takes a TURN allocation on the TosbiSim relay (`POST /api/v1/turn {install_id}` gives short-lived
REST credentials), the client offers its relay address in `RemoteAudio`, the host answers with its own, and each side
ChannelBinds (channel 0x4000) the other's relay address. One UDP datagram carries one audio packet:
`nonce12 || ChaCha20-Poly1305(audio key, nonce, [u16 seq LE][u8 flags][opus])`, AAD `"h2c"` / `"c2h"`, the same
inner packet as the L2CAP channel (10 ms frames; the receiver's jitter buffer reorders). Allocations are refreshed and
channels re-bound every 4 minutes while a call lasts; `RemoteAudio{close}` or the call ending releases them.

## Updates

`GET /api/v1/releases/host?since=<versionCode>` lists the host releases newer than `since` (newest first: version,
code, notes `{en, tr}`, apk `{url, sha256, size}`); `/releases/ios` the same for the iPhone app (no file; `store_url`
once it is in the App Store). The host checks daily and on `UpdateAction{CHECK}`, downloads the newest APK, checks its
sha256 and that it is signed with the installed app's key, and reports `HostStatus.update`. It installs on
`INSTALL_NOW`, or between 02:00 and 05:00 host time once a client has been shown the update and no call is up, unless
`LATER` set `deferred_until_ms` (one day). Every release carries notes in English and Turkish.
