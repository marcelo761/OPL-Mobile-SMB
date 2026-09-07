package com.oplmobilesmb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmbService extends Service {
    public static final String ACTION_START = "com.oplmobilesmb.START";
    public static final String ACTION_STOP = "com.oplmobilesmb.STOP";
    public static final String ACTION_RESTART = "com.oplmobilesmb.RESTART";
    private static final String CHANNEL_ID = "opl_smb";
    private static final int NOTIFICATION_ID = 4450;

    public static volatile boolean running = false;
    public static volatile String lastError = null;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private OplSmbServer smbServer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            executor.execute(this::stopServerAndSelf);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(
                ACTION_RESTART.equals(action) ? "Trocando armazenamento SMB…" : "Iniciando servidor SMB…"));

        if (ACTION_RESTART.equals(action)) {
            executor.execute(() -> {
                stopServerOnly();
                startServer();
            });
        } else {
            executor.execute(this::startServer);
        }
        return START_STICKY;
    }

    private void startServer() {
        if (running) return;
        try {
            StoragePaths.AccessResult access = StoragePaths.validate(this, false);
            if (!access.ok)
                throw new IllegalStateException(access.message + " [" + access.root.getAbsolutePath() + "]");
            File root = access.root;

            acquireLocks();
            smbServer = new OplSmbServer(root);
            smbServer.start();
            running = true;
            lastError = null;
            updateNotification("SMB ativo em " + endpointText());
        } catch (Exception e) {
            running = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            updateNotification("Erro ao iniciar SMB");
            releaseLocks();
        }
    }

    private void stopServerOnly() {
        try {
            if (smbServer != null) smbServer.stop();
        } catch (Exception ignored) {
        } finally {
            smbServer = null;
            running = false;
            releaseLocks();
        }
    }

    private void stopServerAndSelf() {
        stopServerOnly();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private String endpointText() {
        String ip = NetworkUtils.getLocalIpv4();
        return (ip != null ? ip : "IP local") + ":" + OplSmbServer.PORT;
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OPLMobileSMB:Server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }

        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OPLMobileSMB:WiFi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wifiLock = null;
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Servidor OPL SMB",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantém o servidor SMB disponível para o Open PS2 Loader.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, SmbService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("OPL Mobile SMB")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Parar", stopPi).build())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        if (running || smbServer != null) stopServerOnly();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
