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
