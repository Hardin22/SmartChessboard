# Smart board: firmware and serial protocol

The board is an Arduino (Uno/Nano class) connected to the Raspberry Pi over USB. It reads 64 Hall
sensors (TI DRV5032AJ, output LOW = magnet) on four quadrant PCBs chained together, each with two
74HC165 shift registers, and drives 64 WS2812B LEDs (one per square). The app talks to it with the line
protocol below (version 2).

| Item | Default | Where to change |
|---|---|---|
| Sensor chain CLK / LOAD (/PL, active low) / DATA (QH) | pins 13, 9, 12 | `PIN_CLK`, `PIN_LOAD`, `PIN_DATA` in `firmware/smartboard/smartboard.ino` |
| Quadrants in chain order | Q0 e1-h4, Q1 a1-d4, Q2 a5-d8, Q3 e5-h8 | `QUAD_FILE`, `QUAD_RANK` |
| Bit order inside a quadrant | U101 H24..H11, then U102 H44..H31 | `BIT_RC` |
| Debounce | 80 ms stable reading (the DRV5032 samples at 20 Hz) | `DEBOUNCE_MS` |
| LED data pin / count | pin 6, 64 LEDs | `PIN_LEDS`, `NUM_LEDS` |
| LED power cap | 50 / 255 | `LED_POWER_CAP` (64 LEDs at full white ≈ 3.8 A) |
| 5 V sense of the main board | off (bare Arduino) | `USE_POWER_SENSE` (A0, 10k/10k divider on the main board) |
| Serial speed | 250000 baud | `BAUD_RATE` in the firmware **and** `board.baud` in config.properties |
| LED order on the strip | snake from a1 along the ranks | app side: `led.layout`, `led.origin`, `led.direction` |

Reading the chain: LOAD low 5 µs, high 5 µs, then for each of the 64 bits read DATA, CLK high 3 µs, CLK
low 3 µs (the first bit is valid before the first clock). Q0, the quadrant plugged into the Arduino,
comes out first; after the 64th bit the chain reads 0 (SER of the last quadrant pulled down).

## Flashing

1. Arduino IDE (or `arduino-cli`), library **Adafruit NeoPixel** from the Library Manager.
2. Open `firmware/smartboard/smartboard.ino`. On the main board (Arduino Nano with the A0 divider) set
   `USE_POWER_SENSE = true`; on a bare Arduino wired straight to the first quadrant leave it `false` (A0 is not
   connected there, so the firmware would think the 5 V is missing and read nothing).
3. Board *Arduino Nano* (`arduino:avr:nano`; clones may need *ATmega328P (Old Bootloader)*,
   `arduino:avr:nano:cpu=atmega328old`) or *Arduino Uno* (`arduino:avr:uno`), upload.
4. Serial Monitor at 250000 baud should show `H 2 3.0.0*..` and a `B...` line every second.

Quick check without the app: send `?*3F` and `R*52` (each followed by a newline) at 250000 baud; the `B` line is
the occupancy, bit i = square i (a1 = bit 0, h8 = bit 63).

Command line build check: `arduino-cli compile --fqbn arduino:avr:uno firmware/smartboard`
(6.4 KB flash, 937 B RAM on an Uno).

## App configuration (config.properties)

```properties
board.mode=auto        # auto (serial, detected, hot reconnection) | sim (software board) | off
board.port=            # empty = detect; or /dev/ttyACM0, cu.usbmodem1101, COM3
board.baud=250000
led.layout=snake       # snake | rows
led.origin=a1          # square under LED 0: a1 | h1 | a8 | h8
led.direction=ranks    # first LEDs run along the rank (a1,b1..) or the file (a1,a2..)
hardware.led.brightness=100   # %, applied by the app on top of LED_POWER_CAP
```

Port detection skips Bluetooth, debug consoles and macOS `tty.*` duplicates and tries `ttyACM*`,
`ttyUSB*`, `cu.usbmodem*`, `cu.usbserial*`, `cu.wchusbserial*` and anything described as Arduino,
CH340, CP210x, FTDI. A port is accepted only after it answers with a valid protocol line. Without a
board the app runs normally (setup and opponent-move steps complete by themselves) and keeps looking
every 3 s; unplugging and replugging the USB cable is handled while the app runs.

