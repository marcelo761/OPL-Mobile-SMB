package com.oplmobilesmb;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StoragePaths {
    private static final String PREFS = "storage";

    // Legacy 0.4.0 key. Kept for migration only.
    private static final String KEY_ROOT = "share_root";

    private static final String KEY_KIND = "share_kind";
    private static final String KEY_VOLUME_UUID = "share_volume_uuid";
    private static final String KEY_VOLUME_PRIMARY = "share_volume_primary";
    private static final String KEY_VOLUME_PATH = "share_volume_path";

    private static final String KIND_PRIVATE = "private";
    private static final String KIND_VOLUME = "volume";

    private StoragePaths() {}

    /** Default private location used when the user has not selected a storage volume. */
    public static File defaultRoot(Context context) {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : context.getFilesDir();
        return new File(base, "PS2SMB");
    }

    /**
     * Root exported as PS2SMB. For removable/shared storage we persist the volume identity,
     * not only /storage/XXXX-XXXX, because Android may remount a volume at a new path.
     */
    public static File root(Context context) {
        SharedPreferences prefs = prefs(context);
        String kind = prefs.getString(KEY_KIND, null);

        if (KIND_VOLUME.equals(kind)) {
            String uuid = prefs.getString(KEY_VOLUME_UUID, null);
            boolean primary = prefs.getBoolean(KEY_VOLUME_PRIMARY, false);
            File current = resolveVolumeRoot(context, uuid, primary);
            if (current != null) return current;

            String fallback = prefs.getString(KEY_VOLUME_PATH, null);
            if (fallback != null && !fallback.isBlank()) return new File(fallback);
            return defaultRoot(context);
        }

        if (KIND_PRIVATE.equals(kind)) return defaultRoot(context);

        // Upgrade path from 0.4.0: try to match the old absolute path to a currently mounted volume.
        String legacy = prefs.getString(KEY_ROOT, null);
        if (legacy != null && !legacy.isBlank()) {
            File old = new File(legacy);
            StorageRoot match = findVolumeByPath(context, old);
            if (match != null) {
                setRoot(context, match);
                return match.root;
            }
            return old;
        }

        return defaultRoot(context);
    }

    public static void setRoot(Context context, StorageRoot selected) {
        SharedPreferences.Editor edit = prefs(context).edit();
        edit.remove(KEY_ROOT);

        if (selected == null || selected.appPrivate) {
            edit.putString(KEY_KIND, KIND_PRIVATE)
                    .remove(KEY_VOLUME_UUID)
                    .remove(KEY_VOLUME_PRIMARY)
                    .remove(KEY_VOLUME_PATH)
                    .apply();
            return;
        }

        edit.putString(KEY_KIND, KIND_VOLUME)
                .putString(KEY_VOLUME_UUID, selected.uuid)
                .putBoolean(KEY_VOLUME_PRIMARY, selected.primary)
                .putString(KEY_VOLUME_PATH, canonical(selected.root))
                .apply();
    }

    public static boolean isDefaultRoot(Context context) {
        return canonical(root(context)).equals(canonical(defaultRoot(context)));
    }

    public static File dvd(Context context) {
        return new File(root(context), "DVD");
    }

    public static File cd(Context context) {
        return new File(root(context), "CD");
    }

    public static File usbUtilConfig(Context context) {
        return new File(root(context), "ul.cfg");
    }

    /** Create OPL folders only after the selected root is actually available. */
    public static void ensure(Context context) {
        File root = root(context);
        if (isDefaultRoot(context) && !root.exists()) root.mkdirs();
        if (!root.isDirectory() || !root.canWrite()) return;
        dvd(context).mkdirs();
        cd(context).mkdirs();
    }

    /** List only volumes that Android currently reports as mounted read/write. */
    public static List<StorageRoot> availableRoots(Context context) {
        List<StorageRoot> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        File def = defaultRoot(context);
        add(result, seen, new StorageRoot(
                "Pasta privada do app", def, false, true, null, false, Environment.MEDIA_MOUNTED));

        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            for (StorageVolume volume : sm.getStorageVolumes()) {
                String state;
                try { state = volume.getState(); }
                catch (Exception ignored) { state = Environment.MEDIA_UNKNOWN; }

                // The manager installs/removes games, so read-only/unmounted volumes are not valid targets.
                if (!Environment.MEDIA_MOUNTED.equals(state)) continue;

                File volumeRoot = storageVolumeRoot(context, volume);
                if (volumeRoot == null) continue;

                String description;
                try {
                    description = volume.getDescription(context);
                } catch (Exception ignored) {
                    description = volume.isRemovable() ? "Armazenamento removível" : "Armazenamento interno";
                }

                String prefix = volume.isRemovable() ? "USB/SD — " : "Interno — ";
                add(result, seen, new StorageRoot(
                        prefix + description,
                        volumeRoot,
                        volume.isRemovable(),
                        false,
                        volume.getUuid(),
                        volume.isPrimary(),
                        state));
            }
        } else {
            File[] dirs = context.getExternalFilesDirs(null);
            if (dirs != null) {
                for (File dir : dirs) {
                    File volumeRoot = volumeRootFromAppDir(dir);
                    if (volumeRoot != null) {
                        add(result, seen, new StorageRoot(
                                "Armazenamento — " + volumeRoot.getName(),
                                volumeRoot,
                                true,
                                false,
                                volumeRoot.getName(),
                                false,
                                Environment.MEDIA_MOUNTED));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Validate the selected root. testWrite performs a real create/write/delete probe instead of
     * trusting File.canWrite(), which is unreliable on some Android/vendor storage stacks.
     */
    public static AccessResult validate(Context context, boolean testWrite) {
        File root = root(context);
        String state = selectedVolumeState(context);

        if (!isDefaultRoot(context) && state != null && !Environment.MEDIA_MOUNTED.equals(state)) {
            return AccessResult.fail(root, "volume não está montado (estado: " + state + ")");
        }

        if (!root.exists())
            return AccessResult.fail(root, "raiz do armazenamento não existe: " + root.getAbsolutePath());
        if (!root.isDirectory())
            return AccessResult.fail(root, "raiz do armazenamento não é uma pasta: " + root.getAbsolutePath());
        if (!root.canRead())
            return AccessResult.fail(root, "sem acesso de leitura ao armazenamento");

        long total = root.getTotalSpace();
        long free = root.getUsableSpace();
        if (total <= 0)
            return AccessResult.fail(root, "Android reportou 0 B para este volume; remova e reconecte o pendrive");

        if (testWrite) {
            if (!root.canWrite())
                return AccessResult.fail(root, "sem acesso de escrita ao armazenamento");

            File probe = new File(root, ".oplmobile-write-test-" + android.os.Process.myPid() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(probe, false)) {
                out.write(0x4F); // 'O'
                out.flush();
            } catch (Exception e) {
                return AccessResult.fail(root, "teste de escrita falhou: " + shortMessage(e));
            } finally {
                try { if (probe.exists()) probe.delete(); } catch (Exception ignored) {}
            }
        }

        return AccessResult.ok(root, total, free);
    }

    public static String selectedVolumeState(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!KIND_VOLUME.equals(prefs.getString(KEY_KIND, null))) return null;

        String uuid = prefs.getString(KEY_VOLUME_UUID, null);
        boolean primary = prefs.getBoolean(KEY_VOLUME_PRIMARY, false);
        StorageVolume volume = findVolume(context, uuid, primary);
        if (volume == null) return "não encontrado";
        try { return volume.getState(); }
        catch (Exception ignored) { return Environment.MEDIA_UNKNOWN; }
    }

    private static StorageRoot findVolumeByPath(Context context, File old) {
        String wanted = canonical(old);
        for (StorageRoot item : availableRoots(context)) {
            if (!item.appPrivate && canonical(item.root).equals(wanted)) return item;
        }
        return null;
    }

    private static File resolveVolumeRoot(Context context, String uuid, boolean primary) {
        StorageVolume volume = findVolume(context, uuid, primary);
        if (volume == null) return null;
        try {
            if (!Environment.MEDIA_MOUNTED.equals(volume.getState())) return null;
        } catch (Exception ignored) {
            return null;
        }
        return storageVolumeRoot(context, volume);
    }

    private static StorageVolume findVolume(Context context, String uuid, boolean primary) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return null;

        for (StorageVolume volume : sm.getStorageVolumes()) {
            if (primary && volume.isPrimary()) return volume;
            String volumeUuid = volume.getUuid();
            if (!primary && uuid != null && uuid.equalsIgnoreCase(volumeUuid)) return volume;
        }
        return null;
    }

    private static File storageVolumeRoot(Context context, StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File dir = volume.getDirectory();
            if (dir != null) return dir;
        }

        // Android 7-10: map volume UUID/primary flag against app-specific external directories.
        File[] appDirs = context.getExternalFilesDirs(null);
        if (appDirs == null) return null;
        String uuid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? volume.getUuid() : null;
        for (File appDir : appDirs) {
            File volumeRoot = volumeRootFromAppDir(appDir);
            if (volumeRoot == null) continue;
            if (volume.isPrimary() && canonical(volumeRoot).equals(canonical(Environment.getExternalStorageDirectory())))
                return volumeRoot;
            if (uuid != null && canonical(volumeRoot).toLowerCase().contains(uuid.toLowerCase()))
                return volumeRoot;
        }
        return null;
    }

    private static File volumeRootFromAppDir(File appDir) {
        if (appDir == null) return null;
        String path = appDir.getAbsolutePath();
        int marker = path.indexOf(File.separator + "Android" + File.separator + "data" + File.separator);
        return marker > 0 ? new File(path.substring(0, marker)) : null;
    }

    private static void add(List<StorageRoot> out, Set<String> seen, StorageRoot item) {
        String key = canonical(item.root);
        if (seen.add(key)) out.add(item);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String canonical(File file) {
        try { return file.getCanonicalPath(); }
        catch (IOException ignored) { return file.getAbsolutePath(); }
    }

    private static String shortMessage(Throwable e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    public static final class StorageRoot {
        public final String label;
        public final File root;
        public final boolean removable;
        public final boolean appPrivate;
        public final String uuid;
        public final boolean primary;
        public final String state;

        StorageRoot(String label, File root, boolean removable, boolean appPrivate,
                    String uuid, boolean primary, String state) {
            this.label = label;
            this.root = root;
            this.removable = removable;
            this.appPrivate = appPrivate;
            this.uuid = uuid;
            this.primary = primary;
            this.state = state;
        }

        @Override public String toString() {
            return label + "\n" + root.getAbsolutePath();
        }
    }

    public static final class AccessResult {
        public final boolean ok;
        public final File root;
        public final String message;
        public final long totalBytes;
        public final long freeBytes;

        private AccessResult(boolean ok, File root, String message, long totalBytes, long freeBytes) {
            this.ok = ok;
            this.root = root;
            this.message = message;
            this.totalBytes = totalBytes;
            this.freeBytes = freeBytes;
        }

        static AccessResult ok(File root, long total, long free) {
            return new AccessResult(true, root, null, total, free);
        }

        static AccessResult fail(File root, String message) {
            return new AccessResult(false, root, message, 0, 0);
        }
    }
}
