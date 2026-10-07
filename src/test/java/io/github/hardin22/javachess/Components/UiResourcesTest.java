package io.github.hardin22.javachess.Components;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.feather.Feather;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static checks on the UI resources: i18n keys, icon names, fonts and logo files referenced by the views. */
class UiResourcesTest {

    private static List<Path> fxmlFiles() throws IOException, URISyntaxException {
        Path dir = Path.of(UiResourcesTest.class.getResource("/UI/MainLayout.fxml").toURI()).getParent();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".fxml")).toList();
        }
    }

    @Test
    void everyFxmlResourceKeyExistsInTheBundle() throws Exception {
        Pattern key = Pattern.compile("=\"%([\\w.]+)\"");
        List<String> missing = new ArrayList<>();
        for (Path fxml : fxmlFiles()) {
            Matcher m = key.matcher(Files.readString(fxml, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!I18n.bundle().containsKey(m.group(1))) {
                    missing.add(fxml.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    /**
     * FXMLLoader reads a capitalised package segment ("Components") as a class name, so a single-class import such as
     * {@code io.github.hardin22.javachess.Components.ScreenHeader} fails at run time: app classes need wildcards.
     */
    @Test
    void fxmlImportsOfAppClassesUseWildcards() throws Exception {
        Pattern imp = Pattern.compile("<\\?import (io\\.github\\.hardin22\\.javachess\\.[\\w.]+)\\?>");
        List<String> bad = new ArrayList<>();
        for (Path fxml : fxmlFiles()) {
            Matcher m = imp.matcher(Files.readString(fxml, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!m.group(1).endsWith(".*")) {
                    bad.add(fxml.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void everyIconLiteralIsAFeatherIcon() throws Exception {
        Set<String> known = Arrays.stream(Feather.values()).map(Feather::getDescription).collect(Collectors.toSet());
        Pattern literal = Pattern.compile("\"(fth-[a-z0-9-]+)\"");
        List<String> unknown = new ArrayList<>();
        List<Path> sources = new ArrayList<>(fxmlFiles());
        try (Stream<Path> java = Files.walk(Path.of("src/main/java"))) {
            java.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        for (Path file : sources) {
            Matcher m = literal.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!known.contains(m.group(1))) {
                    unknown.add(file.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertEquals(List.of(), unknown);
    }

    @Test
    void fontsLicenceAndIconsArePackaged() {
        for (String font : List.of("Geist-Regular", "Geist-Medium", "Geist-SemiBold", "Geist-Bold",
                "GeistMono-Regular", "GeistMono-Medium", "GeistMono-SemiBold")) {
            assertNotNull(getClass().getResource("/Font/Geist/" + font + ".ttf"), font);
        }
        assertNotNull(getClass().getResource("/Font/Geist/OFL.txt"));
        for (int size : new int[] { 16, 32, 64, 128, 256 }) {
            assertNotNull(getClass().getResource("/images/logo/icon-" + size + ".png"), "icon " + size);
        }
        assertNotNull(getClass().getResource("/Styles/Style.css"));
    }

    @Test
    void pieceSetsAndImageBoardsExist() {
        for (String set : BoardThemes.PIECE_SETS) {
            for (String piece : List.of("wk", "wq", "wr", "wb", "wn", "wp", "bk", "bq", "br", "bb", "bn", "bp")) {
                assertNotNull(getClass().getResource("/images/Pieces/" + set + "/" + piece + ".png"), set + "/" + piece);
            }
        }
        for (String board : BoardThemes.IMAGE_BOARDS) {
            assertNotNull(getClass().getResource("/images/Scacchiere/" + board), board);
        }
        assertNotNull(BoardThemes.colors(BoardThemes.DEFAULT));
    }

    @Test
    void i18nReplacesArgumentsAndKeepsApostrophes() {
        assertEquals("Mossa 3 di 40", I18n.t("review.ply", 3, 40));
        assertTrue(I18n.t("settings.subtitle").contains("dell'app"));
        assertEquals("missing.key", I18n.t("missing.key"));
    }
}
