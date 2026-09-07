package com.oplmobilesmb;

import android.content.Context;

import java.io.File;

public final class StoragePaths {
    private StoragePaths() {}

    public static File root(Context context) {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : context.getFilesDir();
        return new File(base, "PS2SMB");
    }

    public static File dvd(Context context) {
        return new File(root(context), "DVD");
    }

    public static File cd(Context context) {
        return new File(root(context), "CD");
    }

    public static void ensure(Context context) {
        dvd(context).mkdirs();
        cd(context).mkdirs();
    }
}
