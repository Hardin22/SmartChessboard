package io.github.hardin22.javachess.Stats;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.github.hardin22.javachess.Analysis.AnalysisTree;
import io.github.hardin22.javachess.Analysis.MoveText;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A game or a position opened on lichess.org from the phone: the analysis board link (nothing is uploaded: the
 * moves travel in the address) and its QR code, so the player can go on studying away from the board.
 * <ul>
 *   <li>a game from the starting position: {@code https://lichess.org/analysis/pgn/1.e4_e5_2.Nf3...} (the whole
 *       game, movable on lichess);</li>
 *   <li>a game from another position, or a single position: {@code https://lichess.org/analysis/standard/<FEN>}
 *       (the position reached).</li>
 * </ul>
 */
public final class GameLinks {

    public static final String LICHESS = "https://lichess.org/analysis/";

    private GameLinks() {
    }

    /** Link to the game: all the moves when it starts from the usual position, else the position reached. */
    public static String lichessGame(String initialFen, List<String> uciMoves) {
        boolean standard = initialFen == null || initialFen.isBlank()
                || sameStart(initialFen, AnalysisTree.START_FEN);
        String start = standard ? AnalysisTree.START_FEN : initialFen;
        Board b = new Board();
        b.loadFromFen(start);
        List<String> sans = MoveText.san(start, uciMoves, uciMoves.size());
        if (!standard || sans.size() != uciMoves.size()) {
            for (String uci : uciMoves) {
                Move m = MoveText.legal(b, uci);
                if (m == null) {
                    break;
                }
                b.doMove(m);
            }
            return lichessPosition(b.getFen());
        }
        if (sans.isEmpty()) {
            return LICHESS;
        }
        StringBuilder url = new StringBuilder(LICHESS).append("pgn/");
        for (int i = 0; i < sans.size(); i++) {
            if (i > 0) {
                url.append('_');
            }
            if (i % 2 == 0) {
                url.append(i / 2 + 1).append('.');
            }
            // check and mate signs are optional in PGN; "=" of promotions is escaped for the address
            url.append(sans.get(i).replace("+", "").replace("#", "").replace("=", "%3D"));
        }
        return url.toString();
    }

    /** Link to one position (the analysis board opens on it). */
    public static String lichessPosition(String fen) {
        return LICHESS + "standard/" + fen.trim().replaceAll("\\s+", "_");
    }

    /**
     * QR code of {@code text}: {@code modules[y][x]} true = dark. Error correction M, so a phone reads it from a
     * screen at an angle. Null when the text is too long for a QR code.
     */
    public static boolean[][] qr(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 0));
            boolean[][] out = new boolean[m.getHeight()][m.getWidth()];
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    out[y][x] = m.get(x, y);
                }
            }
            return out;
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The QR code as an image, {@code moduleSize} pixels per module and the 4-module white border the standard
     * asks for (use the image at its own size, or scale it without smoothing). Null when the text does not fit.
     * Call on the JavaFX thread or before the image is shown.
     */
    public static WritableImage qrImage(String text, int moduleSize) {
        boolean[][] modules = qr(text);
        if (modules == null) {
            return null;
        }
        int border = 4;
        int size = (modules.length + 2 * border) * moduleSize;
        WritableImage image = new WritableImage(size, size);
        PixelWriter pw = image.getPixelWriter();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int my = y / moduleSize - border;
                int mx = x / moduleSize - border;
                boolean dark = my >= 0 && mx >= 0 && my < modules.length && mx < modules.length && modules[my][mx];
                pw.setColor(x, y, dark ? Color.BLACK : Color.WHITE);
            }
        }
        return image;
    }

    private static boolean sameStart(String a, String b) {
        String[] x = a.trim().split("\\s+");
        String[] y = b.trim().split("\\s+");
        List<String> xs = new ArrayList<>(List.of(x).subList(0, Math.min(4, x.length)));
        List<String> ys = new ArrayList<>(List.of(y).subList(0, Math.min(4, y.length)));
        return xs.equals(ys);
    }
}
