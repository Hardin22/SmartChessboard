#include <Adafruit_NeoPixel.h>

#define LED_PIN 12
#define NUM_LEDS 64

// Prova a cambiare NEO_GRB in NEO_RGB se i colori sono sbagliati
Adafruit_NeoPixel strip(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);

void setup() {
  Serial.begin(115200);
  Serial.println("DEBUG START: Accendo il primo LED...");

  strip.begin();
  strip.setBrightness(50);
  strip.show();
}

void loop() {
  // Accendi il PRIMO led in ROSSO
  Serial.println("Rosso");
  strip.setPixelColor(0, strip.Color(255, 0, 0));
  strip.show();
  delay(1000);

  // Accendi il PRIMO led in VERDE
  Serial.println("Verde");
  strip.setPixelColor(0, strip.Color(0, 255, 0));
  strip.show();
  delay(1000);

  // Accendi il PRIMO led in BLU
  Serial.println("Blu");
  strip.setPixelColor(0, strip.Color(0, 0, 255));
  strip.show();
  delay(1000);
}
