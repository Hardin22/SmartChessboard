// javaChess smart board firmware
// ---------------------------------------------------------------------------
// Reads 64 Hall sensors (TI DRV5032AJ, output LOW = magnet) through the PCB's
// chain of four quadrants, each with two 74HC165 shift registers, debounces
// them and reports occupancy changes to the app; drives 64 WS2812B LEDs with
// the frames the app sends. Serial protocol version 2, documented in
// docs/hardware-protocol.md. Every line ends with "*XX" (XOR checksum).
//
// Board: Arduino Uno / Nano (ATmega328P). Library: "Adafruit NeoPixel"
// (Library Manager).
//
// Everything that depends on the PCB is in the CONFIGURATION section.
// ---------------------------------------------------------------------------

#include <Adafruit_NeoPixel.h>

// ============================ CONFIGURATION ================================

// Serial speed. Must match board.baud in the app (default 250000; 250000 is
// exact on a 16 MHz AVR, 115200 has a 2% error).
const unsigned long BAUD_RATE = 250000;
const char FIRMWARE_VERSION[] = "3.0.0";

// Sensor chain (connector J1 of the first quadrant: 5V, -, GND, -, CLK, LOAD,
// DATA). The 74HC165 shift on the rising edge of CLK; LOAD is /PL, active
// low (10k pull-up on the PCB); DATA is QH of the first quadrant.
const uint8_t PIN_CLK = 13;
const uint8_t PIN_LOAD = 9;
const uint8_t PIN_DATA = 12;
// Pulse widths. The 74HC parts need tens of ns at 5 V: these give a wide
// margin also at the end of the chain (3 buffer stages). A full read of the
// 64 bits takes about 0.5 ms.
const uint8_t LOAD_PULSE_US = 5;
const uint8_t CLK_PULSE_US = 3;

// A DRV5032 samples at 20 Hz: a change counts once it has stayed the same for
// this long (at least two samples), so a piece sliding over a square is not
// reported.
const uint16_t DEBOUNCE_MS = 80;
// Full occupancy is sent this often; the app uses it to resynchronise and to
// detect a dead link.
const uint16_t HEARTBEAT_MS = 1000;

// Quadrants in chain order (Q0 is the one plugged into the Arduino): the
// square of the bottom-left corner of each, as file 0-7 and rank 0-7.
// Q0 = e1-h4, Q1 = a1-d4, Q2 = a5-d8, Q3 = e5-h8.
const uint8_t QUAD_FILE[4] = {4, 0, 0, 4};
const uint8_t QUAD_RANK[4] = {0, 0, 4, 4};

// WS2812B data pin and number of LEDs (one per square; the order of the LEDs
// on the strip is handled by the app: led.layout / led.origin /
// led.direction). Without a strip connected the LED commands are still
// acknowledged, so the app works the same.
const uint8_t PIN_LEDS = 6;
const uint16_t NUM_LEDS = 64;
// Power cap 0-255 applied to every LED. 64 LEDs at full white draw ~3.8 A:
// keep this low unless the PCB has a proper 5 V supply.
const uint8_t LED_POWER_CAP = 50;

// Main board only: A0 reads the board's 5 V through a 10k/10k divider. While
// it is missing (only the PC's USB) CLK, LOAD and the LED pin are left high
// impedance so the quadrants are not powered through the pins, and every
// square reads empty. Set to false on a bare Arduino wired straight to the
// first quadrant (A0 is not connected there).
const bool USE_POWER_SENSE = false;
const uint8_t PIN_POWER_SENSE = A0;
const uint16_t POWER_SENSE_MIN_ADC = 400;  // ~1.95 V at the pin = bus below ~3.9 V

// ========================= END OF CONFIGURATION ============================

const uint8_t PROTOCOL_VERSION = 2;
const uint16_t MAX_LINE = 400;  // longest command: "F" + 384 hex + "*XX"

Adafruit_NeoPixel strip(NUM_LEDS, PIN_LEDS, NEO_GRB + NEO_KHZ800);

// Sensor state, indexed by square.
uint64_t occupancy = 0;          // debounced state, bit i = square i
uint64_t rawState = 0;           // last raw reading
uint16_t rawChangedAt[64];       // low 16 bits of millis() at the last raw change
unsigned long lastHeartbeat = 0;

// Serial input.
char line[MAX_LINE + 1];
uint16_t lineLength = 0;
bool lineOverflow = false;

uint8_t hostBrightness = 255;    // set by "L", scaled by LED_POWER_CAP

void readSerial();

// ------------------------------- output ------------------------------------

uint8_t txChecksum = 0;

void txBegin() { txChecksum = 0; }

void txChar(char c) {
  txChecksum ^= (uint8_t)c;
  Serial.write(c);
}

void txText(const char *text) {
  while (*text) txChar(*text++);
}

const char HEX_DIGITS[] = "0123456789ABCDEF";

