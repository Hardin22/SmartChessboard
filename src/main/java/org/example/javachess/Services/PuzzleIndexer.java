package org.example.javachess.Services;

import java.io.*;
import java.util.*;

public class PuzzleIndexer {

    public static Map<String, List<Long>> buildAndSaveIndex(File csvFile, File indexFile) {
        System.out.println("[PuzzleIndexer] Starting fast index generation for " + csvFile.getName() + "...");
        long startTime = System.currentTimeMillis();

        Map<String, List<Long>> index = new HashMap<>();

        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(csvFile))) {
            long globalOffset = 0;
            long lineStartOffset = 0;

            // Reusable buffer
            byte[] buffer = new byte[8192 * 4];
            int bytesRead;

            ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);
            boolean isFirstLine = true;

            while ((bytesRead = bis.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    byte b = buffer[i];
                    if (b == '\n') {
                        // End of line
                        long nextLineOffset = globalOffset + i + 1;

                        if (isFirstLine) {
                            isFirstLine = false;
                        } else {
                            // Process valid line
                            // Convert bytes to string (ISO-8859-1 is safe for Lichess CSV structure and
                            // fastest)
                            // But we only need parsing, so string conversion is needed.
                            String line = lineBuffer.toString("UTF-8"); // UTF-8 to be safe for tags
                            processLine(line, lineStartOffset, index);
                        }

                        lineBuffer.reset();
                        lineStartOffset = nextLineOffset;
                    } else {
                        lineBuffer.write(b);
                    }
                }
                globalOffset += bytesRead;
            }

            // Last line? Usually CSV ends with newline.

            // Serialize
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(indexFile))) {
                oos.writeObject(index);
            }

            long duration = System.currentTimeMillis() - startTime;
            System.out.println("[PuzzleIndexer] SUCCESS! Index generated in " + duration + "ms.");
            System.out.println("[PuzzleIndexer] Index saved to ABSOLUTE PATH: " + indexFile.getAbsolutePath());
            System.out.println("[PuzzleIndexer] Total themes indexed: " + index.size());
            return index;

        } catch (IOException e) {
            System.err.println("[PuzzleIndexer] FATAL ERROR writing index: " + e.getMessage());
            e.printStackTrace();
            return new HashMap<>(); // Fail safe
        }
    }

    private static void processLine(String line, long offset, Map<String, List<Long>> index) {
        if (line.trim().isEmpty())
            return;
        try {
            // Quick parsing: find the 7th comma (Themes are at index 7)
            int commaCount = 0;
            int lastCommaIndex = -1;
            // PuzzleId,FEN,Moves,Rating,Rd,Pop,Nb,Themes,Url

            // Manual scan for commas is faster than split()
            int themesStart = -1;
            int themesEnd = -1;

            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == ',') {
                    commaCount++;
                    if (commaCount == 7)
                        themesStart = i + 1;
                    if (commaCount == 8) {
                        themesEnd = i;
                        break;
                    }
                }
            }

            if (themesStart != -1 && themesEnd != -1) {
                String themeString = line.substring(themesStart, themesEnd);
                // Space separated
                int start = 0;
                for (int i = 0; i <= themeString.length(); i++) {
                    if (i == themeString.length() || themeString.charAt(i) == ' ') {
                        if (i > start) {
                            String theme = themeString.substring(start, i);
                            index.computeIfAbsent(theme, k -> new ArrayList<>()).add(offset);
                        }
                        start = i + 1;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore malformed
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, List<Long>> loadIndex(File indexFile) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(indexFile))) {
            return (Map<String, List<Long>>) ois.readObject();
        } catch (Exception e) {
            System.err.println("[PuzzleIndexer] Failed to load index: " + e.getMessage());
            return null;
        }
    }
}
