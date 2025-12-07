package org.example.javachess.Controllers;

import org.firmata4j.firmata.FirmataDevice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Scanner;

public class LEDController {

    private static final byte START_SYSEX = (byte) 0xF0;
    private static final byte END_SYSEX = (byte) 0xF7;
    private static final byte LED_SYSEX = 0x0F; // Codice del comando Sysex personalizzato
    private FirmataDevice device;
    private static final int NUM_LEDS = 64; // Numero totale di LED

    public LEDController(String portName) throws IOException, InterruptedException {
        device = new FirmataDevice(portName);
        device.start();
        device.ensureInitializationIsDone();
    }

    // Metodo per impostare il colore di un singolo LED tramite indice
    public void setLEDColor(int index, int r, int g, int b) throws IOException, InterruptedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(START_SYSEX);
        output.write(LED_SYSEX);
        output.write(0x01); // Sub-comando

        // Codifica dell'indice del LED (può essere superiore a 127)
        writeIntAsTwo7bitBytes(output, index);

        // Codifica dei valori RGB (possono essere superiori a 127)
        writeIntAsTwo7bitBytes(output, r);
        writeIntAsTwo7bitBytes(output, g);
        writeIntAsTwo7bitBytes(output, b);

        output.write(END_SYSEX);
        byte[] message = output.toByteArray();
        device.sendMessage(message);
        // Attendi per assicurarti che il comando sia processato
        Thread.sleep(10);
    }

    // Metodo per spegnere tutti i LED
    public void clearAllLEDs() throws IOException, InterruptedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(START_SYSEX);
        output.write(LED_SYSEX);
        output.write(0x03); // Sub-comando
        output.write(END_SYSEX);
        byte[] message = output.toByteArray();
        device.sendMessage(message);
        // Attendi per assicurarti che il comando sia processato
        Thread.sleep(10);
    }

    // Metodo per scrivere un intero come due byte a 7 bit
    private void writeIntAsTwo7bitBytes(ByteArrayOutputStream output, int value) {
        output.write(value & 0x7F);          // 7 bit meno significativi
        output.write((value >> 7) & 0x7F);   // 7 bit più significativi
    }

    public void close() throws IOException {
        device.stop();
    }

    // Metodo main per testare la classe
    public static void main(String[] args) {
        try {
            String portName = "/dev/cu.usbmodem2101"; // Sostituisci con la tua porta
            LEDController ledController = new LEDController(portName);
            Scanner scanner = new Scanner(System.in);

            ledController.clearAllLEDs(); // Spegne tutti i LED all'inizio

            while (true) {
                System.out.print("Inserisci il numero del LED da accendere (0-" + (NUM_LEDS - 1) + "): ");
                int ledNumber = scanner.nextInt();

                if (ledNumber >= 0 && ledNumber < NUM_LEDS) {
                    ledController.clearAllLEDs(); // Spegne tutti i LED
                    ledController.setLEDColor(ledNumber, 255, 255, 255); // Accende il LED di colore verde
                } else {
                    System.out.println("Numero LED non valido. Riprova.");
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

//un buon giallo è 255, 70, 0
//un buon arancione è 255, 40, 0
//il bianco è figo per indicare le mosse possibili