# Cycplus M2 / XOSS BLE protocol

Everything below was observed on a **Cycplus M2, firmware V1.4.0** (BLE name `M2_XXXX`,
manufacturer string `CDZN Tech Co.,Ltd`, model string `CYCPLUS M2`, SoC nRF52832).

## GATT map

| Service | Characteristics | Purpose |
| --- | --- | --- |
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic UART) | `…0002` write-without-response (RX), `…0003` notify (TX), `…0004` write + notify (CTL) | file transfer |
| `0000180a` Device Information | `2a29` manufacturer, `2a24` model, `2a25` serial, `2a26` firmware, `2a27` hardware | identification |
| `0000180f` Battery Service | `2a19` read + **notify** | battery level |
| `0000fe59` Nordic DFU | `8ec90003` buttonless DFU | firmware update |

There is **no live telemetry**: no Cycling Speed and Cadence (`0x1816`), no Heart Rate
(`0x180D`), no Location and Navigation (`0x1819`). The bike computer is the *central* for your
sensors; towards the phone it only exposes recorded files, battery and DFU.

## Command channel (CTL, `…0004`)

Commands are written to CTL, replies arrive as notifications on CTL.

| Direction | Bytes | Meaning |
| --- | --- | --- |
| → | `ff 00 ff` | status request |
| ← | `04` | idle — **M2 replies with a single byte**, XOSS G+ replies `04 00 04` |
| → | `04 00 04` | force idle |
| → | `09 00 09` | free space request |
| ← | `0a "15752/16384" <crc8>` | free / total, KB |
| → | `05 <name> <crc8>` | fetch file |
| ← | `06 <name> <crc8>` | accepted, transfer follows on TX |
| → | `07 <name> <crc8>` | send file to the device |
| ← | `08 <name> <crc8>` | accepted |
| → | `0d <name> <crc8>` | delete file (not used by this project) |
| → | `54 <unix-time LE32> <crc8>` | set the clock |
| ← | `15 …` | file parse error |

`crc8` is a plain XOR of all preceding bytes.

## File transfer (YMODEM over RX/TX)

1. Send `C` (`0x43`) on RX.
2. Block 0 arrives on TX: `SOH | 00 | FF | "<name> <size>" padded to 128 | CRC16`.
3. Reply `ACK`, then `C`.
4. Data blocks arrive; each is `SOH|STX | num | ~num | data | CRC16`.
   `SOH` carries 128 bytes, `STX` carries 1024.
5. `ACK` every good block, `NAK` to ask for a resend.
6. `EOT` (`0x04`) ends the stream — answer `NAK`, receive the second `EOT`, answer `ACK`,
   and the device returns to idle.

The checksum is **CRC16/ARC** (poly `0xA001`, reflected), *not* CRC16/XMODEM.

The ride index is `filelist.txt`, one `name size` pair per line. Newer models (XOSS NAV, G2+)
use `workouts.json` instead.

## Model differences that break naïve clients

| Model | MTU | STX (1024 B) | Idle reply | Index file |
| --- | --- | --- | --- | --- |
| XOSS G Gen1 / Sprint | 23 fixed | no | `04 00 04` | `filelist.txt` |
| **Cycplus M2** | negotiated (185 observed) | **yes** | **`04`** | `filelist.txt` |
| XOSS G2+ / Gen3 / NAV | negotiated | yes | `04 00 04` | `workouts.json` |

Practical effect: with MTU 185 a 133-byte `SOH` block arrives as a single notification, and the
M2 switches to 1024-byte `STX` blocks — 228 KB transfers in ~13 seconds against several minutes
on a G+ Gen1. A reader must size the block from the first byte rather than assume 128.

## Settings file

`Setting.json` can be pulled with `05` and pushed back with `07` (the device beeps on success).
On M2 firmware V1.4.0 it contains only `{"version": "V1"}`, so there is nothing to configure
through it. Other models are documented to keep timezone, auto-pause and backlight there.

## Sample session

```
-> DISKSPACE (09 00 09)
  <- CTL [  13] 0a 31 35 37 35 32 2f 31 36 33 38 34 29      # "15752/16384"
-> STATUS (ff 00 ff)
  <- CTL [   1] 04                                          # idle, one byte
-> FETCH filelist.txt: 05 66 69 6c 65 6c 69 73 74 2e 74 78 74 57
  <- CTL [  14] 06 66 69 6c 65 6c 69 73 74 2e 74 78 74 54
-> 'C'
  <- TX  [ 133] 01 00 ff 66 69 6c 65 6c 69 73 74 2e 74 78 74 20 31 33 30 00 …
```

## What the FIT files contain

One session per file, records at 1 Hz:
`timestamp, position_lat, position_long, distance, enhanced_speed, enhanced_altitude, grade,
cadence, heart_rate, temperature`. Session totals include distance, elapsed and timer time,
ascent, descent, average and maximum speed, average heart rate and cadence.

**No calories and no power** are recorded. Pauses show up as gaps between record timestamps —
this project turns gaps longer than 5 s into Health Connect pause segments, which reproduces
`total_timer_time` to within a few seconds (2698 s of detected gaps against 2717 s reported by
the device on a 2.5-hour ride).
