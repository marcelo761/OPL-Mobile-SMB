package com.oplmobilesmb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_ISO = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView serverStatus;
    private TextView connectionInfo;
    private TextView storageInfo;
    private TextView copyStatus;
    private ProgressBar copyProgress;
    private LinearLayout gamesContainer;
    private Button startStopButton;
    private String pendingFolder = "DVD";

    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            refreshServerStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StoragePaths.ensure(this);
        setContentView(buildUi());
        refreshAll();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(uiTicker);
        refreshAll();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(uiTicker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17, 19, 24));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(36));
        scroll.addView(root);

        TextView title = text("OPL Mobile SMB", 28, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView sub = text("Seu Android vira o servidor SMBv1 do Open PS2 Loader.", 15, Color.LTGRAY);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        root.addView(sectionTitle("Servidor"));
        serverStatus = text("Parado", 18, Color.WHITE);
        root.addView(serverStatus);
        connectionInfo = text("", 15, Color.LTGRAY);
        connectionInfo.setPadding(0, dp(5), 0, dp(12));
        root.addView(connectionInfo);

        startStopButton = button("INICIAR SERVIDOR SMB");
        startStopButton.setOnClickListener(v -> toggleServer());
        root.addView(startStopButton, matchWrap());

        TextView oplHint = text("No OPL: IP = endereço abaixo, porta = 4450, share = PS2SMB, usuário/senha vazios.", 14, Color.LTGRAY);
        oplHint.setPadding(0, dp(10), 0, dp(22));
        root.addView(oplHint);

        root.addView(sectionTitle("Adicionar jogo"));
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        Button addDvd = button("+ ISO DVD");
        Button addCd = button("+ ISO CD");
        addDvd.setOnClickListener(v -> chooseIso("DVD"));
        addCd.setOnClickListener(v -> chooseIso("CD"));
        addRow.addView(addDvd, weightParams());
        addRow.addView(addCd, weightParams());
        root.addView(addRow, matchWrap());

        copyProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        copyProgress.setMax(1000);
        copyProgress.setVisibility(View.GONE);
        copyProgress.setPadding(0, dp(12), 0, 0);
        root.addView(copyProgress, matchWrap());
        copyStatus = text("", 13, Color.LTGRAY);
        root.addView(copyStatus);

        storageInfo = text("", 14, Color.LTGRAY);
        storageInfo.setPadding(0, dp(12), 0, dp(20));
        root.addView(storageInfo);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView gamesTitle = sectionTitle("Jogos no celular");
        header.addView(gamesTitle, weightParams());
        Button refresh = button("Atualizar");
        refresh.setOnClickListener(v -> refreshGames());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(120), dp(48)));
        root.addView(header, matchWrap());

        gamesContainer = new LinearLayout(this);
        gamesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(gamesContainer, matchWrap());

        TextView path = text("Pasta interna do app: " + StoragePaths.root(this).getAbsolutePath(), 12, Color.GRAY);
        path.setPadding(0, dp(24), 0, 0);
        root.addView(path);

        return scroll;
    }

    private void toggleServer() {
        Intent intent = new Intent(this, SmbService.class);
        if (SmbService.running) {
            intent.setAction(SmbService.ACTION_STOP);
            startService(intent);
        } else {
            intent.setAction(SmbService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        }
        handler.postDelayed(this::refreshServerStatus, 300);
    }

    private void refreshServerStatus() {
        String ip = NetworkUtils.getLocalIpv4();
        if (SmbService.running) {
            serverStatus.setText("● SMB ATIVO");
            serverStatus.setTextColor(Color.rgb(99, 211, 138));
            startStopButton.setText("PARAR SERVIDOR");
        } else {
            serverStatus.setText(SmbService.lastError == null ? "● SMB PARADO" : "● ERRO NO SMB");
            serverStatus.setTextColor(SmbService.lastError == null ? Color.LTGRAY : Color.rgb(255, 107, 107));
            startStopButton.setText("INICIAR SERVIDOR SMB");
        }

        connectionInfo.setText(
                "IP: " + (ip == null ? "sem rede local" : ip) +
                "\nPorta: " + OplSmbServer.PORT +
                "\nShare: " + OplSmbServer.SHARE +
                (SmbService.lastError != null ? "\nErro: " + SmbService.lastError : ""));
    }

    private void chooseIso(String folder) {
        pendingFolder = folder;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_ISO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ISO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        FileMeta meta = queryMeta(uri);
        if (meta.name == null || !meta.name.toLowerCase().endsWith(".iso")) {
            Toast.makeText(this, "Escolha um arquivo .iso", Toast.LENGTH_LONG).show();
            return;
        }

        File targetDir = "CD".equals(pendingFolder) ? StoragePaths.cd(this) : StoragePaths.dvd(this);
        File target = new File(targetDir, safeName(meta.name));
        if (target.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("Arquivo já existe")
                    .setMessage("Substituir " + target.getName() + "?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Substituir", (d, w) -> copyIso(uri, target, meta.size))
                    .show();
        } else {
            copyIso(uri, target, meta.size);
        }
    }

    private void copyIso(Uri uri, File target, long total) {
        copyProgress.setVisibility(View.VISIBLE);
        copyProgress.setProgress(0);
        copyStatus.setText("Copiando " + target.getName() + "…");

        io.execute(() -> {
            File part = new File(target.getParentFile(), "." + target.getName() + ".part");
            long copied = 0;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(part)) {
                if (in == null) throw new IllegalStateException("Não foi possível abrir a ISO");
                byte[] buf = new byte[1024 * 1024];
                int n;
                long lastUi = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) continue;
                    out.write(buf, 0, n);
                    copied += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 250) {
                        long c = copied;
                        runOnUiThread(() -> updateCopyProgress(c, total));
                        lastUi = now;
                    }
                }
                out.getFD().sync();
                if (target.exists() && !target.delete()) throw new IllegalStateException("Não consegui substituir a ISO antiga");
                if (!part.renameTo(target)) throw new IllegalStateException("Não consegui finalizar o arquivo");
                runOnUiThread(() -> {
                    copyProgress.setProgress(1000);
                    copyStatus.setText("Concluído: " + target.getName());
                    refreshAll();
                });
            } catch (Exception e) {
                part.delete();
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    copyStatus.setText("Erro: " + msg);
                    Toast.makeText(this, "Falha ao copiar ISO", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateCopyProgress(long copied, long total) {
        if (total > 0) {
            int p = (int) Math.min(1000, copied * 1000L / total);
            copyProgress.setProgress(p);
            copyStatus.setText("Copiando… " + (p / 10.0) + "% — " + formatBytes(copied) + " / " + formatBytes(total));
        } else {
            copyStatus.setText("Copiando… " + formatBytes(copied));
        }
    }

    private void refreshAll() {
        refreshServerStatus();
        refreshGames();
        long free = StoragePaths.root(this).getUsableSpace();
        long total = StoragePaths.root(this).getTotalSpace();
        storageInfo.setText("Armazenamento: " + formatBytes(free) + " livres de " + formatBytes(total));
    }

    private void refreshGames() {
        gamesContainer.removeAllViews();
        List<GameFile> games = new ArrayList<>();
        collect(games, "DVD", StoragePaths.dvd(this));
        collect(games, "CD", StoragePaths.cd(this));
        games.sort(Comparator.comparing(g -> g.file.getName().toLowerCase()));

        if (games.isEmpty()) {
            TextView empty = text("Nenhuma ISO adicionada.", 15, Color.LTGRAY);
            empty.setPadding(0, dp(12), 0, 0);
            gamesContainer.addView(empty);
            return;
        }

        for (GameFile game : games) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView info = text(game.file.getName() + "\n" + game.kind + " • " + formatBytes(game.file.length()), 15, Color.WHITE);
            row.addView(info, weightParams());

            Button delete = button("Excluir");
            delete.setOnClickListener(v -> confirmDelete(game));
            row.addView(delete, new LinearLayout.LayoutParams(dp(100), dp(48)));
            gamesContainer.addView(row, matchWrap());
        }
    }

    private void confirmDelete(GameFile game) {
        new AlertDialog.Builder(this)
                .setTitle("Excluir ISO")
                .setMessage("Excluir " + game.file.getName() + "?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> {
                    if (!game.file.delete()) Toast.makeText(this, "Não consegui excluir", Toast.LENGTH_SHORT).show();
                    refreshAll();
                })
                .show();
    }

    private static void collect(List<GameFile> out, String kind, File dir) {
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".iso"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) if (f.isFile()) out.add(new GameFile(kind, f));
    }

    private FileMeta queryMeta(Uri uri) {
        String name = null;
        long size = -1;
        ContentResolver resolver = getContentResolver();
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        }
        return new FileMeta(name, size);
    }

    private static String safeName(String name) {
        return name.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private TextView sectionTitle(String value) {
        TextView v = text(value, 20, Color.WHITE);
        v.setTypeface(null, 1);
        v.setPadding(0, dp(8), 0, dp(10));
        return v;
    }

    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double n = bytes;
        int i = 0;
        while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
        return new DecimalFormat(i == 0 ? "0" : "0.00").format(n) + " " + units[i];
    }

    private static final class FileMeta {
        final String name;
        final long size;
        FileMeta(String name, long size) { this.name = name; this.size = size; }
    }

    private static final class GameFile {
        final String kind;
        final File file;
        GameFile(String kind, File file) { this.kind = kind; this.file = file; }
    }
}
