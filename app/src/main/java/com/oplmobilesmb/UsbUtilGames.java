package com.oplmobilesmb;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal reader for the 64-byte USBExtreme/USBUtil ul.cfg record format used by OPL. */
public final class UsbUtilGames {
    private static final int RECORD_SIZE = 64;

    private UsbUtilGames() {}

    public static List<Game> read(File root) {
        List<Game> result = new ArrayList<>();
        File cfg = new File(root, "ul.cfg");
        if (!cfg.isFile()) return result;

        try (FileInputStream in = new FileInputStream(cfg)) {
            byte[] record = new byte[RECORD_SIZE];
            while (true) {
                int off = 0;
                while (off < RECORD_SIZE) {
                    int n = in.read(record, off, RECORD_SIZE - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off == 0) break;
                if (off != RECORD_SIZE) break; // Ignore a truncated trailing record.

                String title = cString(record, 0, 32);
                String image = cString(record, 32, 15);
                int parts = record[47] & 0xff;
                int media = record[48] & 0xff;
                if (title.isBlank() && image.isBlank()) continue;

                String gameId = image.startsWith("ul.") ? image.substring(3) : image;
                long bytes = calculatePartsSize(root, gameId, parts);
                result.add(new Game(title.isBlank() ? gameId : title, gameId, parts, media, bytes));
            }
        } catch (Exception ignored) {
            // A malformed ul.cfg must not prevent ISO games from being listed/served.
        }
        return result;
    }

    private static long calculatePartsSize(File root, String gameId, int expectedParts) {
        File[] files = root.listFiles();
        if (files == null || gameId == null || gameId.isBlank()) return -1;
        long total = 0;
        int found = 0;
        String marker = "." + gameId.toLowerCase() + ".";
        for (File file : files) {
            String n = file.getName().toLowerCase();
            if (file.isFile() && n.startsWith("ul.") && n.contains(marker)) {
                total += file.length();
                found++;
            }
        }
        return found == 0 ? -1 : total;
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        int limit = Math.min(data.length, offset + length);
        while (end < limit && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1).trim();
    }

    public static final class Game {
        public final String title;
        public final String gameId;
        public final int parts;
        public final int media;
        public final long bytes;

        Game(String title, String gameId, int parts, int media, long bytes) {
            this.title = title;
            this.gameId = gameId;
            this.parts = parts;
            this.media = media;
            this.bytes = bytes;
        }

        public String mediaName() {
            if (media == 0x12) return "CD";
            if (media == 0x14) return "DVD";
            return "mídia 0x" + Integer.toHexString(media);
        }
    }
}
