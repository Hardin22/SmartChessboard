package io.github.hardin22.javachess.Oggetti;

import java.util.List;

public class Puzzle {
    private String id;
    private String fen;
    private List<String> moves; // UCI list e.g. ["e2e4", "e7e5"]
    private int rating;
    private int ratingDeviation;
    private int popularity;
    private int nbPlays;
    private List<String> themes;
    private String gameUrl;
    private String openingTags; // Using generic tags string instead of family/variation split for simplicity

    public Puzzle(String id, String fen, List<String> moves, int rating, int ratingDeviation, int popularity,
            int nbPlays, List<String> themes, String gameUrl, String openingTags) {
        this.id = id;
        this.fen = fen;
        this.moves = moves;
        this.rating = rating;
        this.ratingDeviation = ratingDeviation;
        this.popularity = popularity;
        this.nbPlays = nbPlays;
        this.themes = themes;
        this.gameUrl = gameUrl;
        this.openingTags = openingTags;
    }

    public String getId() {
        return id;
    }

    public String getFen() {
        return fen;
    }

    public List<String> getMoves() {
        return moves;
    }

    public int getRating() {
        return rating;
    }

    public int getRatingDeviation() {
        return ratingDeviation;
    }

    public int getPopularity() {
        return popularity;
    }

    public int getNbPlays() {
        return nbPlays;
    }

    public List<String> getThemes() {
        return themes;
    }

    public String getGameUrl() {
        return gameUrl;
    }

    public String getOpeningTags() {
        return openingTags;
    }
}
