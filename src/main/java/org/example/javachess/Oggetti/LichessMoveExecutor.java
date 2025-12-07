package org.example.javachess.Oggetti;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.example.javachess.Utils.ConfigManager;

public class LichessMoveExecutor {

    private static final String LICHESS_API_URL = "https://lichess.org/api/board/game/";
    private static final String TOKEN = ConfigManager.getProperty("lichess.token");
    private static String gameId;

    public static void setGameId(String id) {
        gameId = id;
    }

    public static void makeMove(String move) {
        if (gameId == null) {
            System.out.println("Game ID non impostato.");
            return;
        }

        try {
            HttpResponse<String> response = Unirest.post(LICHESS_API_URL + gameId + "/move/" + move)
                    .header("Authorization", "Bearer " + TOKEN)
                    .asString();

            if (response.getStatus() == 200) {
                System.out.println("Mossa eseguita su Lichess: " + move);
            } else {
                System.out.println("Errore nell'esecuzione della mossa su Lichess: " + response.getBody());
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Mossa non valida su Lichess: " + response.getBody(), ButtonType.OK);
                    alert.showAndWait();
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
