package com.oplmobilesmb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Safe removal of one USBUtil/USBExtreme game: chunks + matching ul.cfg record. */
public final class UsbUtilManager {
    private static final int RECORD_SIZE = 64;

    private UsbUtilManager() {}

    public static RemoveResult uninstall(File root, UsbUtilGames.Game game) throws IOException {
        if (root == null || !root.isDirectory() || !root.canWrite())
            throw new IOException("A raiz selecionada não permite gravação");
        if (game == null || game.gameId == null || game.gameId.isBlank())
            throw new IOException("Entrada USBUtil inválida");

        File cfg = new File(root, "ul.cfg");
        if (!cfg.isFile() || cfg.length() % RECORD_SIZE != 0)
            throw new IOException("ul.cfg ausente, inválido ou truncado");

        byte[] all = readAll(cfg);
        int records = all.length / RECORD_SIZE;
        int removeIndex = -1;
        for (int i = 0; i < records; i++) {
            int off = i * RECORD_SIZE;
            String title = cString(all, off, 32);
            String image = cString(all, off + 32, 15);
            String id = image.regionMatches(true, 0, "ul.", 0, 3) ? image.substring(3) : image;
            if (id.equalsIgnoreCase(game.gameId) &&
                    (title.equalsIgnoreCase(game.title) || removeIndex < 0)) {
                removeIndex = i;
                if (title.equalsIgnoreCase(game.title)) break;
            }
        }
        if (removeIndex < 0)
            throw new IOException("Não encontrei " + game.gameId + " no ul.cfg");

        List<File> parts = findPartFiles(root, game.gameId);
        long bytes = 0;
        for (File part : parts) bytes += part.length();

        List<RenamePair> staged = new ArrayList<>();
        File cfgTemp = new File(root, ".ul.cfg.oplmobile.remove.tmp");
        File cfgBackup = new File(root, ".ul.cfg.oplmobile.remove.bak");
        if (cfgTemp.exists() && !cfgTemp.delete()) throw new IOException("Não consegui limpar o ul.cfg temporário");
        if (cfgBackup.exists() && !cfgBackup.delete()) throw new IOException("Não consegui limpar o backup antigo de ul.cfg");

        boolean cfgMoved = false;
        boolean cfgReplaced = false;
        try {
            // Stage chunks by renaming them first. If anything fails before the cfg swap,
            // all chunk names can be restored without data loss.
            for (File part : parts) {
                File stagedFile = new File(root, ".oplmobile-remove-" + part.getName());
                if (stagedFile.exists())
                    throw new IOException("Arquivo temporário já existe: " + stagedFile.getName());
                if (!part.renameTo(stagedFile))
                    throw new IOException("Não consegui preparar remoção de " + part.getName());
                staged.add(new RenamePair(part, stagedFile));
            }

            try (FileOutputStream out = new FileOutputStream(cfgTemp)) {
                for (int i = 0; i < records; i++) {
                    if (i == removeIndex) continue;
                    out.write(all, i * RECORD_SIZE, RECORD_SIZE);
                }
                out.getFD().sync();
            }

            if (!cfg.renameTo(cfgBackup))
                throw new IOException("Não consegui criar backup de ul.cfg");
            cfgMoved = true;

            if (!cfgTemp.renameTo(cfg))
                throw new IOException("Não consegui finalizar o novo ul.cfg");
            cfgReplaced = true;

            if (cfgBackup.exists() && !cfgBackup.delete()) {
                // The new cfg is already valid; an old hidden backup is harmless and can be cleaned later.
                cfgBackup.deleteOnExit();
            }

            int deleted = 0;
            for (RenamePair pair : staged) {
                if (pair.staged.delete()) deleted++;
            }
            return new RemoveResult(game.title, game.gameId, parts.size(), deleted, bytes);
        } catch (Exception e) {
            if (!cfgReplaced) {
                if (cfgMoved && cfgBackup.exists()) {
                    if (cfg.exists()) cfg.delete();
                    cfgBackup.renameTo(cfg);
                }
                for (int i = staged.size() - 1; i >= 0; i--) {
                    RenamePair pair = staged.get(i);
                    if (pair.staged.exists() && !pair.original.exists())
                        pair.staged.renameTo(pair.original);
                }
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        } finally {
            if (cfgTemp.exists()) cfgTemp.delete();
        }
    }

    public static List<File> findPartFiles(File root, String gameId) {
        List<File> result = new ArrayList<>();
        if (root == null || gameId == null || gameId.isBlank()) return result;
        File[] files = root.listFiles();
        if (files == null) return result;
        Pattern p = Pattern.compile("(?i)^ul\\.[0-9a-f]{8}\\." + Pattern.quote(gameId) + "\\.\\d{2,3}$");
        for (File file : files) {
            if (file.isFile() && p.matcher(file.getName()).matches()) result.add(file);
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    private static byte[] readAll(File file) throws IOException {
        if (file.length() > Integer.MAX_VALUE) throw new IOException("ul.cfg grande demais");
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) throw new IOException("Fim inesperado ao ler ul.cfg");
                off += n;
            }
        }
        return data;
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        int limit = Math.min(data.length, offset + length);
        while (end < limit && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1).trim();
    }

    private static final class RenamePair {
        final File original;
        final File staged;
        RenamePair(File original, File staged) {
            this.original = original;
            this.staged = staged;
        }
    }

    public static final class RemoveResult {
        public final String title;
        public final String gameId;
        public final int partsFound;
        public final int partsDeleted;
        public final long bytes;

        RemoveResult(String title, String gameId, int partsFound, int partsDeleted, long bytes) {
            this.title = title;
            this.gameId = gameId;
            this.partsFound = partsFound;
            this.partsDeleted = partsDeleted;
            this.bytes = bytes;
        }

        public boolean hasLeftovers() {
            return partsDeleted != partsFound;
        }
    }
}
