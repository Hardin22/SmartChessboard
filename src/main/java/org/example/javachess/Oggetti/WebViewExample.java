package org.example.javachess.Oggetti;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import org.example.javachess.Oggetti.LichessMoveExecutor;

public class WebViewExample {

    private static String gameId = null; // Memorizza l'ID della partita corrente

    public static void openLichessInMain(StackPane mainPane) {
        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();

        // Monitorare l'URL per estrarre l'ID della partita
        webEngine.locationProperty().addListener((obs, oldLocation, newLocation) -> {
            if (newLocation.contains("lichess.org/")) {
                extractGameId(newLocation);  // Estrae l'ID della partita
            }
        });

        // Esegui JavaScript per login automatico quando la pagina di login è caricata
        webEngine.documentProperty().addListener((obs, oldDoc, newDoc) -> {
            if (newDoc != null && newDoc.getDocumentURI().contains("lichess.org/login")) {
                String username = org.example.javachess.Utils.ConfigManager.getProperty("lichess.username", "");
                String password = org.example.javachess.Utils.ConfigManager.getProperty("lichess.password", "");
                
                if (!username.isEmpty() && !password.isEmpty()) {
                    webEngine.executeScript(
                            "document.querySelector('input[name=username]').value = '" + username + "';" +
                            "document.querySelector('input[name=password]').value = '" + password + "';"
                    );
                }
            }
        });

        webEngine.load("https://lichess.org/login");

        // Applica lo stile per nascondere la barra di scorrimento
        webView.setStyle("-fx-scrollbar-thumb: transparent; -fx-scrollbar-track: transparent; -fx-background-color: transparent;");

        Button backButton = new Button("Indietro");
        backButton.setOnAction(e -> {
            mainPane.getChildren().remove(webView); // Rimuovi il WebView
            mainPane.getChildren().remove(backButton); // Rimuovi il pulsante "Indietro"
            mainPane.getChildren().get(0).setVisible(true); // Rendi visibile il contenuto principale
        });

        StackPane.setAlignment(backButton, Pos.TOP_LEFT);

        mainPane.getChildren().get(0).setVisible(false); // Nascondi il contenuto principale
        mainPane.getChildren().addAll(webView, backButton); // Aggiungi WebView e pulsante "Indietro"
    }

    private static void extractGameId(String url) {
        if (url.matches(".*lichess.org/\\w{8}(/.*)?$")) { // Controlla se l'URL contiene un ID partita
            gameId = url.split("/")[3]; // Estrai l'ID della partita
            System.out.println("Game ID estratto: " + gameId);
            // Puoi passare l'ID a un altro componente per eseguire mosse
            LichessMoveExecutor.setGameId(gameId); // Passa l'ID a LichessMoveExecutor
        }
    }
}
