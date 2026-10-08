package io.github.hardin22.javachess.Stats;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import io.github.hardin22.javachess.Analysis.AnalysisTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameLinksTest {

    @Test
    void aGameFromTheStartTravelsInTheAddress() {
        assertEquals("https://lichess.org/analysis/pgn/1.e4_e5_2.Nf3_Nc6_3.Bc4",
                GameLinks.lichessGame(AnalysisTree.START_FEN, List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4")));
        assertEquals("https://lichess.org/analysis/pgn/1.e4_e5_2.Qh5_Nc6_3.Bc4_Nf6_4.Qxf7",
                GameLinks.lichessGame(null, List.of("e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7")),
                "mate and check signs are left out");
        assertEquals("https://lichess.org/analysis/",
                GameLinks.lichessGame(AnalysisTree.START_FEN, List.of()));
    }

    @Test
    void promotionsAreEscapedAndOtherStartsGiveThePosition() {
        String fen = "8/4P3/8/8/8/8/k7/4K3 w - - 0 1";
        assertEquals("https://lichess.org/analysis/standard/4Q3/8/8/8/8/8/k7/4K3_b_-_-_0_1",
                GameLinks.lichessGame(fen, List.of("e7e8q")));
        assertEquals("https://lichess.org/analysis/standard/8/4P3/8/8/8/8/k7/4K3_w_-_-_0_1",
                GameLinks.lichessPosition(fen));
    }

    @Test
    void theQrCodeReadsBackAsTheLink() throws Exception {
        List<String> moves = new ArrayList<>();
        // a long game: 40 moves of knights going back and forth
        for (int i = 0; i < 20; i++) {
            Collections.addAll(moves, "g1f3", "g8f6", "f3g1", "f6g8");
        }
        String url = GameLinks.lichessGame(AnalysisTree.START_FEN, moves);
        boolean[][] modules = GameLinks.qr(url);
        int n = modules.length;
        int scale = 4;
        int size = (n + 8) * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int my = y / scale - 4;
                int mx = x / scale - 4;
                boolean dark = my >= 0 && mx >= 0 && my < n && mx < n && modules[my][mx];
                pixels[y * size + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size, size, pixels)));
        assertEquals(url, new QRCodeReader().decode(bitmap).getText());
    }

    @Test
    void textTooLongForAQrCodeGivesNull() {
        assertNull(GameLinks.qr("x".repeat(5000)));
    }
}
