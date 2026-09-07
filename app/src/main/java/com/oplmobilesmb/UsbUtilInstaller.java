package com.oplmobilesmb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes ISO images in the USBExtreme/USBUtil format consumed by OPL. */
public final class UsbUtilInstaller {
    public static final long PART_SIZE = 1_073_741_824L; // 1 GiB, same as iso2opl.
    public static final int MEDIA_CD = 0x12;
    public static final int MEDIA_DVD = 0x14;

    private static final int RECORD_SIZE = 64;
    private static final int COPY_BUFFER = 1024 * 1024;
    private static final Pattern GAME_ID = Pattern.compile("(?i)([A-Z]{4})[_-]?(\\d{3})[._-]?(\\d{2})");

    private UsbUtilInstaller() {}

    public interface ProgressCallback {
        void onProgress(long copied, long total, int currentPart, int expectedParts);
    }

    public static InstallResult install(InputStream iso, File root, String title, String gameId,
                                        int media, long totalBytes, ProgressCallback callback)
            throws IOException {
        if (iso == null) throw new IOException("Não foi possível abrir a ISO");
        if (root == null || !root.isDirectory() || !root.canWrite())
            throw new IOException("A raiz selecionada não permite gravação");

        title = validateTitle(title);
        gameId = normalizeGameId(gameId);
        if (media != MEDIA_CD && media != MEDIA_DVD)
            throw new IllegalArgumentException("Tipo de mídia inválido");

        File cfg = new File(root, "ul.cfg");
        if (cfg.exists() && (!cfg.isFile() || cfg.length() % RECORD_SIZE != 0))
            throw new IOException("ul.cfg existente está inválido/truncado; não vou alterá-lo");

        for (UsbUtilGames.Game game : UsbUtilGames.read(root)) {
            if (game.title.equalsIgnoreCase(title))
                throw new IOException("Já existe uma entrada USBUtil com esse nome");
            if (game.gameId.equalsIgnoreCase(gameId))
                throw new IOException("Já existe uma entrada USBUtil para " + gameId);
        }

        int crc = crc32(title);
        String prefix = String.format(Locale.US, "ul.%08X.%s.", crc, gameId);
        File[] existing = root.listFiles();
        if (existing != null) {
            for (File file : existing) {
                if (file.getName().regionMatches(true, 0, prefix, 0, prefix.length()))
                    throw new IOException("Já existem partes com o mesmo nome: " + file.getName());
            }
        }

        if (totalBytes > 0 && root.getUsableSpace() > 0 && root.getUsableSpace() < totalBytes + 4096)
            throw new IOException("Espaço livre insuficiente no armazenamento selecionado");

        int expectedParts = totalBytes > 0 ? partCount(totalBytes) : -1;
        if (expectedParts > 255)
            throw new IOException("A imagem exigiria mais de 255 partes USBUtil");

        List<File> created = new ArrayList<>();
        List<File> temporary = new ArrayList<>();
        long copied = 0;
        int partIndex = 0;
        byte[] buffer = new byte[COPY_BUFFER];

        try {
            boolean eof = false;
            while (!eof) {
                if (partIndex >= 255)
                    throw new IOException("A imagem excedeu o limite de 255 partes USBUtil");

                String finalName = prefix + String.format(Locale.US, "%02d", partIndex);
                File finalFile = new File(root, finalName);
                File tempFile = new File(root, "." + finalName + ".part");
                if (finalFile.exists() || tempFile.exists())
                    throw new IOException("Arquivo de destino já existe: " + finalName);
                temporary.add(tempFile);

                long inPart = 0;
                try (FileOutputStream out = new FileOutputStream(tempFile)) {
                    while (inPart < PART_SIZE) {
                        int want = (int) Math.min(buffer.length, PART_SIZE - inPart);
                        int n = iso.read(buffer, 0, want);
                        if (n < 0) {
                            eof = true;
                            break;
                        }
                        if (n == 0) continue;
                        out.write(buffer, 0, n);
                        inPart += n;
                        copied += n;
                        if (callback != null)
                            callback.onProgress(copied, totalBytes, partIndex + 1, expectedParts);
                    }
                    out.getFD().sync();
                }

                if (inPart == 0) {
                    tempFile.delete();
                    temporary.remove(tempFile);
                    break;
                }

                if (!tempFile.renameTo(finalFile))
                    throw new IOException("Não consegui finalizar " + finalName);
                temporary.remove(tempFile);
                created.add(finalFile);
                partIndex++;
            }

            if (copied == 0 || partIndex == 0)
                throw new IOException("A ISO está vazia");

            appendCfg(cfg, title, gameId, partIndex, media);
            return new InstallResult(title, gameId, crc, partIndex, copied, media);
        } catch (Exception e) {
            for (File f : temporary) f.delete();
            for (File f : created) f.delete();
            if (e instanceof IOException) throw (IOException) e;
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IOException(e);
        }
    }

