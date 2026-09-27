#include <Adafruit_NeoPixel.h>

// --- CONFIGURAZIONE ---
#define LED_PIN 12    // Pin dati della striscia (Assicurati di collegarlo qui!)
#define NUM_LEDS 64   // Numero totale di LED
#define BRIGHTNESS 50 // Luminosità (0-255)

// Layout della striscia
// true = A1->H1, poi H2->A2 (Zig-Zag / Serpente)
// false = A1->H1, poi A2->H2 (Riga per riga)
const bool SNAKE_LAYOUT = true;

Adafruit_NeoPixel strip(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);

// Forward Declarations
void processCommand(String cmd);
int getSquareIndex(String square);
void colorWipe(uint32_t color, int wait);
void rainbow(int wait);

void setup() {
  Serial.begin(115200);
  Serial.println("LED TEST STARTING...");

  strip.begin();
  strip.setBrightness(BRIGHTNESS);
  strip.show(); // Spegni tutto inizialmente

  // Test iniziale: Arcobaleno veloce per verificare che tutti funzionino
  rainbow(10);
  colorWipe(strip.Color(0, 0, 0), 10); // Pulisci

  Serial.println("READY");
  Serial.println("Comandi disponibili:");
  Serial.println("  L:A1:255:0:0  -> Accendi A1 in Rosso");
  Serial.println("  I:0:0:255:0   -> Accendi Indice 0 in Verde");
  Serial.println("  C             -> Pulisci tutto");
  Serial.println("  T             -> Test Arcobaleno");
}

void loop() {
  if (Serial.available() > 0) {
    String command = Serial.readStringUntil('\n');
    command.trim();
    processCommand(command);
  }
}

void processCommand(String cmd) {
  if (cmd.equalsIgnoreCase("C")) {
    strip.clear();
    strip.show();
    Serial.println("CLEARED");
  } else if (cmd.equalsIgnoreCase("T")) {
    rainbow(10);
    colorWipe(strip.Color(0, 0, 0), 10);
    Serial.println("TEST DONE");
  } else if (cmd.startsWith("L:")) {
    // Formato: L:A1:R:G:B
    // Esempio: L:E2:255:0:0
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
        strip.show();
        Serial.print("LED ");
        Serial.print(square);
        Serial.println(" ON");
      } else {
        Serial.println("INVALID SQUARE");
      }
    }
  } else if (cmd.startsWith("I:")) {
    // Formato: I:Index:R:G:B
    // Esempio: I:0:255:255:255
    int firstColon = cmd.indexOf(':');
    int secondColon = cmd.indexOf(':', firstColon + 1);
    int thirdColon = cmd.indexOf(':', secondColon + 1);
    int fourthColon = cmd.indexOf(':', thirdColon + 1);

    if (firstColon > 0 && secondColon > 0) {
      int index = cmd.substring(firstColon + 1, secondColon).toInt();
      int r = cmd.substring(secondColon + 1, thirdColon).toInt();
      int g = cmd.substring(thirdColon + 1, fourthColon).toInt();
      int b = cmd.substring(fourthColon + 1).toInt();

      if (index >= 0 && index < NUM_LEDS) {
        strip.setPixelColor(index, strip.Color(r, g, b));
        strip.show();
        Serial.print("INDEX ");
        Serial.print(index);
        Serial.println(" ON");
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

  // Calcolo Indice
  // Se SNAKE_LAYOUT è true:
  // Rank 0 (1): 0-7 (A1->H1)
  // Rank 1 (2): 15-8 (H2->A2)
  // Rank 2 (3): 16-23 (A3->H3)
  // ...

  int index;
  if (SNAKE_LAYOUT) {
    if (rank % 2 == 0) {
      // Righe Pari (0, 2, 4... -> Rank 1, 3, 5...) : Da Sinistra a Destra
      index = (rank * 8) + file;
    } else {
      // Righe Dispari (1, 3, 5... -> Rank 2, 4, 6...) : Da Destra a Sinistra
      index = (rank * 8) + (7 - file);
    }
  } else {
    // Lineare (Sempre Sinistra a Destra)
    index = (rank * 8) + file;
  }

  return index;
}

// --- EFFETTI ---
void colorWipe(uint32_t color, int wait) {
  for (int i = 0; i < strip.numPixels(); i++) {
    strip.setPixelColor(i, color);
    strip.show();
    delay(wait);
  }
}

void rainbow(int wait) {
  for (long firstPixelHue = 0; firstPixelHue < 5 * 65536;
       firstPixelHue += 256) {
    for (int i = 0; i < strip.numPixels(); i++) {
      int pixelHue = firstPixelHue + (i * 65536L / strip.numPixels());
      strip.setPixelColor(i, strip.gamma32(strip.ColorHSV(pixelHue)));
    }
    strip.show();
    delay(wait);
  }
}
