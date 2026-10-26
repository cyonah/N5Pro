# Device support request: Infinix XWatch N5 Pro (XW5E)

Protocol notes derived from a Bluetooth HCI capture and from analysis of the vendor app's decompiled code, for interoperability. No decompiled source is reproduced here; only message names, field numbers and observed behaviour. The raw capture can be shared on request after redacting contacts, the bind identity and the serial number.

## Device

| Item | Value |
|---|---|
| Watch | Infinix XWatch N5 Pro, model `XW5E` |
| Firmware (from version reply) | 1.0.80 |
| Companion app | MyHealth (package `com.transsion.healthlife`), version 5.8.1.13 |
| Chipset hint | Advertises Jieli-style classic services (`JL_A2DP`, `JL_HFP`, `JL_SPP`) |
| Phone used for capture | Android (Infinix) |

The watch uses two Bluetooth links: Classic (calls, audio, serial) and BLE (health data, settings, notifications). This document only covers BLE.

## Status of this document

- Verified against the capture: framing, the GATT layout, and the step and sleep messages (decoded to plausible values: 24 hourly step buckets per day, one 37-minute nap).
- Verified live with a generic GATT browser on a watch previously paired with the vendor app: after enabling notifications on `0x9231`, writing `EE 00 32 00 00` (version request, Write Without Response) to `0x9231` returned `EE 0A 32 00 00 12 06 31 2E 30 2E 38 30 22 00` on `0x9231` immediately (firmware "1.0.80"). The same write to `0x9232` produced no reply. Notifications enabled on `0xAE02`, the Nordic-UART TX characteristic, `0x2A37` and `0xCC01` showed no traffic during the test.
- Verified in structure only: heart rate. The 288-slot, 5-minute layout decodes cleanly, but the only day in the capture is the current day just after midnight, so every reading is 0. Real non-zero values, and the meaning of any sentinel such as 255, are still unconfirmed.
- Inferred: command names for cmds 0x30–0x3D and the contact commands, taken from the older "v1" raw-byte protocol in the same app. Command numbers match, but payloads are protobuf on this watch.
- Unknown: how the bind identity is created, and the meaning of several cmds (listed at the end).

## GATT

| Item | Value |
|---|---|
| Primary service | `0xFD35` (16-bit UUID) |
| `0x9231` (ATT handle 210 in the capture) | Main channel: phone writes commands (Write Command); watch notifies; also readable per a GATT browser |
| `0x9232` (handle 213) | Write Without Response + Notify; carried the bind reply (cmd 0x91) in the capture |
| `0x9233` (handle 216) | Read-only; the vendor app reads it once at connect. Value (identical in the capture and in a GATT browser): `08 04 10 80 20 18 6E`, which parses as protobuf `{1: 4, 2: 4096, 3: 110}`. Meaning unconfirmed (possibly protocol version, buffer size and a limit). |

Properties confirmed by a GATT browser's service dump: `0x9231` = Read, Write Without Response, Notify (a read returns an empty value); `0x9232` = Write Without Response, Notify; `0x9233` = Read. All notifying characteristics have a standard CCCD (`0x2902`), initially `0000`.
| Notify enable | Write `0x0001` to each notifying characteristic's CCCD (handles 211 and 214 in the capture, Write Request) |

An earlier draft of this document mentioned a custom descriptor `0x3166`; that is not supported by the capture or by a GATT browser listing and has been removed.

### Other services exposed by the watch (from a GATT browser)

