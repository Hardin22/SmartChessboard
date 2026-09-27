#include <Adafruit_NeoPixel.h>

// --- CONFIGURAZIONE PIN ---
const int PIN_S0 = 8;
const int PIN_S1 = 9;
const int PIN_S2 = 10;
const int PIN_S3 = 11;

// Pin di Segnale (UNICI per ogni Mux)
const int PIN_SIG_MUX1 = 4;
const int PIN_SIG_MUX2 = 5;
const int PIN_SIG_MUX3 = 6;
const int PIN_SIG_MUX4 = 7;

// LED Configuration
#define LED_PIN 12
#define NUM_LEDS 64
#define BRIGHTNESS 50
const bool SNAKE_LAYOUT = true;

Adafruit_NeoPixel strip(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);

const int muxSignalPins[4] = {PIN_SIG_MUX1, PIN_SIG_MUX2, PIN_SIG_MUX3,
                              PIN_SIG_MUX4};

// --- MAPPATURA FISICA ---
const char *mapScacchiera[4][16] = {
    // MUX 1
    {"A4", "A3", "A2", "A1", "B4", "B3", "B2", "B1", "C4", "C3", "C2", "C1",
     "D4", "D3", "D2", "D1"},
    // MUX 2
    {"E4", "E3", "E2", "E1", "F4", "F3", "F2", "F1", "G4", "G3", "G2", "G1",
     "H4", "H3", "H2", "H1"},
    // MUX 3
    {"D5", "D6", "D7", "D8", "B5", "B6", "B7", "B8", "C5", "C6", "C7", "C8",
     "A5", "A6", "A7", "A8"},
    // MUX 4
    {"E5", "E6", "E7", "E8", "F5", "F6", "F7", "F8", "G5", "G6", "G7", "G8",
     "H5", "H6", "H7", "H8"}};

// --- STATO DEL SISTEMA ---
bool boardState[4][16];
unsigned long lastDebounceTime[4][16];
const unsigned long DEBOUNCE_DELAY = 50; // ms

// Forward Declarations
void handleSerialInput();
void scanBoard();
void fullRescan();
void setMuxAddress(int channel);
void sendUpdate(int mux, int channel, bool present);
int getSquareIndex(String square);
void processLedCommand(String command, bool showNow);

void setup() {
  Serial.begin(115200);

  pinMode(PIN_S0, OUTPUT);
  pinMode(PIN_S1, OUTPUT);
  pinMode(PIN_S2, OUTPUT);
  pinMode(PIN_S3, OUTPUT);

  for (int i = 0; i < 4; i++) {
    pinMode(muxSignalPins[i], INPUT_PULLUP);
  }

  // Inizializza stato
  for (int m = 0; m < 4; m++) {
    for (int c = 0; c < 16; c++) {
      boardState[m][c] = false;
      lastDebounceTime[m][c] = 0;
    }
  }

  // Init LEDs
  strip.begin();
  strip.setBrightness(BRIGHTNESS);
  strip.show(); // Off

  // Invia segnale di pronto
  Serial.println("READY");
}

void loop() {
  handleSerialInput();
  scanBoard();
}

void handleSerialInput() {
  if (Serial.available() > 0) {
    String command = Serial.readStringUntil('\n');
    command.trim();

    if (command == "R") {
      fullRescan();
    } else if (command.startsWith("L:")) {
      processLedCommand(command, true); // True = Show immediately
    } else if (command.startsWith("P:")) {
      processLedCommand(command, false); // False = Don't show yet
    } else if (command == "S") {
      strip.show();
    } else if (command == "C") {
      strip.clear();
      strip.show();
    }
  }
}

void processLedCommand(String cmd, bool showNow) {
  // Format: L:A1:R:G:B or P:A1:R:G:B
  int firstColon = cmd.indexOf(':');
  int secondColon = cmd.indexOf(':', firstColon + 1);
  int thirdColon = cmd.indexOf(':', secondColon + 1);
  int fourthColon = cmd.indexOf(':', thirdColon + 1);

  if (firstColon > 0 && secondColon > 0) {
    String square = cmd.substring(firstColon + 1, secondColon);
    int r = cmd.substring(secondColon + 1, thirdColon).toInt();
    int g = cmd.substring(thirdColon + 1, fourthColon).toInt();
    int b = cmd.substring(fourthColon + 1).toInt();

    int index = getSquareIndex(square);
    if (index >= 0 && index < NUM_LEDS) {
      strip.setPixelColor(index, strip.Color(r, g, b));
      if (showNow) {
        strip.show();
      }
    }
  }
}

int getSquareIndex(String square) {
  square.toUpperCase();
  if (square.length() != 2)
    return -1;

  char fileChar = square.charAt(0); // 'A'-'H'
  char rankChar = square.charAt(1); // '1'-'8'

  int file = fileChar - 'A'; // 0-7
  int rank = rankChar - '1'; // 0-7

  if (file < 0 || file > 7 || rank < 0 || rank > 7)
    return -1;

  int index;
  if (SNAKE_LAYOUT) {
    if (rank % 2 == 0) {
      index = (rank * 8) + file;
    } else {
      index = (rank * 8) + (7 - file);
    }
  } else {
    index = (rank * 8) + file;
  }
  return index;
}

void scanBoard() {
  for (int channel = 0; channel < 16; channel++) {
    setMuxAddress(channel);

    for (int muxIndex = 0; muxIndex < 4; muxIndex++) {
      // Lettura sensore (LOW = Pezzo Presente per A3144)
      bool reading = (digitalRead(muxSignalPins[muxIndex]) == LOW);

      // Debounce logic
      if (reading != boardState[muxIndex][channel]) {
        if ((millis() - lastDebounceTime[muxIndex][channel]) > DEBOUNCE_DELAY) {
          boardState[muxIndex][channel] = reading;
          lastDebounceTime[muxIndex][channel] = millis();
          sendUpdate(muxIndex, channel, reading);
        }
      } else {
        lastDebounceTime[muxIndex][channel] = millis();
      }
    }
  }
}

void setMuxAddress(int channel) {
  digitalWrite(PIN_S0, (channel & 1));
  digitalWrite(PIN_S1, (channel & 2));
  digitalWrite(PIN_S2, (channel & 4));
  digitalWrite(PIN_S3, (channel & 8));
  delayMicroseconds(20);
}

void sendUpdate(int mux, int channel, bool present) {
  const char *squareName = mapScacchiera[mux][channel];
  if (present) {
    Serial.print("+");
  } else {
    Serial.print("-");
  }
  Serial.println(squareName);
}

void fullRescan() {
  for (int m = 0; m < 4; m++) {
    for (int c = 0; c < 16; c++) {
      sendUpdate(m, c, boardState[m][c]);
    }
  }
}