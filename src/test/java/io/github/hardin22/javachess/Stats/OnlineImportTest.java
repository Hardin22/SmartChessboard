package io.github.hardin22.javachess.Stats;

import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Services.GameArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Imports from canned Lichess and Chess.com answers (no network). */
class OnlineImportTest {

    static final String LICHESS_PGN = """
            [Event "Rated blitz game"]
            [Site "https://lichess.org/abcd1234"]
            [White "hardin22"]
            [Black "rival"]
            [Result "1-0"]
            [UTCDate "2026.10.05"]
            [UTCTime "19:22:01"]
            [WhiteElo "1650"]
            [BlackElo "1702"]
            [Variant "Standard"]
            [TimeControl "180+2"]
            [ECO "C50"]
            [Opening "Italian Game: Giuoco Piano"]
            [Termination "Normal"]

            1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0

            [Event "Rated bullet game"]
            [White "rival"]
            [Black "hardin22"]
            [Result "1-0"]
            [UTCDate "2026.10.04"]
            [UTCTime "08:00:00"]
            [WhiteElo "1700"]
            [BlackElo "1655"]
            [Variant "Standard"]
            [TimeControl "60+0"]
            [Termination "Time forfeit"]

            1. d4 d5 2. c4 1-0

            [Event "Casual chess960 game"]
            [White "hardin22"]
            [Black "x"]
            [Result "0-1"]
            [Variant "Chess960"]
            [FEN "bqnbrkrn/pppppppp/8/8/8/8/PPPPPPPP/BQNBRKRN w KQkq - 0 1"]
            [SetUp "1"]

            1. e4 e5 0-1
            """;

    static final String CHESSCOM_PGN = """
            [Event "Live Chess"]
            [Site "Chess.com"]
            [Date "2026.09.30"]
            [White "Hardin22"]
            [Black "opponent"]
            [Result "1/2-1/2"]
            [WhiteElo "1500"]
            [BlackElo "1490"]
            [TimeControl "600"]
            [EndDate "2026.09.30"]
            [EndTime "21:10:05"]
            [ECO "D06"]
            [ECOUrl "https://www.chess.com/openings/Queens-Gambit-Declined-2...e6"]
            [Termination "Game drawn by agreement"]

            1. d4 d5 2. c4 e6 1/2-1/2
            """;