| Service | Characteristics | Notes |
|---|---|---|
| `0x1800` Generic Access | `0x2A00` Device Name (readable, writable) | standard |
| `0x180D` Heart Rate | `0x2A37` Heart Rate Measurement (notify) | standard profile, usable for live heart rate without the vendor protocol |
| `0xAE00` | `0xAE01` (write), `0xAE02` (notify) | pattern commonly used by Jieli chips |
| `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | `…0002` (write), `…0003` (notify) | Nordic UART Service layout |
| `0xCC00` | `0xCC01` (notify) | purpose unknown |
| `0xFF00` | `0xFF01` (write), `0xFF02` (notify) | purpose unknown |
| ATT MTU | 517 negotiated; 244 bytes of data per notification observed |

The watch also probes the phone's GATT server for the Apple Notification Center Service (`7905F431-B5CE-4E99-A40F-4B1E122D00D0`) and the phone answers "attribute not found". This does not appear to be needed.

## Frame format

Every ATT write or notification value is one or more frames (a frame may be split across notifications; reassemble by length):

```
0xEE | len_lo | cmd | flags | idx | payload[len]
```

- `len` = `len_lo` | (flags bit 4) << 8 | (flags bit 5) << 9, so the maximum payload is 1023 bytes.
- `cmd`: one byte.
- `flags`: bit 0 = response frame, bit 1 = continuation (more packets follow for this cmd), bit 2 = sender requests an acknowledgement, bits 4–5 = high bits of the length.
- `idx`: packet index within a multi-packet message (0 for single-packet).
- `payload`: a protobuf message (below).

All 126 phone-to-watch frames in the capture split exactly with this layout. Large watch-to-phone frames (length ≥ 256) leave a few stray bytes in my reassembler that I have not explained; decoding was unaffected.

## Protobuf messages (verified)

All integers are varints. Dates are Unix seconds at local midnight for the user's timezone.

### Time sync, cmd 0x60 (phone → watch): `TransSyncTime`

| # | Field | Type | Note |
|---|---|---|---|
| 1 | phoneTime | int | Unix seconds |
| 2 | timeZone | int | 300 observed in a UTC+3 timezone; unit unidentified |

### Steps, cmd 0x34: request `TransStepRequest`, reply `TransStepsInfoList { repeated TransStepInfo = 1 }`

| Message | # | Field | Type | Note |
|---|---|---|---|---|
| TransStepRequest | 1 | queryTime | enum | 0 = today, 1 = history |
| TransStepRequest | 2 | index | int | |
| TransStepInfo | 1 | date | int | day start, Unix seconds |
| TransStepInfo | 2 | stepsData | repeated int (packed) | 24 values per day in the capture (hourly buckets) |
| TransStepInfo | 3 | intervalTime | int | omitted when default |

### Heart rate, cmd 0x36: request `TransHeartRateRequest`, reply `TransMultiDayHeartRate { repeated TransDailyHeartRate = 1 }`

| Message | # | Field | Type | Note |
|---|---|---|---|---|
| TransHeartRateRequest | 1 | queryTime | enum | as above |
| TransHeartRateRequest | 2 | index | int | |
| TransDailyHeartRate | 1 | date | int | |
| TransDailyHeartRate | 2 | timeInterval | int | 5 (minutes) |
| TransDailyHeartRate | 3 | heartRateList | bytes | one byte per interval, 288 per day; 0 = no reading |

### Sleep, cmd 0x38: request `TransSleepDataRequest`, reply `TransDailySleepDataList { repeated TransDailySleepData = 1 }`

| Message | # | Field | Type | Note |
|---|---|---|---|---|
| TransSleepDataRequest | 1, 2 | queryTime, index | enum, int | as above |
| TransDailySleepData | 1 | date | int | |
| TransDailySleepData | 2 | sleepData | repeated TransSleepData | |
| TransSleepData | 1 | startTime | int | Unix seconds |
| TransSleepData | 2 | endTime | int | Unix seconds |
| TransSleepData | 3 | totalTime | int | minutes (37 observed for a 37-minute nap) |
| TransSleepData | 4 | state | int | 255 observed for the nap; semantics unknown |
| TransSleepData | 5 | category | int | 2 observed for the nap; semantics unknown |

### Bind, cmds 0x6e and 0x91

| Message | # | Field | Type | Note |
|---|---|---|---|---|
| TransSyncUserIdentity (0x6e, phone → watch) | 1 | identity | string | 32 hex characters |
| TransBindState (0x91, watch → phone) | 1 | userIdentity | message | echoes the identity |
| TransBindState | 2 | state | enum | 0 = timeout, 1 = agree, 2 = refuse |

The reply in the capture is wrapped: an outer varint (2029) followed by the TransBindState message. The watch answered with state = 1.

### Generic response, cmd 0x92: `TransCommonResponse`

| # | Field | Type |
|---|---|---|
| 1 | eventId | int (the cmd being answered) |
| 2 | responseCode | int (0 = success) |
| 3 | extraData | bytes |

## Unsolicited events, cmd 0x91 (watch → phone, on characteristic `0x9232`)

Payload: field 1 = event id (varint), field 2 = an optional inner message. The watch sends these on its own, for example while the user runs a measurement on the watch.

| Event id | Inner message | Meaning | Status |
|---|---|---|---|
| 2002 | empty → `{1: 1, 2: bpm}` | Heart-rate test: empty message when it starts, then the result. Field 1 = 1 (probably success), field 2 = beats per minute | Confirmed: the watch screen showed 67 and the event carried `{1: 1, 2: 67}` |
| 2021 | empty → `{1: 1, 2: level}` | Stress test: same pattern; field 2 = stress value | Confirmed: the watch screen showed 30 and the event carried `{1: 1, 2: 30}` |
| 2029 | `{1: identity, 2: state}` | Bind state (see above) | Seen in the first capture |
| 2001 | `{1: percent}` | Battery level, sent about every 20 s | Confirmed: matched the battery reply (57) and then counted down 57, 56, 55, 54, 53 over about 20 minutes |
| 2003 | start: `{2: {1: time}}`; result: `{1: 1, 2: {1: time, 2: percent}}`; end marker: `{1: 1, 2: {1: time}}` | Blood-oxygen test; `time` is Unix seconds | Confirmed: the watch showed 97 % and the event carried 0x61 = 97 |
| 2006 | `{1: steps}` | Steps so far today (5831 seen shortly after a history sync showing 5815) | Probable |
| 2008, 2009, 2010 | `{1: 1..3}` | Unknown; values 1, 2 and 3 seen | Unknown |

Each result event is usually sent twice a few seconds apart; deduplicate on (event id, value, time).

## File transfer (partial, from a truncated capture)

A capture taken while the vendor app sent a file to the watch (probably a watch face) was in Android's shortened "btsnooz" form, which keeps only the first 3 bytes of every ATT value. So frame headers are visible but contents and the upper length bits are not. What it shows:

| Item | Observation |
|---|---|
| Start | cmd `0x70` from the phone with a 47-byte payload (probably file name, size and checksum; contents not captured), answered by cmd `0x92` |
| Data | cmd `0x71` frames written to `0x9231` (Write Without Response): 8 frames of 248 bytes plus one of 64 bytes, i.e. 2048 bytes per block |
| Flow control | after every block the watch sends cmd `0x92` (2-byte payload); the phone waits for it before sending the next block |
| End | the last block is shorter (a final frame of 80 bytes in one transfer, 200 bytes in another); a final `0x92` follows about 0.4 s later |
| Status | cmd `0xA7` with an empty payload; the watch answers with 6 bytes; the phone polls it before and after the transfer |
| Speed | about 2 KiB per 0.33 s (roughly 6 KB/s); a transfer of about 55 KB took about 10 s |

The file format and the 0x70 header fields are unknown. A full capture is needed (see the capture notes for the vendor app); the watch-face file itself would also help.

## Request framing and sync flow (observed)

- Request payloads on 0x34 / 0x36 / 0x38 / 0x4d / 0x7c in the capture are `12 02 08 00` (today) and `12 02 08 01` (history), i.e. a field 2 containing the query-time varint. This differs slightly from the field layout above; I am reporting the observed bytes.
- Sync sequence per data type: phone requests history; the watch replies with a per-day index; the phone writes back a checkpoint message (fields: date, 0, 0, sampleCount, sampleCount) listing what it already has; the watch then sends the full day records.
- Per-day sample counts seen in the checkpoint: steps 24, heart rate 288, sleep 1, cmd 0x7c 48.

## Command table

Names from the app's older raw-byte protocol. Payload encoding on this watch is protobuf.

| Cmd | Name | Seen |
|---|---|---|
| 0x30 | Feature support | |
| 0x31 | Model info | request in capture |
| 0x32 | Version info | reply contains "1.0.80" |
| 0x33 | Battery | reply field 1 = 64 |
| 0x34 | Step history | yes |
| 0x35 | Sport info | no |
| 0x36 | Heart-rate history | yes |
| 0x37 | Settings data | |
| 0x38 | Sleep history | yes |
| 0x39 | Blood pressure | no |
| 0x3A | Blood oxygen | no |
| 0x3D | Contacts info | |
| 0x57 / 0x58 | Add / delete contact | contacts sync seen |
| 0x5B | Weather | |
| 0x60 | Time sync | yes |
| 0x6e / 0x91 | Bind / bind reply | yes |
| 0x92 | Common response | yes |

Note: in the old protocol 0x91 was a realtime heart-rate push; on this watch it carries the bind reply on the second notify characteristic.

## Other commands observed in the capture (meaning partly guessed)

| Cmd | Direction | Observed payload | Note |
|---|---|---|---|
| 0x20 / 0x21 | query / set | field 1 = index 0, 1, 2; set carries field 2 = 4000, 300, 30 | Looks like three numeric settings (possibly daily goals); unconfirmed |
| 0x26 | phone → watch | field 1 = 0 | unknown |
| 0x43 | both | request empty; reply has 4 fields (model and serial strings) | device info |
| 0x44, 0xA7, 0xA3, 0x9C | request empty, small reply | e.g. 0xA7 returns (2, 5, 1) | capability or state queries; unknown |
| 0x46 | phone → watch | field 1 = a version-like number (202610) | reply is a 30–31 byte message; unknown |
| 0x61 | phone → watch | field 1 = `"en"` | looks like a language setting; the watch answered with response code 1 |
| 0x69, 0x78 | phone → watch | small settings messages | unknown |
| 0x7C | phone → watch request, watch reply | reply holds a 10-byte field | per-day count 48 in checkpoints; unknown |

Response codes in cmd 0x92: 0 = success. Code 3 was returned for cmd 0x01 and cmd 0x4D, which suggests "not supported" or "not applicable", but this is a guess.

## Unknowns

1. How the 32-character bind identity is generated, and whether the watch accepts an arbitrary value. If it must come from the vendor's cloud account, a standalone driver may not be possible.
2. Meaning of cmds 0x4d and 0x7c (sample count 48 per day; candidates are stress, temperature or SpO2), and of cmds in the 0x20–0x2d and 0xA0–0xA9 ranges.
3. Unit of the timezone field, and meaning of sleep `state` / `category` values.
4. Realtime measurement and sports-session commands: not exercised in the capture.

## Watch faces

The app's SDK exposes `sendDialBinFile` (a path to a dial `.bin` file), `sendCustomDial`, `sendSelectedDialInfo`, abort commands and a transfer-progress message (state, progress, error code), with lost-packet retransmit messages. The `.bin` format and the transfer cmds are not captured here. A custom dial message carries a layout (time position, text colour, background-image MD5, height, width, thumbnail size).

## Request

Could a maintainer or contributor with this watch help confirm the bind step on a factory-reset device, and would the project accept a driver for health sync (steps, heart rate, sleep, battery, time sync) before watch faces or notifications are understood?