void txEnd() {
  Serial.write('*');
  Serial.write(HEX_DIGITS[txChecksum >> 4]);
  Serial.write(HEX_DIGITS[txChecksum & 0x0F]);
  Serial.write('\n');
}

void sendHello() {
  txBegin();
  txText("H ");
  txChar('0' + PROTOCOL_VERSION);
  txChar(' ');
  txText(FIRMWARE_VERSION);
  txEnd();
}

void sendSquare(uint8_t square, bool occupied) {
  txBegin();
  txChar(occupied ? '+' : '-');
  txChar('A' + (square % 8));
  txChar('1' + (square / 8));
  txEnd();
}

void sendOccupancy() {
  txBegin();
  txChar('B');
  for (int shift = 60; shift >= 0; shift -= 4) {
    txChar(HEX_DIGITS[(uint8_t)(occupancy >> shift) & 0x0F]);
  }
  txEnd();
  lastHeartbeat = millis();
}

void sendAck() {
  txBegin();
  txChar('K');
  txEnd();
}

void sendError(const char *reason) {
  txBegin();
  txText("E ");
  txText(reason);
  txEnd();
}

// ------------------------------- sensors -----------------------------------

// Square read at each position of the chain (64 bits, first read = Q0's).
// Inside a quadrant the bits come out as U101 D7..D0 = H24 H23 H22 H21 H14
// H13 H12 H11, then U102 D7..D0 = H44 H43 H42 H41 H34 H33 H32 H31, where Hrc
// is row r from the top (towards rank 8) and column c from the left.
const uint8_t BIT_RC[16] = {24, 23, 22, 21, 14, 13, 12, 11, 44, 43, 42, 41, 34, 33, 32, 31};
uint8_t squareOfBit[64];
bool boardPowered = true;

void buildSquareTable() {
  for (uint8_t q = 0; q < 4; q++) {
    for (uint8_t i = 0; i < 16; i++) {
      uint8_t r = BIT_RC[i] / 10, c = BIT_RC[i] % 10;
      squareOfBit[q * 16 + i] = (QUAD_RANK[q] + (4 - r)) * 8 + QUAD_FILE[q] + (c - 1);
    }
  }
}

// Reads the 64 sensors: bit i of the result = square i occupied. The 74HC165
// shows its first bit right after the load, then shifts on every rising edge.
uint64_t readChain() {
  if (!boardPowered) return 0;
  digitalWrite(PIN_LOAD, LOW);
  delayMicroseconds(LOAD_PULSE_US);
  digitalWrite(PIN_LOAD, HIGH);
  delayMicroseconds(LOAD_PULSE_US);
  uint64_t occupied = 0;
  for (uint8_t i = 0; i < 64; i++) {
    if (digitalRead(PIN_DATA) == LOW) {  // DRV5032: LOW = magnet = piece
      occupied |= (uint64_t)1 << squareOfBit[i];
    }
    digitalWrite(PIN_CLK, HIGH);
    delayMicroseconds(CLK_PULSE_US);
    digitalWrite(PIN_CLK, LOW);
    delayMicroseconds(CLK_PULSE_US);
    if ((i & 15) == 15) readSerial();  // keep the 64-byte receive buffer from overflowing
  }
  return occupied;
}

void updateSquare(uint8_t square, bool reading, uint16_t now) {
  uint64_t bit = (uint64_t)1 << square;
  bool raw = (rawState & bit) != 0;
  if (reading != raw) {
    rawState ^= bit;
    rawChangedAt[square] = now;
    return;
  }
  bool stable = (occupancy & bit) != 0;
  if (reading != stable && (uint16_t)(now - rawChangedAt[square]) >= DEBOUNCE_MS) {
    occupancy ^= bit;
    sendSquare(square, reading);
  }
}

void scanBoard() {
  uint64_t reading = readChain();
  uint16_t now = (uint16_t)millis();
  for (uint8_t square = 0; square < 64; square++) {
    updateSquare(square, (reading >> square) & 1, now);
  }
}

// Outputs towards the quadrants only while the board has its 5 V.
void setOutputs(bool on) {
  if (on) {
    pinMode(PIN_CLK, OUTPUT);
    digitalWrite(PIN_CLK, LOW);
    pinMode(PIN_LOAD, OUTPUT);
    digitalWrite(PIN_LOAD, HIGH);
    pinMode(PIN_DATA, INPUT_PULLUP);  // no cable: squares read empty instead of random
    pinMode(PIN_LEDS, OUTPUT);
    digitalWrite(PIN_LEDS, LOW);
  } else {
    pinMode(PIN_CLK, INPUT);
    pinMode(PIN_LOAD, INPUT);
    pinMode(PIN_DATA, INPUT);
    pinMode(PIN_LEDS, INPUT);
  }
}

// The divider brings A0 back to its real value at once; discharging it first
// makes a disconnected A0 read 0 (no 5 V) instead of floating.
bool readPowerSense() {
  pinMode(PIN_POWER_SENSE, OUTPUT);
  digitalWrite(PIN_POWER_SENSE, LOW);
  delayMicroseconds(10);
  pinMode(PIN_POWER_SENSE, INPUT);
  return (uint16_t)analogRead(PIN_POWER_SENSE) > POWER_SENSE_MIN_ADC;
}

