import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import io.nekohasekai.libbox.AutoRedirectHandler;
import io.nekohasekai.libbox.AutoRedirectSession;
import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.SystemProxyStatus;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

public class MyVpnService extends VpnService implements PlatformInterface, CommandServerHandler {
    private static final String TAG = "MyVpnService";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    public static boolean isRunning = false;
    private CommandServer commandServer;
    private ParcelFileDescriptor tunFd;
    private InterfaceUpdateListener defaultInterfaceListener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network lastUnderlyingNetwork;
    private String lastInterfaceName;
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate() вызван");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "null";
        Log.d(TAG, "onStartCommand() action=" + action + ", flags=" + flags + ", startId=" + startId);
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        String configJson = null;
        if (intent != null && intent.hasExtra("config_json")) {
            configJson = intent.getStringExtra("config_json");
        } else {
            android.content.SharedPreferences prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE);
            String savedVless = prefs.getString("vless_link", "");
            if (!savedVless.isEmpty()) {
                try {
                    configJson = VlessParser.parseToSingBoxConfig(savedVless);
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка парсинга сохраненной VLESS ссылки", e);
                }
            }
            if (configJson == null) {
                throw new RuntimeException("Не найдена VLESS ссылка для запуска VPN");
            }
        }

        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        startVpn(configJson);
        return START_STICKY;
    }

    private void startVpn(String configJson) {
        try {
            OverrideOptions overrideOptions = new OverrideOptions();

            if (commandServer == null) {
                commandServer = Libbox.newCommandServer(this, this);
                commandServer.start();
            }

            commandServer.startOrReloadService(configJson, overrideOptions);

            isRunning = true;
            getSharedPreferences("vpn_prefs", MODE_PRIVATE).edit().putBoolean("service_running", true).apply();
            Log.i(TAG, "VPN и sing-box TUN запущены/перезагружены успешно");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при запуске sing-box VPN", e);
            stopVpn();
        }
    }

    private void stopVpn() {
        isRunning = false;
        getSharedPreferences("vpn_prefs", MODE_PRIVATE).edit().putBoolean("service_running", false).apply();

        try {
            setUnderlyingNetworks(null);
        } catch (Exception e) {
            Log.w(TAG, "setUnderlyingNetworks(null) error: " + e.getMessage());
        }

        if (commandServer != null) {
            try {
                commandServer.closeService();
                commandServer.close();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка закрытия commandServer", e);
            }
            commandServer = null;
        }

        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка закрытия tunFd", e);
            }
            tunFd = null;
        }

        closeDefaultInterfaceMonitor(defaultInterfaceListener);

        boolean persistent = getSharedPreferences("vpn_prefs", MODE_PRIVATE).getBoolean("persistent_notification", false);
        if (persistent) {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

            Intent startIntent = new Intent(this, MyVpnService.class);
            startIntent.setAction("START");
            PendingIntent startPendingIntent = PendingIntent.getService(this, 2, startIntent, flags);

            android.content.SharedPreferences prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE);
            String savedVless = prefs.getString("vless_link", "");
            String serverName = VlessParser.parseServerName(savedVless);
            if (serverName.isEmpty()) serverName = "VLESS Server";

            Notification stoppedNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Semyon_VPN_Client")
                    .setContentText("VPN отключен (" + serverName + ")")
                    .setSmallIcon(R.drawable.baseline_vpn_key_24)
                    .setContentIntent(pendingIntent)
                    .addAction(R.drawable.baseline_power_settings_new_24, "Подключить", startPendingIntent)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIFICATION_ID, stoppedNotification);
            Log.i(TAG, "VPN туннель остановлен, уведомление оставлено постоянным с кнопкой Подключить");
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
            Log.i(TAG, "VPN служба полностью остановлена");
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy() вызван");
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        Log.d(TAG, "onRevoke() вызван — VPN разрешение отозвано системой");
        stopVpn();
        super.onRevoke();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "VPN Service Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        Intent stopIntent = new Intent(this, MyVpnService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags);

        android.content.SharedPreferences prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE);
        String savedVless = prefs.getString("vless_link", "");
        String serverName = VlessParser.parseServerName(savedVless);
        if (serverName.isEmpty()) serverName = "VLESS Server";

        boolean persistent = prefs.getBoolean("persistent_notification", false);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Semyon_VPN_Client")
                .setContentText("Подключено: " + serverName)
                .setSmallIcon(R.drawable.baseline_vpn_key_24)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.baseline_power_settings_new_24_red, "Остановить", stopPendingIntent)
                .setOngoing(persistent)
                .build();
    }

    @Override
    public int openTun(TunOptions options) throws Exception {
        Log.i(TAG, "===> openTun() вызван. MTU=" + options.getMTU() + ", autoRoute=" + options.getAutoRoute() + ", strictRoute=" + options.getStrictRoute());
        Builder builder = new Builder();
        builder.setSession("SemyonVPN");

        int mtu = options.getMTU();
        if (mtu <= 0 || mtu > 1280) {
            mtu = 1280;
        }
        builder.setMtu(mtu);

        boolean hasIpv4Address = false;
        boolean hasIpv6Address = false;

        try {
            RoutePrefixIterator v4AddrIter = options.getInet4Address();
            if (v4AddrIter != null) {
                while (v4AddrIter.hasNext()) {
                    RoutePrefix prefix = v4AddrIter.next();
                    builder.addAddress(prefix.address(), prefix.prefix());
                    hasIpv4Address = true;
                    Log.i(TAG, "  + Address IPv4: " + prefix.address() + "/" + prefix.prefix());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка чтения IPv4 адресов", e);
        }

        try {
            RoutePrefixIterator v6AddrIter = options.getInet6Address();
            if (v6AddrIter != null) {
                while (v6AddrIter.hasNext()) {
                    RoutePrefix prefix = v6AddrIter.next();
                    builder.addAddress(prefix.address(), prefix.prefix());
                    hasIpv6Address = true;
                    Log.i(TAG, "  + Address IPv6: " + prefix.address() + "/" + prefix.prefix());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка чтения IPv6 адресов", e);
        }

        if (options.getAutoRoute()) {
            if (hasIpv4Address) {
                builder.addRoute("0.0.0.0", 0);
                Log.i(TAG, "  + Route IPv4: 0.0.0.0/0");
            }
            if (hasIpv6Address) {
                builder.addRoute("::", 0);
                Log.i(TAG, "  + Route IPv6: ::/0");
            }
        } else {
            try {
                RoutePrefixIterator v4RouteIter = options.getInet4RouteAddress();
                if (v4RouteIter != null) {
                    while (v4RouteIter.hasNext()) {
                        RoutePrefix prefix = v4RouteIter.next();
                        builder.addRoute(prefix.address(), prefix.prefix());
                        Log.i(TAG, "  + Custom Route IPv4: " + prefix.address() + "/" + prefix.prefix());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка чтения IPv4 маршрутов", e);
            }

            try {
                RoutePrefixIterator v6RouteIter = options.getInet6RouteAddress();
                if (v6RouteIter != null) {
                    while (v6RouteIter.hasNext()) {
                        RoutePrefix prefix = v6RouteIter.next();
                        builder.addRoute(prefix.address(), prefix.prefix());
                        Log.i(TAG, "  + Custom Route IPv6: " + prefix.address() + "/" + prefix.prefix());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка чтения IPv6 маршрутов", e);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RoutePrefixIterator v4ExcludeIter = options.getInet4RouteExcludeAddress();
                if (v4ExcludeIter != null) {
                    while (v4ExcludeIter.hasNext()) {
                        RoutePrefix prefix = v4ExcludeIter.next();
                        builder.excludeRoute(new IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()));
                        Log.i(TAG, "  + Exclude Route IPv4: " + prefix.address() + "/" + prefix.prefix());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка чтения IPv4 исключений маршрутов", e);
            }

            try {
                RoutePrefixIterator v6ExcludeIter = options.getInet6RouteExcludeAddress();
                if (v6ExcludeIter != null) {
                    while (v6ExcludeIter.hasNext()) {
                        RoutePrefix prefix = v6ExcludeIter.next();
                        builder.excludeRoute(new IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()));
                        Log.i(TAG, "  + Exclude Route IPv6: " + prefix.address() + "/" + prefix.prefix());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка чтения IPv6 исключений маршрутов", e);
            }
        }

        builder.addDnsServer("1.1.1.1");
        Log.i(TAG, "  + DNS Server: 1.1.1.1");

        try {
            StringIterator includeIter = options.getIncludePackage();
            if (includeIter != null) {
                while (includeIter.hasNext()) {
                    String pkg = includeIter.next();
                    builder.addAllowedApplication(pkg);
                    Log.i(TAG, "  + Allowed App: " + pkg);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка добавления разрешенных приложений", e);
        }

        try {
            StringIterator excludeIter = options.getExcludePackage();
            if (excludeIter != null) {
                while (excludeIter.hasNext()) {
                    String pkg = excludeIter.next();
                    builder.addDisallowedApplication(pkg);
                    Log.i(TAG, "  + Disallowed App: " + pkg);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка добавления исключенных приложений", e);
        }
      
        try {
            builder.addDisallowedApplication(getPackageName());
            Log.i(TAG, "  + Excluded self app: " + getPackageName());
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        tunFd = builder.establish();
        if (tunFd == null) {
            throw new Exception("VpnService.Builder.establish() вернул null");
        }

        int fd = tunFd.getFd();
        return fd;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public void autoDetectInterfaceControl(int fd) throws Exception {
        boolean protectedSocket = protect(fd);

        if (!protectedSocket) {
            Log.e(TAG, "CRITICAL: protect() failed, fd=" + fd);
            throw new Exception("VpnService.protect() failed for fd=" + fd);
        }
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        this.defaultInterfaceListener = listener;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        updateCurrentDefaultInterface(cm);

        if (networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateCurrentDefaultInterface(cm);
                }

                @Override
                public void onLost(Network network) {
                    updateCurrentDefaultInterface(cm);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                    updateCurrentDefaultInterface(cm);
                }
            };
            try {
                cm.registerDefaultNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Ошибка регистрации default network callback", e);
            }
        }
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (Exception ignored) {}
            }
            networkCallback = null;
        }
        this.defaultInterfaceListener = null;
        this.lastUnderlyingNetwork = null;
        this.lastInterfaceName = null;
    }

    private void updateCurrentDefaultInterface(ConnectivityManager cm) {
        if (defaultInterfaceListener == null) return;
        try {
            Network activeNetwork = cm.getActiveNetwork();
            NetworkCapabilities nc = cm.getNetworkCapabilities(activeNetwork);

            if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                activeNetwork = null;
                for (Network net : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null &&
                            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        activeNetwork = net;
                        nc = caps;
                        break;
                    }
                }
            }

            if (activeNetwork != null) {
                if (!activeNetwork.equals(lastUnderlyingNetwork)) {
                    try {
                        setUnderlyingNetworks(new Network[]{ activeNetwork });
                        lastUnderlyingNetwork = activeNetwork;
                    } catch (Exception e) {
                        Log.e(TAG, "CRITICAL: setUnderlyingNetworks failed", e);
                    }
                }

                LinkProperties lp = cm.getLinkProperties(activeNetwork);
                if (lp != null && lp.getInterfaceName() != null) {
                    String ifName = lp.getInterfaceName();
                    if (!ifName.startsWith("tun") && !ifName.startsWith("ppp") && !ifName.equals(lastInterfaceName)) {
                        int ifIndex = 0;
                        try {
                            java.net.NetworkInterface netIf = java.net.NetworkInterface.getByName(ifName);
                            if (netIf != null) {
                                ifIndex = netIf.getIndex();
                            }
                        } catch (Exception ignored) {}
                        boolean isExpensive = nc != null && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                        boolean isConstrained = nc != null && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);

                        lastInterfaceName = ifName;
                        Log.i(TAG, "SUCCESS: Notifying sing-box updateDefaultInterface(name=" + ifName + ", index=" + ifIndex + ")");
                        defaultInterfaceListener.updateDefaultInterface(ifName, ifIndex, isExpensive, isConstrained);
                    }
                }
            } else if (lastUnderlyingNetwork != null) {
                lastUnderlyingNetwork = null;
                lastInterfaceName = null;
                try {
                    setUnderlyingNetworks(null);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка обновления default interface", e);
        }
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() throws Exception {
        List<NetworkInterface> interfaceList = new ArrayList<>();
        try {
            Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    java.net.NetworkInterface netIf = interfaces.nextElement();
                    if (!netIf.isUp()) continue;

                    String name = netIf.getName();
                    if (name.startsWith("tun") ||
                            name.startsWith("lo") ||
                            name.startsWith("dummy") ||
                            name.startsWith("ppp")) {
                        continue;
                    }

                    NetworkInterface boxIf = new NetworkInterface();
                    boxIf.setName(netIf.getName());
                    boxIf.setIndex(netIf.getIndex());
                    boxIf.setMTU(netIf.getMTU());

                    List<String> addrList = new ArrayList<>();
                    List<InterfaceAddress> ifAddrs = netIf.getInterfaceAddresses();
                    if (ifAddrs != null) {
                        for (InterfaceAddress ifAddr : ifAddrs) {
                            InetAddress addr = ifAddr.getAddress();
                            if (addr == null || addr.isLoopbackAddress()) continue;

                            String host = addr.getHostAddress();
                            if (host == null) continue;

                            int percentIdx = host.indexOf('%');
                            if (percentIdx != -1) {
                                host = host.substring(0, percentIdx);
                            }

                            short prefixLen = ifAddr.getNetworkPrefixLength();
                            if (prefixLen <= 0) {
                                prefixLen = (addr instanceof java.net.Inet6Address) ? (short) 128 : (short) 32;
                            }

                            addrList.add(host + "/" + prefixLen);
                        }
                    }
                    final Iterator<String> addrIterator = addrList.iterator();
                    boxIf.setAddresses(new StringIterator() {
                        @Override
                        public boolean hasNext() {
                            return addrIterator.hasNext();
                        }

                        @Override
                        public int len() {
                            return addrList.size();
                        }

                        @Override
                        public String next() {
                            return addrIterator.next();
                        }
                    });

                    if (netIf.getName().startsWith("wlan") || netIf.getName().startsWith("wifi")) {
                        boxIf.setType(Libbox.InterfaceTypeWIFI);
                    } else if (netIf.getName().startsWith("rmnet") || netIf.getName().startsWith("ccmni") || netIf.getName().startsWith("pdp")) {
                        boxIf.setType(Libbox.InterfaceTypeCellular);
                    } else if (netIf.getName().startsWith("eth")) {
                        boxIf.setType(Libbox.InterfaceTypeEthernet);
                    } else {
                        boxIf.setType(Libbox.InterfaceTypeOther);
                    }

                    interfaceList.add(boxIf);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка получения списка сетевых интерфейсов", e);
        }

        final Iterator<NetworkInterface> iterator = interfaceList.iterator();
        return new NetworkInterfaceIterator() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public NetworkInterface next() {
                return iterator.next();
            }
        };
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean usePlatformAutoRedirect() {
        return false;
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public void checkPlatformShell() throws Exception {
        Log.d(TAG, "checkPlatformShell() вызван");
    }

    @Override
    public void clearDNSCache() {
        Log.d(TAG, "clearDNSCache() вызван");
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) throws Exception {
        Log.d(TAG, "closeNeighborMonitor() вызван");
    }

    @Override
    public AutoRedirectSession createAutoRedirect(byte[] options, AutoRedirectHandler handler) throws Exception {
        Log.d(TAG, "createAutoRedirect() вызван");
        throw new Exception("auto redirect not supported");
    }

    @Override
    public BridgeSession createBridge(BridgeOptions options) throws Exception {
        Log.d(TAG, "createBridge() вызван");
        throw new Exception("bridge not supported");
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort, String destinationAddress, int destinationPort) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    InetSocketAddress source = new InetSocketAddress(sourceAddress, sourcePort);
                    InetSocketAddress destination = new InetSocketAddress(destinationAddress, destinationPort);
                    int uid = cm.getConnectionOwnerUid(ipProtocol, source, destination);
                    if (uid != android.os.Process.INVALID_UID) {
                        ConnectionOwner owner = new ConnectionOwner();
                        owner.setUserId(uid);
                        String[] packages = getPackageManager().getPackagesForUid(uid);
                        if (packages != null && packages.length > 0) {
                            owner.setUserName(packages[0]);
                            final String[] finalPackages = packages;
                            owner.setAndroidPackageNames(new StringIterator() {
                                private int index = 0;

                                @Override
                                public boolean hasNext() {
                                    return index < finalPackages.length;
                                }

                                @Override
                                public int len() {
                                    return finalPackages.length;
                                }

                                @Override
                                public String next() {
                                    return finalPackages[index++];
                                }
                            });
                        }
                        return owner;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "getConnectionOwnerUid exception: " + e.getMessage());
                }
            }
        }
        throw new Exception("connection owner not found");
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public String lookupSFTPServer() throws Exception {
        return "";
    }

    @Override
    public PlatformUser lookupUser(String username) throws Exception {
        throw new Exception("user not found");
    }

    @Override
    public ShellSession openShellSession(PlatformUser user, String command, StringIterator environ, String term, int rows, int cols) throws Exception {
        throw new Exception("shell session not supported");
    }

    @Override
    public String readSystemSSHHostKey() throws Exception {
        return "";
    }

    @Override
    public WIFIState readWIFIState() {
        return Libbox.newWIFIState("", "");
    }

    @Override
    public void registerMyInterface(String name) {
        Log.d(TAG, "registerMyInterface(" + name + ") вызван");
    }

    @Override
    public void sendNotification(io.nekohasekai.libbox.Notification notification) throws Exception {
        Log.d(TAG, "sendNotification() вызван");
    }

    @Override
    public void cancelNotification(String identifier, int typeID) throws Exception {
        Log.d(TAG, "cancelNotification(id=" + identifier + ", type=" + typeID + ") вызван");
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) throws Exception {
        Log.d(TAG, "startNeighborMonitor() вызван");
    }

    @Override
    public String tailscaleHostname() {
        return "";
    }

    @Override
    public int connectSSHAgent() throws Exception {
        return 0;
    }

    @Override
    public SystemProxyStatus getSystemProxyStatus() throws Exception {
        return null;
    }

    @Override
    public void serviceReload() throws Exception {
        Log.d(TAG, "serviceReload() вызван");
    }

    @Override
    public void serviceStop() throws Exception {
        Log.d(TAG, "serviceStop() вызван sing-box");
        stopVpn();
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) throws Exception {
        Log.d(TAG, "setSystemProxyEnabled(" + enabled + ") вызван");
    }

    @Override
    public void triggerNativeCrash() throws Exception {
        Log.e(TAG, "triggerNativeCrash() вызван!");
    }

    @Override
    public void writeDebugMessage(String message) {
        Log.i("sing-box", message);
    }
}