`-Djavachess.board=sim -Djavachess.simulator.window=true` starts a software board with a small window:
click a square to lift/place a piece, LEDs are drawn as they would be on the board.

`firmware/emulator/board_emulator.py` emulates the firmware on a pseudo-terminal (macOS/Linux, plain
Python 3) to exercise the real serial code without an Arduino:
`python3 firmware/emulator/board_emulator.py --link /tmp/fakeboard`, then start the app with
`-Djavachess.board.port=/tmp/fakeboard` (and `board.baud=115200`: macOS refuses non-standard speeds on
pseudo-terminals). Type `move e2e4`, `-e2`, `+e4` on its stdin; it prints every LED command received.
`SerialBoardEmulatorTest` uses it for handshake, sensor events, LED acknowledgements and unplug/replug
(unplug noticed in ~4 s by the missing heartbeat, replug in < 3 s, LEDs restored with a full frame).
Symlinked ports such as `/dev/serial/by-id/usb-Arduino...` can be used in `board.port`.

## Protocol v2

ASCII lines terminated by `\n`. Every line ends with `*XX`: XX is the XOR of all bytes before `*`,
as two upper-case hex digits (NMEA style). Lines with a wrong checksum are discarded.
Squares are `A1`..`H8`; numeric square indices are 0 = a1, 1 = b1, ... 7 = h1, 8 = a2, ... 63 = h8.

### Board → app

| Line | Meaning |
|---|---|
| `H <proto> <fw>` | hello, sent after reset and in reply to `?` (e.g. `H 2 2.0.0`) |
| `+E2` / `-E2` | square E2 became occupied / empty (after debounce) |
| `B <16 hex>` | full occupancy, bit i = square i; in reply to `R`, after reset and every second (heartbeat) |
| `K` | LED/brightness command applied |
| `E <reason>` | command rejected: `checksum`, `format`, `frame`, `brightness`, `overflow`, `unknown` |

### App → board

| Line | Meaning |
|---|---|
| `?` | hello request |
| `R` | occupancy request |
| `F <64 × RRGGBB>` | full frame, LED index order (389 bytes with checksum and newline) |
| `D <n × IIRRGGBB>` | only the LEDs that changed (II = LED index, hex) |
| `L <XX>` | brightness 0-255 on top of `LED_POWER_CAP` (applies to the next LED updates) |
| `C` | all LEDs off |

### Flow control and robustness

* `strip.show()` keeps interrupts off for about 2 ms, so bytes arriving meanwhile would be lost. The
  app therefore sends one LED command, waits for `K` (or `E`, or 250 ms), and only then sends the
  next one. It always sends the **newest** frame, as a delta against the frame the firmware confirmed;
  after an error, a timeout or a reconnection it sends a full frame. Bursts of LED changes are merged
  into at most ~60 frames per second before they reach the serial port.
* The firmware reads the serial port after every quadrant of the chain, so its 64-byte receive buffer never
  overflows during a scan (a full read of the 64 bits takes about 0.5 ms).
* The occupancy heartbeat lets the app recover from a lost `+`/`-` line (it diffs the snapshot with its
  state) and detect a dead link (no line for 4.5 s → reconnect).
* Old firmware (`READY`, `+A1` without checksum) is still recognised: sensors work, LEDs need v2.

### Latency budget (event → light)

| Step | Time |
|---|---|
| App: event → LED frame handed to the serial writer | 0.13 ms average, 0.17 ms worst (simulator, `LedRendererTest`) |
| Serial: typical delta (2 LEDs, 21 bytes) at 250000 baud | ~0.9 ms |
| Firmware parse + `strip.show()` (64 LEDs) | ~2 ms |
| Sensor change → `+E2` line | sensor sampling (DRV5032, 20 Hz: ≤ 50 ms) + debounce 80 ms + ≤ 0.5 ms scan |
