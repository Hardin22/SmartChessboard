// javaChess smart board firmware
// ---------------------------------------------------------------------------
// Reads 64 Hall sensors through four CD74HC4067 multiplexers, debounces them
// and reports occupancy changes to the app; drives 64 WS2812 LEDs with the
// frames the app sends. Serial protocol version 2, documented in
// docs/hardware-protocol.md. Every line ends with "*XX" (XOR checksum).
//
// Board: Arduino Uno / Nano (ATmega328P) or any board supported by
// Adafruit_NeoPixel. Library: "Adafruit NeoPixel" (Library Manager).
//
// Everything that depends on the PCB is in the CONFIGURATION section.
// ---------------------------------------------------------------------------

#include <Adafruit_NeoPixel.h>

// ============================ CONFIGURATION ================================

// Serial speed. Must match board.baud in the app (default 250000; 250000 is
// exact on a 16 MHz AVR, 115200 has a 2% error).
const unsigned long BAUD_RATE = 250000;
const char FIRMWARE_VERSION[] = "2.0.0";

// Multiplexer address lines, shared by the four CD74HC4067.
const uint8_t PIN_MUX_S0 = 8;
const uint8_t PIN_MUX_S1 = 9;
const uint8_t PIN_MUX_S2 = 10;
const uint8_t PIN_MUX_S3 = 11;

// Signal (common I/O) pin of each multiplexer.
const uint8_t MUX_SIGNAL_PINS[4] = {4, 5, 6, 7};

// A3144-style Hall sensors pull the line LOW when a magnet (piece) is near.
const bool SENSOR_ACTIVE_LOW = true;
// Use the internal pull-up on the signal pins (no external resistors needed).
const bool SIGNAL_PULLUP = true;
// Settling time after changing the multiplexer address.
const uint8_t MUX_SETTLE_US = 10;
// A reading must be stable this long before it is reported (contact bounce,
// pieces sliding over squares).
const uint16_t DEBOUNCE_MS = 30;
// Full occupancy is sent this often; the app uses it to resynchronise and to
// detect a dead link.
const uint16_t HEARTBEAT_MS = 1000;

// WS2812 data pin and number of LEDs. The order of the LEDs on the strip is
// handled by the app (led.layout / led.origin / led.direction), the firmware
// only knows LED indices 0..63.
const uint8_t PIN_LEDS = 12;
const uint16_t NUM_LEDS = 64;
// Power cap 0-255 applied to every LED. 64 LEDs at full white draw ~3.8 A:
// keep this low unless the PCB has a proper 5 V supply.
const uint8_t LED_POWER_CAP = 50;

// Square wired to each multiplexer channel: SQUARE_OF[mux][channel].
// Squares are numbered 0 = a1, 1 = b1, ... 7 = h1, 8 = a2, ... 63 = h8.
#define SQ(file, rank) ((uint8_t)(((rank) - 1) * 8 + ((file) - 'A')))
const uint8_t SQUARE_OF[4][16] = {
    // MUX 1 (signal pin 4)
    {SQ('A', 4), SQ('A', 3), SQ('A', 2), SQ('A', 1), SQ('B', 4), SQ('B', 3), SQ('B', 2), SQ('B', 1),
     SQ('C', 4), SQ('C', 3), SQ('C', 2), SQ('C', 1), SQ('D', 4), SQ('D', 3), SQ('D', 2), SQ('D', 1)},
    // MUX 2 (signal pin 5)
    {SQ('E', 4), SQ('E', 3), SQ('E', 2), SQ('E', 1), SQ('F', 4), SQ('F', 3), SQ('F', 2), SQ('F', 1),
     SQ('G', 4), SQ('G', 3), SQ('G', 2), SQ('G', 1), SQ('H', 4), SQ('H', 3), SQ('H', 2), SQ('H', 1)},
    // MUX 3 (signal pin 6)
    {SQ('D', 5), SQ('D', 6), SQ('D', 7), SQ('D', 8), SQ('B', 5), SQ('B', 6), SQ('B', 7), SQ('B', 8),
     SQ('C', 5), SQ('C', 6), SQ('C', 7), SQ('C', 8), SQ('A', 5), SQ('A', 6), SQ('A', 7), SQ('A', 8)},
    // MUX 4 (signal pin 7)
    {SQ('E', 5), SQ('E', 6), SQ('E', 7), SQ('E', 8), SQ('F', 5), SQ('F', 6), SQ('F', 7), SQ('F', 8),
     SQ('G', 5), SQ('G', 6), SQ('G', 7), SQ('G', 8), SQ('H', 5), SQ('H', 6), SQ('H', 7), SQ('H', 8)}};

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

void selectChannel(uint8_t channel) {
  digitalWrite(PIN_MUX_S0, channel & 1);
  digitalWrite(PIN_MUX_S1, (channel >> 1) & 1);
  digitalWrite(PIN_MUX_S2, (channel >> 2) & 1);
  digitalWrite(PIN_MUX_S3, (channel >> 3) & 1);
  delayMicroseconds(MUX_SETTLE_US);
}

bool readSensor(uint8_t mux) {
  bool level = digitalRead(MUX_SIGNAL_PINS[mux]) == HIGH;
  return SENSOR_ACTIVE_LOW ? !level : level;
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
  for (uint8_t channel = 0; channel < 16; channel++) {
    selectChannel(channel);
    uint16_t now = (uint16_t)millis();
    for (uint8_t mux = 0; mux < 4; mux++) {
      updateSquare(SQUARE_OF[mux][channel], readSensor(mux), now);
    }
    readSerial();  // keep the 64-byte receive buffer from overflowing
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
      strip.show();  // interrupts are off for ~2 ms: the app waits for "K" before sending more
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
      strip.show();
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

  pinMode(PIN_MUX_S0, OUTPUT);
  pinMode(PIN_MUX_S1, OUTPUT);
  pinMode(PIN_MUX_S2, OUTPUT);
  pinMode(PIN_MUX_S3, OUTPUT);
  for (uint8_t mux = 0; mux < 4; mux++) {
    pinMode(MUX_SIGNAL_PINS[mux], SIGNAL_PULLUP ? INPUT_PULLUP : INPUT);
  }

  strip.begin();
  strip.clear();
  strip.show();

  // Start from the real state of the board without reporting it square by
  // square: the app gets it with the first "B" line.
  for (uint8_t channel = 0; channel < 16; channel++) {
    selectChannel(channel);
    for (uint8_t mux = 0; mux < 4; mux++) {
      if (readSensor(mux)) occupancy |= (uint64_t)1 << SQUARE_OF[mux][channel];
    }
  }
  rawState = occupancy;
  for (uint8_t i = 0; i < 64; i++) rawChangedAt[i] = 0;

  sendHello();
  sendOccupancy();
}

void loop() {
  readSerial();
  scanBoard();
  if (millis() - lastHeartbeat >= HEARTBEAT_MS) {
    sendOccupancy();
  }
}
