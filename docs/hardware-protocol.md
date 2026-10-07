# Smart board: firmware and serial protocol

The board is an Arduino (Uno/Nano class) connected to the Raspberry Pi over USB. It reads 64 Hall
sensors through four CD74HC4067 multiplexers and drives 64 WS2812 LEDs. The app talks to it with the
line protocol below (version 2).

| Item | Default | Where to change |
|---|---|---|
| Multiplexer address S0-S3 | pins 8, 9, 10, 11 | `PIN_MUX_S0..S3` in `firmware/smartboard/smartboard.ino` |
| Multiplexer signal (mux 1-4) | pins 4, 5, 6, 7 | `MUX_SIGNAL_PINS` |
| Mux channel → square wiring | current prototype | `SQUARE_OF` table (uses `SQ('E', 2)` for readability) |
| Sensor polarity | active low (A3144), internal pull-up | `SENSOR_ACTIVE_LOW`, `SIGNAL_PULLUP` |
| Debounce | 30 ms stable reading | `DEBOUNCE_MS` |
| LED data pin / count | pin 12, 64 LEDs | `PIN_LEDS`, `NUM_LEDS` |
| LED power cap | 50 / 255 | `LED_POWER_CAP` (64 LEDs at full white ≈ 3.8 A) |
| Serial speed | 250000 baud | `BAUD_RATE` in the firmware **and** `board.baud` in config.properties |
| LED order on the strip | snake from a1 along the ranks | app side: `led.layout`, `led.origin`, `led.direction` |

## Flashing

1. Arduino IDE (or `arduino-cli`), library **Adafruit NeoPixel** from the Library Manager.
2. Open `firmware/smartboard/smartboard.ino`, board *Arduino Uno* (or yours), upload.
3. Serial Monitor at 250000 baud should show `H 2 2.0.0*..` and a `B...` line every second.

`firmware/board-test/board-test.ino` is a bring-up sketch for a new PCB (115200 baud, interactive):
LED walk by index (to check the strip order), single LED colors (GRB vs RGB), live sensor monitor with
mux/channel of every change and an 8x8 occupancy print. Use it to fill in `SQUARE_OF` for the PCB.

Command line build check: `arduino-cli compile --fqbn arduino:avr:uno firmware/smartboard`
(6.4 KB flash, 917 B RAM on an Uno).

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
* The firmware reads the serial port between multiplexer channels, so its 64-byte receive buffer never
  overflows during a scan (a full scan takes about 0.7 ms).
* The occupancy heartbeat lets the app recover from a lost `+`/`-` line (it diffs the snapshot with its
  state) and detect a dead link (no line for 4.5 s → reconnect).
* Old firmware (`READY`, `+A1` without checksum) is still recognised: sensors work, LEDs need v2.

### Latency budget (event → light)

| Step | Time |
|---|---|
| App: event → LED frame handed to the serial writer | 0.13 ms average, 0.17 ms worst (simulator, `LedRendererTest`) |
| Serial: typical delta (2 LEDs, 21 bytes) at 250000 baud | ~0.9 ms |
| Firmware parse + `strip.show()` (64 LEDs) | ~2 ms |
| Sensor change → `+E2` line | debounce 30 ms + ≤ 0.7 ms scan |