    private static void appendCfg(File cfg, String title, String gameId, int parts, int media)
            throws IOException {
        byte[] record = new byte[RECORD_SIZE];
        byte[] titleBytes = title.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(titleBytes, 0, record, 0, titleBytes.length);

        byte[] image = ("ul." + gameId).getBytes(StandardCharsets.US_ASCII);
        if (image.length > 14) throw new IOException("Game ID grande demais para ul.cfg");
        System.arraycopy(image, 0, record, 32, image.length);
        record[47] = (byte) parts;
        record[48] = (byte) media;
        record[53] = 0x08; // cfg.pad[4], matching OPL iso2opl / USBAdvance convention.

        File parent = cfg.getParentFile();
        File temp = new File(parent, ".ul.cfg.oplmobile.tmp");
        File backup = new File(parent, ".ul.cfg.oplmobile.bak");
        if (temp.exists() && !temp.delete()) throw new IOException("Não consegui limpar o ul.cfg temporário");
        if (backup.exists() && !backup.delete()) throw new IOException("Não consegui limpar o backup antigo de ul.cfg");

        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (cfg.isFile()) {
                    try (FileInputStream in = new FileInputStream(cfg)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) >= 0) {
                            if (n > 0) out.write(buffer, 0, n);
                        }
                    }
                }
                out.write(record);
                out.getFD().sync();
            }

            boolean hadCfg = cfg.exists();
            if (hadCfg && !cfg.renameTo(backup))
                throw new IOException("Não consegui criar backup de ul.cfg");

            if (!temp.renameTo(cfg)) {
                if (hadCfg) backup.renameTo(cfg);
                throw new IOException("Não consegui finalizar ul.cfg");
            }
            if (backup.exists()) backup.delete();
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    public static String validateTitle(String value) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty()) throw new IllegalArgumentException("Digite o nome do jogo");
        byte[] encoded = title.getBytes(StandardCharsets.ISO_8859_1);
        if (encoded.length > 31)
            throw new IllegalArgumentException("O nome USBUtil pode ter no máximo 31 caracteres/bytes");
        return title;
    }

    public static String normalizeGameId(String value) {
        if (value == null) throw new IllegalArgumentException("Digite o Game ID");
        Matcher m = GAME_ID.matcher(value.trim());
        if (!m.matches())
            throw new IllegalArgumentException("Game ID inválido. Exemplo: SLUS_202.16");
        return (m.group(1) + "_" + m.group(2) + "." + m.group(3)).toUpperCase(Locale.US);
    }

    public static String extractGameId(String text) {
        if (text == null) return "";
        Matcher m = GAME_ID.matcher(text);
        if (!m.find()) return "";
        return (m.group(1) + "_" + m.group(2) + "." + m.group(3)).toUpperCase(Locale.US);
    }

    public static int partCount(long bytes) {
        if (bytes <= 0) return 0;
        long count = (bytes + PART_SIZE - 1) / PART_SIZE;
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /** OPL iso2opl's non-reflected CRC32 routine (poly 0x04C11DB7, initial value 0). */
    public static int crc32(String value) {
        int[] table = new int[256];
        int crc = 0;
        for (int tableIndex = 0; tableIndex < 256; tableIndex++) {
            crc = tableIndex << 24;
            for (int count = 8; count > 0; count--) {
                if (crc < 0) crc <<= 1;
                else crc = (crc << 1) ^ 0x04C11DB7;
            }
            table[255 - tableIndex] = crc;
        }

        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        crc = 0; // The original C routine ends table generation with crc == 0.
        for (int i = 0; i <= bytes.length; i++) {
            int b = i == bytes.length ? 0 : bytes[i] & 0xff; // Include terminating NUL, like iso2opl.
            crc = table[b ^ ((crc >>> 24) & 0xff)] ^ (crc << 8);
        }
        return crc;
    }

    public static final class InstallResult {
        public final String title;
        public final String gameId;
        public final int crc;
        public final int parts;
        public final long bytes;
        public final int media;

        InstallResult(String title, String gameId, int crc, int parts, long bytes, int media) {
            this.title = title;
            this.gameId = gameId;
            this.crc = crc;
            this.parts = parts;
            this.bytes = bytes;
            this.media = media;
        }

        public String firstPartName() {
            return String.format(Locale.US, "ul.%08X.%s.00", crc, gameId);
        }
    }
}
