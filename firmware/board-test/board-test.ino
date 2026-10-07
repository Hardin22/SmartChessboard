// Hardware bring-up test for the smart board (LEDs and sensors), useful when a
// new PCB arrives. Not used by the app: flash firmware/smartboard afterwards.
//
// Open the Serial Monitor at 115200 baud, "Newline" line ending. Commands:
//   T          rainbow over all LEDs
//   W          walk: light LED 0, 1, 2 ... one at a time (checks the strip order)
//   I <n>      light only LED index n in white
//   C          all LEDs off
//   S          print the sensors once as an 8x8 board (X = piece)
//   M          monitor: print every sensor change with its mux/channel (S or C stops)
//   1/2/3      first LED red / green / blue (checks NEO_GRB vs NEO_RGB)
//
// Pins and the mux -> square table must match firmware/smartboard.

#include <Adafruit_NeoPixel.h>

const uint8_t PIN_MUX_S0 = 8;
const uint8_t PIN_MUX_S1 = 9;
const uint8_t PIN_MUX_S2 = 10;
const uint8_t PIN_MUX_S3 = 11;
const uint8_t MUX_SIGNAL_PINS[4] = {4, 5, 6, 7};
const bool SENSOR_ACTIVE_LOW = true;
const uint8_t PIN_LEDS = 12;
const uint16_t NUM_LEDS = 64;
const uint8_t BRIGHTNESS = 40;

#define SQ(file, rank) ((uint8_t)(((rank) - 1) * 8 + ((file) - 'A')))
const uint8_t SQUARE_OF[4][16] = {
    {SQ('A', 4), SQ('A', 3), SQ('A', 2), SQ('A', 1), SQ('B', 4), SQ('B', 3), SQ('B', 2), SQ('B', 1),
     SQ('C', 4), SQ('C', 3), SQ('C', 2), SQ('C', 1), SQ('D', 4), SQ('D', 3), SQ('D', 2), SQ('D', 1)},
    {SQ('E', 4), SQ('E', 3), SQ('E', 2), SQ('E', 1), SQ('F', 4), SQ('F', 3), SQ('F', 2), SQ('F', 1),
     SQ('G', 4), SQ('G', 3), SQ('G', 2), SQ('G', 1), SQ('H', 4), SQ('H', 3), SQ('H', 2), SQ('H', 1)},
    {SQ('D', 5), SQ('D', 6), SQ('D', 7), SQ('D', 8), SQ('B', 5), SQ('B', 6), SQ('B', 7), SQ('B', 8),
     SQ('C', 5), SQ('C', 6), SQ('C', 7), SQ('C', 8), SQ('A', 5), SQ('A', 6), SQ('A', 7), SQ('A', 8)},
    {SQ('E', 5), SQ('E', 6), SQ('E', 7), SQ('E', 8), SQ('F', 5), SQ('F', 6), SQ('F', 7), SQ('F', 8),
     SQ('G', 5), SQ('G', 6), SQ('G', 7), SQ('G', 8), SQ('H', 5), SQ('H', 6), SQ('H', 7), SQ('H', 8)}};

Adafruit_NeoPixel strip(NUM_LEDS, PIN_LEDS, NEO_GRB + NEO_KHZ800);
bool monitoring = false;
bool lastReading[4][16];

void selectChannel(uint8_t channel) {
  digitalWrite(PIN_MUX_S0, channel & 1);
  digitalWrite(PIN_MUX_S1, (channel >> 1) & 1);
  digitalWrite(PIN_MUX_S2, (channel >> 2) & 1);
  digitalWrite(PIN_MUX_S3, (channel >> 3) & 1);
  delayMicroseconds(10);
}

bool readSensor(uint8_t mux) {
  bool level = digitalRead(MUX_SIGNAL_PINS[mux]) == HIGH;
  return SENSOR_ACTIVE_LOW ? !level : level;
}

void printSquare(uint8_t square) {
  Serial.print((char)('A' + square % 8));
  Serial.print((char)('1' + square / 8));
}

