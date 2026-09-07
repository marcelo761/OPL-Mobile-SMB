package com.oplmobilesmb;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StoragePaths {
    private static final String PREFS = "storage";
    private static final String KEY_ROOT = "share_root";

    private StoragePaths() {}

    /** Default private location used when the user has not selected a storage volume. */
    public static File defaultRoot(Context context) {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : context.getFilesDir();
        return new File(base, "PS2SMB");
    }

    /**
     * Root exported as the PS2SMB share. When a USB/SD/internal shared volume is selected,
     * the volume root itself is exported so USBUtil's ul.cfg + ul.* files can remain at root.
     */
    public static File root(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ROOT, null);
        return saved == null || saved.isBlank() ? defaultRoot(context) : new File(saved);
    }

    public static void setRoot(Context context, File root) {
        if (root == null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ROOT).apply();
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ROOT, canonical(root))
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

    public static void ensure(Context context) {
        File root = root(context);
        if (!root.exists()) root.mkdirs();
        dvd(context).mkdirs();
        cd(context).mkdirs();
    }

    /** List storage roots that can be served by JFileServer's File/NIO backend. */
    public static List<StorageRoot> availableRoots(Context context) {
        List<StorageRoot> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        File def = defaultRoot(context);
        add(result, seen, new StorageRoot("Pasta privada do app", def, false, true));

        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            for (StorageVolume volume : sm.getStorageVolumes()) {
                File root = storageVolumeRoot(context, volume);
                if (root == null) continue;
                String description;
                try {
                    description = volume.getDescription(context);
                } catch (Exception ignored) {
                    description = volume.isRemovable() ? "Armazenamento removível" : "Armazenamento interno";
                }
                String prefix = volume.isRemovable() ? "USB/SD — " : "Interno — ";
                add(result, seen, new StorageRoot(prefix + description, root, volume.isRemovable(), false));
            }
        } else {
            // Fallback for unusual Android builds: infer volume roots from app external directories.
            File[] dirs = context.getExternalFilesDirs(null);
            if (dirs != null) {
                for (File dir : dirs) {
                    File volumeRoot = volumeRootFromAppDir(dir);
                    if (volumeRoot != null)
                        add(result, seen, new StorageRoot("Armazenamento — " + volumeRoot.getName(), volumeRoot, true, false));
                }
            }
        }
        return result;
    }

    private static File storageVolumeRoot(Context context, StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File dir = volume.getDirectory();
            if (dir != null) return dir;
        }

        // On Android 7-10 map the volume UUID/primary flag against getExternalFilesDirs().
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

    private static String canonical(File file) {
        try { return file.getCanonicalPath(); }
        catch (IOException ignored) { return file.getAbsolutePath(); }
    }

    public static final class StorageRoot {
        public final String label;
        public final File root;
        public final boolean removable;
        public final boolean appPrivate;

        StorageRoot(String label, File root, boolean removable, boolean appPrivate) {
            this.label = label;
            this.root = root;
            this.removable = removable;
            this.appPrivate = appPrivate;
        }

        @Override public String toString() {
            return label + "\n" + root.getAbsolutePath();
        }
    }
}
