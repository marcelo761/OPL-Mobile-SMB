package com.oplmobilesmb;

import android.os.Build;

import org.filesys.server.NetworkServer;
import org.filesys.server.SrvSession;
import org.filesys.server.auth.ClientInfo;
import org.filesys.server.auth.ISMBAuthenticator;
import org.filesys.server.auth.LocalAuthenticator;
import org.filesys.server.auth.SMBAuthenticator;
import org.filesys.server.auth.UserAccountList;
import org.filesys.server.auth.acl.DefaultAccessControlManager;
import org.filesys.server.config.CoreServerConfigSection;
import org.filesys.server.config.GlobalConfigSection;
import org.filesys.server.config.SecurityConfigSection;
import org.filesys.server.config.ServerConfiguration;
import org.filesys.server.core.SharedDevice;
import org.filesys.server.filesys.DiskDeviceContext;
import org.filesys.server.filesys.DiskInterface;
import org.filesys.server.filesys.DiskSharedDevice;
import org.filesys.server.filesys.FilesystemsConfigSection;
import org.filesys.smb.server.SMBConfigSection;
import org.filesys.smb.server.SMBServer;
import org.filesys.smb.server.SMBSrvSession;
import org.filesys.smb.server.disk.JavaNIODiskDriver;
import org.springframework.extensions.config.element.GenericConfigElement;

import java.io.File;
import java.util.EnumSet;

/**
 * Minimal SMBv1 server configuration for OPL.
 *
 * The networking/configuration approach follows the public JFileServer API and the
 * Android compatibility choices demonstrated by the MPL-2.0 SimbaDroid project.
 */
public final class OplSmbServer {
    public static final int PORT = 4450;
    public static final String SHARE = "PS2SMB";

    private final ServerConfiguration config;
    private final SMBServer server;
    private volatile boolean started;

    public OplSmbServer(File shareRoot) throws Exception {
        shareRoot.mkdirs();
        config = buildConfiguration(shareRoot);
        server = new SMBServer(config);
    }

    public synchronized void start() throws Exception {
        if (started) return;
        server.startServer();
        started = true;
    }

    public synchronized void stop() {
        if (!started) return;
        server.shutdownServer(false);
        started = false;
    }

    public boolean isStarted() {
        return started;
    }

    private static ServerConfiguration buildConfiguration(File shareRoot) throws Exception {
        ServerConfiguration cfg = new ServerConfiguration("OPLMOBILE");

        CoreServerConfigSection core = new CoreServerConfigSection(cfg);
        core.setMemoryPool(
                new int[]{256, 4096, 16384, 66000},
                new int[]{20, 20, 5, 5},
                new int[]{100, 50, 50, 50});
        core.setThreadPool(6, 12);
        core.getThreadPool().setDebug(false);

        new GlobalConfigSection(cfg);

        SecurityConfigSection security = new SecurityConfigSection(cfg);
        DefaultAccessControlManager acl = new DefaultAccessControlManager();
        acl.setDebug(false);
        acl.initialize(cfg, new GenericConfigElement("aclManager"));
        security.setAccessControlManager(acl);
        security.setUserAccounts(new UserAccountList());

        FilesystemsConfigSection filesystems = new FilesystemsConfigSection(cfg);
        addShare(cfg, security, filesystems, SHARE, shareRoot);

        SMBConfigSection smb = new SMBConfigSection(cfg);
        smb.setServerName("OPLMOBILE");
        smb.setDomainName("WORKGROUP");
        smb.setHostAnnouncer(false);

        // OPL connects directly by IP to the native SMB-over-TCP port below.
        // Disable the legacy NetBIOS SMB listener so Android does not try to bind
        // privileged ports 137/138/139. Keep the high-port mappings as a safeguard
        // if NetBIOS is enabled again later.
        smb.setNetBIOSSMB(false);
        smb.setNameServerPort(1137);
        smb.setDatagramPort(1138);
        smb.setSessionPort(1139);

        smb.setTcpipSMB(true);
        smb.setTcpipSMBPort(PORT);

        SMBAuthenticator auth = new LocalAuthenticator() {
            @Override
            public AuthStatus authenticateUser(ClientInfo client, SrvSession sess, PasswordAlgorithm alg) {
                return AuthStatus.AUTHENTICATED;
            }
        };
        auth.setDebug(false);
        auth.setAllowGuest(true);
        auth.setAccessMode(ISMBAuthenticator.AuthMode.USER);
        auth.initialize(cfg, new GenericConfigElement("authenticator"));
        smb.setAuthenticator(auth);
        smb.setNetBIOSDebug(false);
        smb.setHostAnnounceDebug(false);
        smb.setSessionDebugFlags(EnumSet.noneOf(SMBSrvSession.Dbg.class));

        // Matches the Android compatibility workaround used by SimbaDroid/JFileServer.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            smb.setDisableHashedOpenFileMap(true);
        }

        return cfg;
    }

    private static void addShare(
            ServerConfiguration cfg,
            SecurityConfigSection security,
            FilesystemsConfigSection filesystems,
            String shareName,
            File shareRoot) throws Exception {

        DiskInterface driver = new JavaNIODiskDriver();
        GenericConfigElement driverConfig = new GenericConfigElement("driver");

        GenericConfigElement localPath = new GenericConfigElement("LocalPath");
        localPath.setValue(shareRoot.getAbsolutePath());
        driverConfig.addChild(localPath);
        driverConfig.addChild(new GenericConfigElement("DiskIsCaseInsensitive"));

        DiskDeviceContext ctx = (DiskDeviceContext) driver.createContext(shareName, driverConfig);
        ctx.setShareName(shareName);
        ctx.setConfigurationParameters(driverConfig);
        ctx.enableChangeHandler(false);

        DiskSharedDevice dev = new DiskSharedDevice(shareName, driver, ctx);
        dev.setConfiguration(cfg);
        dev.setAccessControlList(security.getGlobalAccessControls());
        ctx.startFilesystem(dev);
        filesystems.addShare(dev);
    }
}