void checkPower() {
  if (!USE_POWER_SENSE) return;
  bool powered = readPowerSense();
  if (powered == boardPowered) return;
  boardPowered = powered;
  setOutputs(powered);
  if (powered) {
    delay(50);  // let sensors and LEDs settle
    strip.begin();
    strip.show();
  } else {
    sendError("power");
  }
}

// --------------------------------- LEDs ------------------------------------

int hexValue(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

// Parses `digits` hex characters; returns -1 on a bad digit.
long parseHex(const char *text, uint8_t digits) {
  long value = 0;
  for (uint8_t i = 0; i < digits; i++) {
    int v = hexValue(text[i]);
    if (v < 0) return -1;
    value = (value << 4) | v;
  }
  return value;
}

void setLed(uint8_t index, long rgb) {
  uint16_t scale = (uint16_t)LED_POWER_CAP * hostBrightness / 255;
  uint8_t r = (uint8_t)(((rgb >> 16) & 0xFF) * scale / 255);
  uint8_t g = (uint8_t)(((rgb >> 8) & 0xFF) * scale / 255);
  uint8_t b = (uint8_t)((rgb & 0xFF) * scale / 255);
  strip.setPixelColor(index, r, g, b);
}

// "F" + 64 x RRGGBB
bool applyFullFrame(const char *payload, uint16_t length) {
  if (length != 1 + NUM_LEDS * 6) return false;
  for (uint16_t i = 0; i < NUM_LEDS; i++) {
    long rgb = parseHex(payload + 1 + i * 6, 6);
    if (rgb < 0) return false;
    setLed(i, rgb);
  }
  return true;
}

// "D" + n x IIRRGGBB
bool applyDelta(const char *payload, uint16_t length) {
  if (length < 9 || (length - 1) % 8 != 0) return false;
  for (uint16_t p = 1; p < length; p += 8) {
    long index = parseHex(payload + p, 2);
    long rgb = parseHex(payload + p + 2, 6);
    if (index < 0 || index >= NUM_LEDS || rgb < 0) return false;
    setLed((uint8_t)index, rgb);
  }
  return true;
}

// ------------------------------- commands ----------------------------------

void handleLine(char *text, uint16_t length) {
  // split "<payload>*XX" and verify the checksum
  if (length < 4 || text[length - 3] != '*') {
    sendError("format");
    return;
  }
  long expected = parseHex(text + length - 2, 2);
  uint16_t payloadLength = length - 3;
  uint8_t checksum = 0;
  for (uint16_t i = 0; i < payloadLength; i++) checksum ^= (uint8_t)text[i];
  if (expected < 0 || checksum != expected) {
    sendError("checksum");
    return;
  }

  switch (text[0]) {
    case '?':
      sendHello();
      break;
    case 'R':
      sendOccupancy();
      break;
    case 'F':
    case 'D': {
      bool ok = text[0] == 'F' ? applyFullFrame(text, payloadLength) : applyDelta(text, payloadLength);
      if (!ok) {
        sendError("frame");
        return;
      }
      if (boardPowered) strip.show();  // interrupts are off for ~2 ms: the app waits for "K" before sending more
      sendAck();
      break;
    }
    case 'L': {
      long value = payloadLength == 3 ? parseHex(text + 1, 2) : -1;
      if (value < 0) {
        sendError("brightness");
        return;
      }
      hostBrightness = (uint8_t)value;
      sendAck();
      break;
    }
    case 'C':
      strip.clear();
      if (boardPowered) strip.show();
      sendAck();
      break;
    default:
      sendError("unknown");
  }
}

void readSerial() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (lineOverflow) {
        sendError("overflow");
      } else if (lineLength > 0) {
        line[lineLength] = '\0';
        handleLine(line, lineLength);
      }
      lineLength = 0;
      lineOverflow = false;
    } else if (lineLength < MAX_LINE) {
      line[lineLength++] = c;
    } else {
      lineOverflow = true;
    }
  }
}

// --------------------------------- main ------------------------------------

void setup() {
  Serial.begin(BAUD_RATE);
  buildSquareTable();
  if (USE_POWER_SENSE) boardPowered = readPowerSense();
  setOutputs(boardPowered);

  strip.begin();
  strip.clear();
  if (boardPowered) strip.show();

  // Start from the real state of the board without reporting it square by
  // square: the app gets it with the first "B" line.
  occupancy = readChain();
  rawState = occupancy;
  for (uint8_t i = 0; i < 64; i++) rawChangedAt[i] = 0;

  sendHello();
  sendOccupancy();
}

void loop() {
  readSerial();
  checkPower();
  scanBoard();
  if (millis() - lastHeartbeat >= HEARTBEAT_MS) {
    sendOccupancy();
  }
}
