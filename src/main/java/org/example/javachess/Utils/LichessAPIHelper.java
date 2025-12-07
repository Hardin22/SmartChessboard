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

            JSONObject jsonResponse = new JSONObject(response.getBody());
            if (jsonResponse.has("nowPlaying")) {
                return jsonResponse.getJSONArray("nowPlaying").getJSONObject(0).getString("gameId");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
