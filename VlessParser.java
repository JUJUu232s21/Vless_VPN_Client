import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VlessParser {

    public static String parseToSingBoxConfig(String vlessUrl) throws Exception {
        if (vlessUrl == null || !vlessUrl.trim().startsWith("vless://")) {
            throw new IllegalArgumentException("Ссылка должна начинаться с vless://");
        }

        String mainPart = vlessUrl.trim().substring(8);
        int hashIdx = mainPart.indexOf('#');
        if (hashIdx != -1) {
            mainPart = mainPart.substring(0, hashIdx);
        }

        int atIdx = mainPart.indexOf('@');
        if (atIdx == -1) {
            throw new IllegalArgumentException("Неверный формат vless: отсутствует @");
        }

        String uuid = mainPart.substring(0, atIdx);
        String rest = mainPart.substring(atIdx + 1);

        String hostAndPort;
        Map<String, String> queryParams = new HashMap<>();

        int queryIdx = rest.indexOf('?');
        if (queryIdx != -1) {
            hostAndPort = rest.substring(0, queryIdx);
            String queryString = rest.substring(queryIdx + 1);
            String[] pairs = queryString.split("&");
            for (String pair : pairs) {
                int eqIdx = pair.indexOf('=');
                if (eqIdx != -1) {
                    String key = pair.substring(0, eqIdx);
                    String value = pair.substring(eqIdx + 1);
                    try {
                        value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
                    } catch (Exception ignored) {}
                    queryParams.put(key, value);
                }
            }
        } else {
            hostAndPort = rest;
        }

        String serverHost;
        int serverPort = 443;

        int colonIdx = hostAndPort.lastIndexOf(':');
        if (colonIdx != -1) {
            serverHost = hostAndPort.substring(0, colonIdx);
            try {
                serverPort = Integer.parseInt(hostAndPort.substring(colonIdx + 1));
            } catch (NumberFormatException ignored) {}
        } else {
            serverHost = hostAndPort;
        }

        if (TextUtils.isEmpty(serverHost) || TextUtils.isEmpty(uuid)) {
            throw new IllegalArgumentException("Имя сервера или UUID не могут быть пустыми");
        }

        String flow = queryParams.get("flow");
        String security = queryParams.get("security");
        String fp = queryParams.get("fp");
        String sni = queryParams.get("sni");
        String pbk = queryParams.get("pbk");
        String sid = queryParams.get("sid");
        String transportType = queryParams.get("type");

        JSONObject vlessOutbound = new JSONObject();
        vlessOutbound.put("type", "vless");
        vlessOutbound.put("tag", "proxy");
        vlessOutbound.put("server", serverHost);
        vlessOutbound.put("server_port", serverPort);
        vlessOutbound.put("uuid", uuid);

        if (!TextUtils.isEmpty(flow)) {
            vlessOutbound.put("flow", flow);
        }

        if ("reality".equalsIgnoreCase(security) || "tls".equalsIgnoreCase(security)) {
            JSONObject tlsObj = new JSONObject();
            tlsObj.put("enabled", true);

            if (!TextUtils.isEmpty(sni)) {
                tlsObj.put("server_name", sni);
            }

            if (!TextUtils.isEmpty(fp)) {
                JSONObject utlsObj = new JSONObject();
                utlsObj.put("enabled", true);
                utlsObj.put("fingerprint", fp);
                tlsObj.put("utls", utlsObj);
            }

            if ("reality".equalsIgnoreCase(security)) {
                JSONObject realityObj = new JSONObject();
                realityObj.put("enabled", true);
                if (!TextUtils.isEmpty(pbk)) {
                    realityObj.put("public_key", pbk);
                }
                if (!TextUtils.isEmpty(sid)) {
                    realityObj.put("short_id", sid);
                }
                tlsObj.put("reality", realityObj);
            }

            vlessOutbound.put("tls", tlsObj);
        }

        if (!TextUtils.isEmpty(transportType) && !"tcp".equalsIgnoreCase(transportType)) {
            JSONObject transportObj = new JSONObject();
            transportObj.put("type", transportType.toLowerCase());

            String path = queryParams.get("path");
            if (!TextUtils.isEmpty(path)) {
                transportObj.put("path", path);
            }
            String host = queryParams.get("host");
            if (!TextUtils.isEmpty(host)) {
                JSONObject headers = new JSONObject();
                headers.put("Host", host);
                transportObj.put("headers", headers);
            }
            String serviceName = queryParams.get("serviceName");
            if (!TextUtils.isEmpty(serviceName)) {
                transportObj.put("service_name", serviceName);
            }
            vlessOutbound.put("transport", transportObj);
        }

        JSONObject directOutbound = new JSONObject();
        directOutbound.put("type", "direct");
        directOutbound.put("tag", "direct");

        JSONArray outbounds = new JSONArray();
        outbounds.put(vlessOutbound);
        outbounds.put(directOutbound);

        JSONArray rules = new JSONArray();

        JSONObject dnsRule = new JSONObject();
        dnsRule.put("protocol", "dns");
        dnsRule.put("action", "hijack-dns");
        rules.put(dnsRule);

        JSONObject serverRule = new JSONObject();
        if (Character.isDigit(serverHost.charAt(0)) || serverHost.contains(":")) {
            JSONArray ipCidr = new JSONArray();
            ipCidr.put(serverHost + (serverHost.contains(":") ? "/128" : "/32"));
            serverRule.put("ip_cidr", ipCidr);
        } else {
            JSONArray domainArr = new JSONArray();
            domainArr.put(serverHost);
            serverRule.put("domain", domainArr);
        }
        serverRule.put("outbound", "direct");
        rules.put(serverRule);

        if (!TextUtils.isEmpty(sni) && !sni.equalsIgnoreCase(serverHost)) {
            JSONObject sniRule = new JSONObject();
            JSONArray domainArr = new JSONArray();
            domainArr.put(sni);
            sniRule.put("domain", domainArr);
            sniRule.put("outbound", "direct");
            rules.put(sniRule);
        }

        JSONObject routeObj = new JSONObject();
        routeObj.put("rules", rules);
        routeObj.put("final", "proxy");

        JSONObject dnsRemoteServer = new JSONObject();
        dnsRemoteServer.put("tag", "dns-remote");
        dnsRemoteServer.put("type", "https");
        dnsRemoteServer.put("server", "1.1.1.1");

        JSONArray dnsServers = new JSONArray();
        dnsServers.put(dnsRemoteServer);

        JSONObject dnsObj = new JSONObject();
        dnsObj.put("servers", dnsServers);
        dnsObj.put("strategy", "ipv4_only");
        dnsObj.put("final", "dns-remote");

        JSONObject tunInbound = new JSONObject();
        tunInbound.put("type", "tun");
        tunInbound.put("tag", "tun-in");
        JSONArray tunAddresses = new JSONArray();
        tunAddresses.put("172.19.0.1/30");
        tunAddresses.put("fdfe:dcba:9876::1/126");
        tunInbound.put("address", tunAddresses);
        tunInbound.put("mtu", 1280);
        tunInbound.put("auto_route", true);
        tunInbound.put("stack", "gvisor");

        JSONArray inbounds = new JSONArray();
        inbounds.put(tunInbound);

        JSONObject logObj = new JSONObject();
        logObj.put("level", "info");
        logObj.put("timestamp", true);

        JSONObject root = new JSONObject();
        root.put("log", logObj);
        root.put("dns", dnsObj);
        root.put("inbounds", inbounds);
        root.put("outbounds", outbounds);
        root.put("route", routeObj);

        return root.toString(2);
    }

    public static List<String> parseDetails(String vlessUrl) {
        List<String> details = new ArrayList<>();
        if (vlessUrl == null || !vlessUrl.trim().startsWith("vless://")) {
            return details;
        }
        try {
            String mainPart = vlessUrl.trim().substring(8);
            String tag = "";
            int hashIdx = mainPart.indexOf('#');
            if (hashIdx != -1) {
                tag = mainPart.substring(hashIdx + 1);
                mainPart = mainPart.substring(0, hashIdx);
            }

            int atIdx = mainPart.indexOf('@');
            if (atIdx == -1) return details;

            String uuid = mainPart.substring(0, atIdx);
            String rest = mainPart.substring(atIdx + 1);

            String hostAndPort;
            Map<String, String> queryParams = new HashMap<>();

            int queryIdx = rest.indexOf('?');
            if (queryIdx != -1) {
                hostAndPort = rest.substring(0, queryIdx);
                String queryString = rest.substring(queryIdx + 1);
                for (String pair : queryString.split("&")) {
                    int eqIdx = pair.indexOf('=');
                    if (eqIdx != -1) {
                        String key = pair.substring(0, eqIdx);
                        String value = pair.substring(eqIdx + 1);
                        try {
                            value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
                        } catch (Exception ignored) {}
                        queryParams.put(key, value);
                    }
                }
            } else {
                hostAndPort = rest;
            }

            String serverHost;
            int serverPort = 443;
            int colonIdx = hostAndPort.lastIndexOf(':');
            if (colonIdx != -1) {
                serverHost = hostAndPort.substring(0, colonIdx);
                try {
                    serverPort = Integer.parseInt(hostAndPort.substring(colonIdx + 1));
                } catch (NumberFormatException ignored) {}
            } else {
                serverHost = hostAndPort;
            }

            details.add("IP: " + serverHost);
            details.add("Port: " + serverPort);
            details.add("UUID: " + uuid);
            if (!tag.isEmpty()) details.add("Name: " + tag);

            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                details.add(entry.getKey().toUpperCase() + ": " + entry.getValue());
            }
        } catch (Exception e) {
            details.add("Ошибка парсинга: " + e.getMessage());
        }
        return details;
    }

    public static String parseServerName(String vlessUrl) {
        if (vlessUrl == null || !vlessUrl.trim().startsWith("vless://")) {
            return "";
        }
        try {
            int hashIdx = vlessUrl.indexOf('#');
            if (hashIdx != -1) {
                String tag = vlessUrl.substring(hashIdx + 1).trim();
                if (!tag.isEmpty()) {
                    return tag;
                }
            }
            String mainPart = vlessUrl.trim().substring(8);
            int atIdx = mainPart.indexOf('@');
            if (atIdx != -1) {
                return mainPart.substring(atIdx + 1).split("\\?")[0];
            }
        } catch (Exception ignored) {}
        return "";
    }
}
