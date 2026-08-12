package com.ggpark.byddashcam;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public final class PhoneAccessNetwork {
    private PhoneAccessNetwork() {
    }

    public static String findLocalIpv4Address() throws IOException {
        Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            throw new IOException("No local network is available");
        }
        List<InetAddress> fallbackAddresses = new ArrayList<>();
        for (NetworkInterface networkInterface : Collections.list(interfaces)) {
            if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            for (InetAddress address :
                    Collections.list(networkInterface.getInetAddresses())) {
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                    continue;
                }
                if (address.isSiteLocalAddress()) {
                    return address.getHostAddress();
                }
                fallbackAddresses.add(address);
            }
        }
        if (!fallbackAddresses.isEmpty()) {
            return fallbackAddresses.get(0).getHostAddress();
        }
        throw new IOException("Connect this device and phone to the same Wi-Fi network");
    }

    public static String getWifiName(Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(
                        Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            if (activeNetwork != null
                    && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
                String networkName = normalizeWifiName(activeNetwork.getExtraInfo());
                if (!networkName.isEmpty()) {
                    return networkName;
                }
            }
        }
        WifiManager manager =
                (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
        if (manager == null) {
            return "";
        }
        WifiInfo info = manager.getConnectionInfo();
        if (info == null) {
            return "";
        }
        return normalizeWifiName(info.getSSID());
    }

    private static String normalizeWifiName(String value) {
        if (value == null
                || value.isEmpty()
                || "<unknown ssid>".equalsIgnoreCase(value)) {
            return "";
        }
        if (value.length() >= 2
                && value.startsWith("\"")
                && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
