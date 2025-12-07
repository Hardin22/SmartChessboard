package org.example.javachess.Utils;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import org.example.javachess.Oggetti.PvpGame;

public class SerialListener {
    private SerialPort comPort;
    private PvpGame pvpGame;

    public SerialListener(String portDescription, PvpGame pvpGame) {
        this.comPort = SerialPort.getCommPort(portDescription);
        this.pvpGame = pvpGame;
        comPort.setBaudRate(9600);

        if (comPort.openPort()) {
            System.out.println("Porta seriale aperta.");
        } else {
            System.out.println("Errore nell'apertura della porta seriale.");
            return;
        }
    }

    public void startListening() {
        Thread thread = new Thread(() -> {
            StringBuilder dataBuffer = new StringBuilder();

            comPort.addDataListener(new SerialPortDataListener() {
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE)
                        return;

                    byte[] newData = new byte[comPort.bytesAvailable()];
                    comPort.readBytes(newData, newData.length);

                    for (byte b : newData) {
                        char c = (char) b;
                        if (c == '\n') {
                            String mossa = dataBuffer.toString().trim();
                            System.out.println("Mossa ricevuta: " + mossa);
                            pvpGame.handleMoveInput(mossa); // Chiama la funzione makeMove con la mossa ricevuta
                            dataBuffer.setLength(0);
                        } else {
                            dataBuffer.append(c);
                        }
                    }
                }
            });
        });
        thread.start();
    }

    public void closeConnection() {
        comPort.closePort();
    }
}
