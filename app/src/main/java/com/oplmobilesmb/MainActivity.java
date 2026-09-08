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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    private static final int REQUEST_STORAGE = 1003;
    private static final int REQUEST_USBUTIL = 1004;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView serverStatus;
    private TextView connectionInfo;
    private TextView storagePath;
    private TextView storageInfo;
    private TextView copyStatus;
    private ProgressBar copyProgress;
    private ProgressBar storageUsage;
    private LinearLayout gamesContainer;
    private Button startStopButton;
    private String pendingFolder = "DVD";
    private boolean restartAfterStoragePermission;
    private boolean validateAfterStoragePermission;

    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            refreshServerStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (hasSelectedStorageAccess()) StoragePaths.ensure(this);
        setContentView(buildUi());
        refreshAll();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(uiTicker);

        if (validateAfterStoragePermission && hasSelectedStorageAccess()) {
            boolean restart = restartAfterStoragePermission;
            validateAfterStoragePermission = false;
            restartAfterStoragePermission = false;
            completeStorageSelection(restart);
            return;
        }

        if (hasSelectedStorageAccess()) {
            StoragePaths.AccessResult access = StoragePaths.validate(this, false);
            if (access.ok) StoragePaths.ensure(this);
        }
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

        TextView oplHint = text("No OPL: IP = endereço abaixo, porta = 4450, share = PS2SMB, usuário = GUEST, senha vazia.", 14, Color.LTGRAY);
        oplHint.setPadding(0, dp(10), 0, dp(22));
        root.addView(oplHint);

        root.addView(sectionTitle("Armazenamento dos jogos"));
        storagePath = text("", 14, Color.WHITE);
        storagePath.setPadding(0, 0, 0, dp(8));
        root.addView(storagePath);

        Button chooseStorage = button("Selecionar armazenamento / pendrive");
        chooseStorage.setOnClickListener(v -> chooseStorageRoot());
        root.addView(chooseStorage, matchWrap());

        storageInfo = text("", 14, Color.LTGRAY);
        storageInfo.setPadding(0, dp(10), 0, dp(6));
        root.addView(storageInfo);

        storageUsage = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        storageUsage.setMax(1000);
        LinearLayout.LayoutParams storageBarParams = matchWrap();
        storageBarParams.setMargins(0, 0, 0, dp(20));
        root.addView(storageUsage, storageBarParams);

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

        Button addUsbUtil = button("+ Instalar como USBUtil");
        addUsbUtil.setOnClickListener(v -> chooseUsbUtil());
        LinearLayout.LayoutParams usbButtonParams = matchWrap();
        usbButtonParams.setMargins(0, dp(8), 0, 0);
        root.addView(addUsbUtil, usbButtonParams);

        copyProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        copyProgress.setMax(1000);
        copyProgress.setVisibility(View.GONE);
        copyProgress.setPadding(0, dp(12), 0, 0);
        root.addView(copyProgress, matchWrap());
        copyStatus = text("", 13, Color.LTGRAY);
        root.addView(copyStatus);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView gamesTitle = sectionTitle("Gerenciar jogos");
        header.addView(gamesTitle, weightParams());
        Button refresh = button("Atualizar");
        refresh.setOnClickListener(v -> refreshGames());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(120), dp(48)));
        root.addView(header, matchWrap());

        gamesContainer = new LinearLayout(this);
        gamesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(gamesContainer, matchWrap());

        TextView usbUtilHint = text(
                "USBUtil/USBExtreme: o app pode empacotar uma ISO diretamente na raiz selecionada, criando ul.cfg + ul.*. " +
                "Jogos USBUtil existentes também são reconhecidos e podem ser desinstalados com atualização segura do ul.cfg; ISOs normais ficam em DVD/ ou CD/.", 12, Color.GRAY);
        usbUtilHint.setPadding(0, dp(24), 0, 0);
        root.addView(usbUtilHint);

        return scroll;
    }

    private void toggleServer() {
        if (!hasSelectedStorageAccess()) {
            requestSelectedStorageAccess();
            return;
        }
        if (!SmbService.running) {
            StoragePaths.AccessResult access = StoragePaths.validate(this, false);
            if (!access.ok) {
                SmbService.lastError = "Armazenamento: " + access.message;
                Toast.makeText(this, access.message, Toast.LENGTH_LONG).show();
                refreshAll();
                return;
            }
        }
        startSmbService(SmbService.running ? SmbService.ACTION_STOP : SmbService.ACTION_START);
        handler.postDelayed(this::refreshServerStatus, 300);
    }

    private void startSmbService(String action) {
        Intent intent = new Intent(this, SmbService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !SmbService.ACTION_STOP.equals(action))
            startForegroundService(intent);
        else
            startService(intent);
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

    private void chooseStorageRoot() {
        List<StoragePaths.StorageRoot> roots = StoragePaths.availableRoots(this);
        if (roots.isEmpty()) {
            Toast.makeText(this, "Nenhum armazenamento encontrado", Toast.LENGTH_LONG).show();
            return;
        }

        String current = StoragePaths.root(this).getAbsolutePath();
        String[] labels = new String[roots.size()];
        int checked = -1;
        for (int i = 0; i < roots.size(); i++) {
            StoragePaths.StorageRoot item = roots.get(i);
            labels[i] = item.label + "\n" + item.root.getAbsolutePath();
            if (item.root.getAbsolutePath().equals(current)) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Onde ficam os jogos?")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    applyStorageRoot(roots.get(which));
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void applyStorageRoot(StoragePaths.StorageRoot selected) {
        boolean wasRunning = SmbService.running;
        if (wasRunning) startSmbService(SmbService.ACTION_STOP);

        StoragePaths.setRoot(this, selected);
        SmbService.lastError = null;

        if (!selected.appPrivate && !hasBroadStorageAccess()) {
            restartAfterStoragePermission = wasRunning;
            validateAfterStoragePermission = true;
            refreshAll();
            new AlertDialog.Builder(this)
                    .setTitle("Permitir acesso ao armazenamento")
                    .setMessage("Para servir a raiz do pendrive diretamente pelo SMB, o Android precisa liberar ‘acesso a todos os arquivos’. Depois de permitir, o app testa leitura, escrita e espaço real antes de aceitar o volume.")
                    .setNegativeButton("Agora não", null)
                    .setPositiveButton("Permitir", (d, w) -> requestBroadStorageAccess())
                    .show();
            return;
        }

        completeStorageSelection(wasRunning);
    }

    private void completeStorageSelection(boolean restartServer) {
        StoragePaths.AccessResult access = StoragePaths.validate(this, true);
        if (!access.ok) {
            SmbService.lastError = "Armazenamento: " + access.message;
            refreshAll();
            new AlertDialog.Builder(this)
                    .setTitle("Pendrive indisponível")
                    .setMessage(access.message + "\n\nCaminho detectado: " + access.root.getAbsolutePath() +
                            "\n\nTente remover e reconectar o pendrive e selecione-o novamente.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        StoragePaths.ensure(this);
        SmbService.lastError = null;
        if (restartServer) startSmbService(SmbService.ACTION_START);
        refreshAll();
    }

    private boolean hasSelectedStorageAccess() {
        return StoragePaths.isDefaultRoot(this) || hasBroadStorageAccess();
    }

    private boolean hasBroadStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean requireWritableStorage() {
        if (!hasSelectedStorageAccess()) {
            requestSelectedStorageAccess();
            return false;
        }
        StoragePaths.AccessResult access = StoragePaths.validate(this, true);
        if (!access.ok) {
            Toast.makeText(this, "Armazenamento indisponível: " + access.message, Toast.LENGTH_LONG).show();
            refreshAll();
            return false;
        }
        StoragePaths.ensure(this);
        return true;
    }

    private void requestSelectedStorageAccess() {
        if (StoragePaths.isDefaultRoot(this)) return;
        requestBroadStorageAccess();
    }

    private void requestBroadStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_STORAGE);
        }
    }

    private void chooseIso(String folder) {
        if (!requireWritableStorage()) return;
        pendingFolder = folder;
        launchIsoPicker(REQUEST_ISO);
    }

    private void chooseUsbUtil() {
        if (!requireWritableStorage()) return;
        launchIsoPicker(REQUEST_USBUTIL);
    }

    private void launchIsoPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_ISO && requestCode != REQUEST_USBUTIL) ||
                resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        FileMeta meta = queryMeta(uri);
        if (meta.name == null || !meta.name.toLowerCase().endsWith(".iso")) {
            Toast.makeText(this, "Escolha um arquivo .iso", Toast.LENGTH_LONG).show();
            return;
        }

        if (!requireWritableStorage()) return;
        StoragePaths.ensure(this);
        if (requestCode == REQUEST_USBUTIL) {
            File root = StoragePaths.root(this);
            if (!root.isDirectory() || !root.canWrite()) {
                Toast.makeText(this, "Sem permissão para gravar no armazenamento escolhido", Toast.LENGTH_LONG).show();
                return;
            }
            showUsbUtilInstallDialog(uri, meta);
            return;
        }

        File targetDir = "CD".equals(pendingFolder) ? StoragePaths.cd(this) : StoragePaths.dvd(this);
        if (!targetDir.isDirectory() || !targetDir.canWrite()) {
            Toast.makeText(this, "Sem permissão para gravar no armazenamento escolhido", Toast.LENGTH_LONG).show();
            return;
        }
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

    private void showUsbUtilInstallDialog(Uri uri, FileMeta meta) {
        String fileTitle = meta.name.substring(0, meta.name.length() - 4).trim();
        String detectedId = UsbUtilInstaller.extractGameId(fileTitle);
        if (!detectedId.isBlank()) {
            fileTitle = fileTitle.replaceFirst("(?i)^" + java.util.regex.Pattern.quote(detectedId) + "[ ._-]*", "").trim();
        }
        if (fileTitle.isBlank()) fileTitle = "PS2 Game";
        if (fileTitle.length() > 31) fileTitle = fileTitle.substring(0, 31).trim();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        TextView destination = text("Destino: " + StoragePaths.root(this).getAbsolutePath(), 13, Color.LTGRAY);
        destination.setPadding(0, 0, 0, dp(8));
        form.addView(destination);

        EditText titleInput = new EditText(this);
        titleInput.setHint("Nome do jogo (máx. 31 bytes)");
        titleInput.setSingleLine(true);
        titleInput.setText(fileTitle);
        form.addView(titleInput, matchWrap());

        EditText idInput = new EditText(this);
        idInput.setHint("Game ID, ex.: SLUS_202.16");
        idInput.setSingleLine(true);
        idInput.setText(detectedId);
        form.addView(idInput, matchWrap());

        TextView mediaLabel = text("Tipo de mídia", 14, Color.LTGRAY);
        mediaLabel.setPadding(0, dp(8), 0, 0);
        form.addView(mediaLabel);

        RadioGroup mediaGroup = new RadioGroup(this);
        mediaGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton dvd = new RadioButton(this);
        dvd.setText("DVD");
        dvd.setId(View.generateViewId());
        RadioButton cd = new RadioButton(this);
        cd.setText("CD");
        cd.setId(View.generateViewId());
        mediaGroup.addView(dvd);
        mediaGroup.addView(cd);
        if (meta.size > 0 && meta.size <= 900L * 1024 * 1024) cd.setChecked(true);
        else dvd.setChecked(true);
        form.addView(mediaGroup);

        int expected = UsbUtilInstaller.partCount(meta.size);
        TextView info = text(
                (meta.size > 0 ? "ISO: " + formatBytes(meta.size) + (expected > 0 ? " • " + expected + " parte(s) de até 1 GiB" : "") : "ISO: tamanho desconhecido") +
                "\nO ul.cfg só é atualizado depois que todas as partes terminarem.", 12, Color.GRAY);
        info.setPadding(0, dp(8), 0, 0);
        form.addView(info);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Instalar como USBUtil")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Instalar", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String title = UsbUtilInstaller.validateTitle(titleInput.getText().toString());
                String gameId = UsbUtilInstaller.normalizeGameId(idInput.getText().toString());
                int media = cd.isChecked() ? UsbUtilInstaller.MEDIA_CD : UsbUtilInstaller.MEDIA_DVD;
                installUsbUtil(uri, meta.size, title, gameId, media);
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private void installUsbUtil(Uri uri, long total, String title, String gameId, int media) {
        copyProgress.setVisibility(View.VISIBLE);
        copyProgress.setIndeterminate(total <= 0);
        copyProgress.setProgress(0);
        copyStatus.setText("Empacotando " + title + " como USBUtil…");
        File root = StoragePaths.root(this);

        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                UsbUtilInstaller.InstallResult result = UsbUtilInstaller.install(
                        in, root, title, gameId, media, total,
                        (copied, sourceTotal, currentPart, expectedParts) -> runOnUiThread(() ->
                                updateUsbUtilProgress(copied, sourceTotal, currentPart, expectedParts)));

                runOnUiThread(() -> {
                    copyProgress.setIndeterminate(false);
                    copyProgress.setProgress(1000);
                    copyStatus.setText("USBUtil concluído: " + result.title + " • " + result.parts + " parte(s) • " + formatBytes(result.bytes));
                    Toast.makeText(this, "Instalado no pendrive: " + result.firstPartName(), Toast.LENGTH_LONG).show();
                    refreshAll();
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    copyProgress.setIndeterminate(false);
                    copyStatus.setText("Erro USBUtil: " + msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    refreshAll();
                });
            }
        });
    }

    private void updateUsbUtilProgress(long copied, long total, int currentPart, int expectedParts) {
        String partText = expectedParts > 0 ? "parte " + currentPart + "/" + expectedParts : "parte " + currentPart;
        if (total > 0) {
            copyProgress.setIndeterminate(false);
            int p = (int) Math.min(1000, copied * 1000L / total);
            copyProgress.setProgress(p);
            copyStatus.setText("Empacotando USBUtil… " + (p / 10.0) + "% — " + formatBytes(copied) + " / " + formatBytes(total) + " — " + partText);
        } else {
            copyStatus.setText("Empacotando USBUtil… " + formatBytes(copied) + " — " + partText);
        }
    }

    private void copyIso(Uri uri, File target, long total) {
        copyProgress.setVisibility(View.VISIBLE);
        copyProgress.setIndeterminate(false);
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
        File root = StoragePaths.root(this);
        storagePath.setText("Raiz compartilhada: " + root.getAbsolutePath());

        if (!hasSelectedStorageAccess()) {
            storageInfo.setText("Acesso ao armazenamento pendente. Toque em “Selecionar armazenamento / pendrive” ou inicie o servidor para conceder acesso.");
            storageUsage.setProgress(0);
            gamesContainer.removeAllViews();
            gamesContainer.addView(text("Sem acesso ao armazenamento selecionado.", 15, Color.LTGRAY));
            return;
        }

        StoragePaths.AccessResult access = StoragePaths.validate(this, false);
        if (!access.ok) {
            storageInfo.setText("INDISPONÍVEL — " + access.message +
                    "\nReconecte o pendrive e selecione o armazenamento novamente.");
            storageUsage.setProgress(0);
            gamesContainer.removeAllViews();
            gamesContainer.addView(text("Armazenamento indisponível.", 15, Color.LTGRAY));
            return;
        }

        StoragePaths.ensure(this);
        root = access.root;
        long free = access.freeBytes;
        long total = access.totalBytes;
        List<GameEntry> games = scanGames();
        long libraryBytes = 0;
        for (GameEntry game : games) if (game.bytes > 0) libraryBytes += game.bytes;
        long used = total > 0 ? Math.max(0, total - free) : 0;
        int usedPermille = total > 0 ? (int) Math.min(1000, used * 1000L / total) : 0;
        storageUsage.setProgress(usedPermille);

        int usbUtilCount = 0;
        for (GameEntry game : games) if (game.usbUtil != null) usbUtilCount++;
        String usedPct = total > 0 ? new DecimalFormat("0.0").format(usedPermille / 10.0) + "% usado" : "ocupação indisponível";
        storageInfo.setText(
                formatBytes(free) + " livres de " + formatBytes(total) + " • " + usedPct +
                "\nBiblioteca OPL: " + games.size() + " jogo(s) • " + formatBytes(libraryBytes) +
                (usbUtilCount > 0 ? " • " + usbUtilCount + " USBUtil" : ""));
        renderGames(games);
    }

    private void refreshGames() {
        if (!hasSelectedStorageAccess()) return;
        renderGames(scanGames());
    }

    private List<GameEntry> scanGames() {
        List<GameEntry> games = new ArrayList<>();
        collectIso(games, "ISO DVD", StoragePaths.dvd(this));
        collectIso(games, "ISO CD", StoragePaths.cd(this));
        for (UsbUtilGames.Game game : UsbUtilGames.read(StoragePaths.root(this))) {
            String details = "USBUtil " + game.mediaName() + " • " + game.parts + " parte" + (game.parts == 1 ? "" : "s");
            if (game.bytes >= 0) details += " • " + formatBytes(game.bytes);
            if (!game.gameId.isBlank()) details += "\n" + game.gameId;
            games.add(GameEntry.usbUtil(game, details));
        }
        games.sort(Comparator.comparing(g -> g.title.toLowerCase()));
        return games;
    }

    private void renderGames(List<GameEntry> games) {
        gamesContainer.removeAllViews();
        if (games.isEmpty()) {
            TextView empty = text("Nenhuma ISO ou entrada USBUtil encontrada.", 15, Color.LTGRAY);
            empty.setPadding(0, dp(12), 0, 0);
            gamesContainer.addView(empty);
            return;
        }

        for (GameEntry game : games) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView info = text(game.title + "\n" + game.details, 15, Color.WHITE);
            row.addView(info, weightParams());

            Button manage = button("Desinstalar");
            manage.setOnClickListener(v -> confirmDelete(game));
            row.addView(manage, new LinearLayout.LayoutParams(dp(118), dp(48)));
            gamesContainer.addView(row, matchWrap());
        }
    }

    private void confirmDelete(GameEntry game) {
        String sizeText = game.bytes >= 0 ? formatBytes(game.bytes) : "tamanho desconhecido";
        String kind = game.usbUtil != null ? "USBUtil" : "ISO";
        String message = "Desinstalar " + game.title + "?\n\nFormato: " + kind +
                "\nEspaço a liberar: " + sizeText +
                (game.usbUtil != null ? "\nO registro correspondente também será removido do ul.cfg." : "");
        new AlertDialog.Builder(this)
                .setTitle("Desinstalar jogo")
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desinstalar", (d, w) -> uninstallGame(game))
                .show();
    }

    private void uninstallGame(GameEntry game) {
        copyProgress.setVisibility(View.VISIBLE);
        copyProgress.setIndeterminate(true);
        copyStatus.setText("Desinstalando " + game.title + "…");
        io.execute(() -> {
            try {
                if (game.usbUtil != null) {
                    UsbUtilManager.RemoveResult result = UsbUtilManager.uninstall(StoragePaths.root(this), game.usbUtil);
                    runOnUiThread(() -> {
                        copyProgress.setIndeterminate(false);
                        copyProgress.setProgress(1000);
                        String msg = "Desinstalado: " + result.title + " • " + formatBytes(result.bytes) + " liberados";
                        if (result.hasLeftovers()) msg += " • atenção: sobrou " + (result.partsFound - result.partsDeleted) + " arquivo temporário";
                        copyStatus.setText(msg);
                        Toast.makeText(this, result.hasLeftovers() ? "Jogo removido do OPL; verifique arquivos temporários" : "Jogo desinstalado", Toast.LENGTH_LONG).show();
                        refreshAll();
                    });
                } else {
                    if (game.file == null || !game.file.isFile()) throw new IllegalStateException("ISO não encontrada");
                    if (!game.file.delete()) throw new IllegalStateException("Não consegui excluir a ISO");
                    runOnUiThread(() -> {
                        copyProgress.setIndeterminate(false);
                        copyProgress.setProgress(1000);
                        copyStatus.setText("Desinstalado: " + game.title + " • " + formatBytes(game.bytes) + " liberados");
                        Toast.makeText(this, "Jogo desinstalado", Toast.LENGTH_SHORT).show();
                        refreshAll();
                    });
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    copyProgress.setIndeterminate(false);
                    copyStatus.setText("Erro ao desinstalar: " + msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    refreshAll();
                });
            }
        });
    }

    private static void collectIso(List<GameEntry> out, String kind, File dir) {
        File[] files;
        try {
            files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".iso"));
        } catch (SecurityException e) {
            return;
        }
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            if (f.isFile()) out.add(GameEntry.iso(f, kind + " • " + formatBytes(f.length())));
        }
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

    private static final class GameEntry {
        final String title;
        final String details;
        final File file;
        final UsbUtilGames.Game usbUtil;
        final long bytes;

        private GameEntry(String title, String details, File file, UsbUtilGames.Game usbUtil, long bytes) {
            this.title = title;
            this.details = details;
            this.file = file;
            this.usbUtil = usbUtil;
            this.bytes = bytes;
        }

        static GameEntry iso(File file, String details) {
            return new GameEntry(file.getName(), details, file, null, file.length());
        }

        static GameEntry usbUtil(UsbUtilGames.Game game, String details) {
            return new GameEntry(game.title, details, null, game, game.bytes);
        }
    }
}