    @TempDir
    Path dir;
    GameArchiveService archive;
    final Map<String, HttpResponse<String>> answers = new HashMap<>();
    final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        archive = new GameArchiveService(dir.resolve("archive.json"), null, dir.resolve("backups"));
    }

    OnlineImport importer() {
        return new OnlineImport((url, accept) -> {
            asked.add(url);
            HttpResponse<String> r = answers.entrySet().stream().filter(e -> url.startsWith(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(response(404, ""));
            return CompletableFuture.completedFuture(r);
        }, archive);
    }

    @Test
    void lichessGamesWithRatingsAndItalianTerminations() {
        answers.put("https://lichess.org/api/games/user/hardin22", response(200, LICHESS_PGN));
        OnlineImport.Result r = importer().importRecent(OnlineImport.Source.LICHESS, "hardin22", 20).join();
        assertTrue(r.ok());
        assertEquals(2, r.imported().size(), "the chess960 game is skipped");
        assertEquals("2 partite importate", r.message());
        ArchivedGame newest = r.imported().get(0);
        assertEquals(ArchivedGame.GameMode.LICHESS, newest.mode());
        assertEquals("hardin22 (1650)", newest.white());
        assertEquals("rival (1702)", newest.black());
        assertEquals("1-0", newest.result());
        assertEquals("Scaccomatto", newest.termination());
        assertEquals("C50 Italian Game: Giuoco Piano", newest.opening());
        assertEquals("3+2", newest.timeControl());
        assertEquals(LocalDateTime.of(2026, 10, 5, 19, 22, 1), newest.playedAt());
        assertEquals(1650, newest.whiteRating(), "the review reads the rating back");
        assertEquals("Tempo", r.imported().get(1).termination());
        assertEquals(2, archive.size());
        assertTrue(asked.get(0).contains("max=20"));
    }

    @Test
    void importingTwiceSkipsTheGamesAlreadyThere() {
        answers.put("https://lichess.org/api/games/user/hardin22", response(200, LICHESS_PGN));
        importer().importRecent(OnlineImport.Source.LICHESS, "hardin22", 20).join();
        OnlineImport.Result again = importer().importRecent(OnlineImport.Source.LICHESS, "hardin22", 20).join();
        assertEquals(0, again.imported().size());
        assertEquals(2, again.alreadyThere());
        assertEquals("0 partite importate, 2 già presenti", again.message());
        assertEquals(2, archive.size());
    }

    @Test
    void chessComMonthlyArchivesNewestFirst() {
        answers.put("https://api.chess.com/pub/player/hardin22/games/archives", response(200,
                "{\"archives\":[\"https://api.chess.com/pub/player/hardin22/games/2026/08\","
                        + "\"https://api.chess.com/pub/player/hardin22/games/2026/09\"]}"));
        answers.put("https://api.chess.com/pub/player/hardin22/games/2026/09/pgn", response(200, CHESSCOM_PGN));
        answers.put("https://api.chess.com/pub/player/hardin22/games/2026/08/pgn", response(200, ""));
        OnlineImport.Result r = importer().importRecent(OnlineImport.Source.CHESS_COM, "Hardin22", 1).join();
        assertTrue(r.ok());
        assertEquals(1, r.imported().size());
        ArchivedGame g = r.imported().get(0);
        assertEquals(ArchivedGame.GameMode.BROWSER, g.mode());
        assertEquals("Hardin22 (1500)", g.white());
        assertEquals("1/2-1/2", g.result());
        assertEquals("Patta d'accordo", g.termination());
        assertEquals("D06 Queens Gambit Declined", g.opening());
        assertEquals("10+0", g.timeControl());
        assertEquals(LocalDateTime.of(2026, 9, 30, 21, 10, 5), g.playedAt());
        assertEquals("https://api.chess.com/pub/player/hardin22/games/2026/09/pgn", asked.get(1),
                "the newest month first; enough games after it");
        assertEquals(2, asked.size());
    }

    @Test
    void problemsAreReportedNotThrown() {
        OnlineImport.Result unknown = importer().importRecent(OnlineImport.Source.LICHESS, "nobody", 10).join();
        assertFalse(unknown.ok());
        assertEquals("Utente «nobody» non trovato su Lichess", unknown.message());
        answers.put("https://lichess.org/api/games/user/down", response(503, ""));
        assertEquals("Impossibile scaricare le partite da Lichess: controlla la connessione",
                importer().importRecent(OnlineImport.Source.LICHESS, "down", 10).join().message());
        assertEquals("Nome utente non valido",
                importer().importRecent(OnlineImport.Source.CHESS_COM, "bad name!", 10).join().message());
        answers.put("https://lichess.org/api/games/user/empty", response(200, ""));
        assertEquals("Nessuna partita trovata per «empty» su Lichess",
                importer().importRecent(OnlineImport.Source.LICHESS, "empty", 10).join().message());
    }

    @Test
    void smallConversions() {
        assertEquals("½+0".replace("½", "30s"), OnlineImport.timeControl("30+0"));
        assertEquals("", OnlineImport.timeControl("1/259200"));
        assertEquals("Triplice ripetizione", OnlineImport.termination("Game drawn by repetition", "1/2-1/2", null));
        assertEquals("Abbandono", OnlineImport.termination("opponent won by resignation", "0-1", null));
        assertEquals("Patta: tempo scaduto",
                OnlineImport.termination("Game drawn by timeout vs insufficient material", "1/2-1/2", null));
    }

    static HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("https://example.invalid");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