void printBoard() {
  bool occupied[64] = {false};
  for (uint8_t channel = 0; channel < 16; channel++) {
    selectChannel(channel);
    for (uint8_t mux = 0; mux < 4; mux++) occupied[SQUARE_OF[mux][channel]] = readSensor(mux);
  }
  for (int rank = 7; rank >= 0; rank--) {
    Serial.print(rank + 1);
    Serial.print(' ');
    for (int file = 0; file < 8; file++) Serial.print(occupied[rank * 8 + file] ? " X" : " .");
    Serial.println();
  }
  Serial.println("   a b c d e f g h");
}

void monitorSensors() {
  for (uint8_t channel = 0; channel < 16; channel++) {
    selectChannel(channel);
    for (uint8_t mux = 0; mux < 4; mux++) {
      bool now = readSensor(mux);
      if (now != lastReading[mux][channel]) {
        lastReading[mux][channel] = now;
        Serial.print(now ? "+ " : "- ");
        printSquare(SQUARE_OF[mux][channel]);
        Serial.print("  (mux ");
        Serial.print(mux + 1);
        Serial.print(", channel ");
        Serial.print(channel);
        Serial.println(")");
      }
    }
  }
}

void rainbow() {
  for (long hue = 0; hue < 3 * 65536L; hue += 512) {
    for (uint16_t i = 0; i < NUM_LEDS; i++) {
      strip.setPixelColor(i, strip.gamma32(strip.ColorHSV(hue + i * 65536L / NUM_LEDS)));
    }
    strip.show();
    delay(5);
  }
  strip.clear();
  strip.show();
}

void walk() {
  for (uint16_t i = 0; i < NUM_LEDS; i++) {
    strip.clear();
    strip.setPixelColor(i, 255, 255, 255);
    strip.show();
    Serial.print("LED ");
    Serial.println(i);
    delay(150);
  }
  strip.clear();
  strip.show();
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_MUX_S0, OUTPUT);
  pinMode(PIN_MUX_S1, OUTPUT);
  pinMode(PIN_MUX_S2, OUTPUT);
  pinMode(PIN_MUX_S3, OUTPUT);
  for (uint8_t mux = 0; mux < 4; mux++) pinMode(MUX_SIGNAL_PINS[mux], INPUT_PULLUP);
  strip.begin();
  strip.setBrightness(BRIGHTNESS);
  strip.clear();
  strip.show();
  Serial.println("Board test ready. Commands: T W I<n> C S M 1 2 3");
}

void loop() {
  if (monitoring) monitorSensors();
  if (Serial.available() == 0) return;
  String command = Serial.readStringUntil('\n');
  command.trim();
  if (command.length() == 0) return;
  char c = toupper(command.charAt(0));
  monitoring = false;
  switch (c) {
    case 'T': rainbow(); break;
    case 'W': walk(); break;
    case 'I': {
      int index = command.substring(1).toInt();
      strip.clear();
      if (index >= 0 && index < NUM_LEDS) strip.setPixelColor(index, 255, 255, 255);
      strip.show();
      break;
    }
    case 'C': strip.clear(); strip.show(); break;
    case 'S': printBoard(); break;
    case 'M':
      for (uint8_t channel = 0; channel < 16; channel++) {
        selectChannel(channel);
        for (uint8_t mux = 0; mux < 4; mux++) lastReading[mux][channel] = readSensor(mux);
      }
      monitoring = true;
      Serial.println("Monitoring sensors (send S or C to stop)");
      break;
    case '1': strip.setPixelColor(0, 255, 0, 0); strip.show(); Serial.println("LED 0 should be RED"); break;
    case '2': strip.setPixelColor(0, 0, 255, 0); strip.show(); Serial.println("LED 0 should be GREEN"); break;
    case '3': strip.setPixelColor(0, 0, 0, 255); strip.show(); Serial.println("LED 0 should be BLUE"); break;
    default: Serial.println("Unknown command");
  }
}
