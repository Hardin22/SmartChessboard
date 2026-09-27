package org.example.javachess.Utils;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.json.JSONObject;

public class LichessAPIHelper {

    private static final String LICHESS_API_URL = "https://lichess.org/api/account/playing";
    private static final String TOKEN = ConfigManager.getProperty("lichess.token");

    public static String getGameId() {
        try {
            HttpResponse<String> response = Unirest.get(LICHESS_API_URL)
                    .header("Authorization", "Bearer " + TOKEN)
                    .asString();

            if (response.getStatus() == 200) {
                String body = response.getBody();
                if (body != null && !body.isEmpty()) {
                    JSONObject jsonResponse = new JSONObject(body);
                    if (jsonResponse.has("nowPlaying")) {
                        org.json.JSONArray nowPlaying = jsonResponse.getJSONArray("nowPlaying");
                        if (nowPlaying.length() > 0) {
                            return nowPlaying.getJSONObject(0).getString("gameId");
                        }
                    }
                }
            } else {
                System.err.println("Lichess API Error: " + response.getStatus() + " - " + response.getBody());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    public static String createSeek(int timeMinutes, int incrementSeconds, boolean rated, String color) {
        java.net.HttpURLConnection connection = null;
        try {
            String urlParameters = String.format("time=%d&increment=%d&rated=%b&color=%s", 
                timeMinutes, incrementSeconds, rated, color);
            
            java.net.URL url = new java.net.URL("https://lichess.org/api/board/seek");
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            
            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = urlParameters.getBytes("utf-8");
                os.write(input, 0, input.length);
            }
            
            System.out.println("[Lichess] Seeking game: " + urlParameters);
            
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream(), "utf-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[Lichess Seek] " + line);
                     if (line.trim().isEmpty()) continue;
                     
                     try {
                         JSONObject json = new JSONObject(line);
                         if (json.has("id")) {
                             return json.getString("id");
                         }
                     } catch (Exception e) {
                     }
                }
            }
            
        } catch (java.io.IOException e) {
            System.err.println("[Lichess] Seek failed: " + e.getMessage());
            StringBuilder errorMsg = new StringBuilder("Errore: ");
            try {
                if (connection != null && connection.getErrorStream() != null) {
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(connection.getErrorStream(), "utf-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            System.err.println("[Lichess Error Body] " + line);
                            errorMsg.append(line);
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return "ERROR:" + errorMsg.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR:" + e.getMessage();
        }
        return null;
    }
}
