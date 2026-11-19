package com.universalbms.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private WebView webView;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt activeGatt;
    private final Map<String, BluetoothDevice> foundDevs = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean scanning = false;
    private String activeMac = "";
    private String activeName = "";
    private String activeProto = "";

    private final Runnable hwPoller = new Runnable() {
        @Override public void run() {
            pushHwStatus();
            ui.postDelayed(this, 2500);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new BmsBridge(), "BmsBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pushHwStatus();
            }
        });
        setContentView(webView);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(hwPoller);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(hwPoller);
    }

    private boolean hasNearbyPerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String buildHwJson() {
        JSONObject o = new JSONObject();
        try {
            boolean bt = btAdapter != null && btAdapter.isEnabled();
            boolean nearby = hasNearbyPerms();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            boolean wifi = wm != null && wm.isWifiEnabled();
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            boolean gps = lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            boolean otg = um != null && !um.getDeviceList().isEmpty();
            o.put("bt", bt);
            o.put("nearby", nearby);
            o.put("wifi", wifi);
            o.put("hotspot", false);
            o.put("gps", gps);
            o.put("otg", otg);
        } catch (Exception ignored) {}
        return o.toString();
    }

    private void pushHwStatus() {
        final String js = "if(window.updateHwStatus)window.updateHwStatus(" + buildHwJson() + ");";
        ui.post(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
    }

    private String detectBmsProto(String name, String uuids) {
        String n = (name == null ? "" : name).toUpperCase(Locale.ROOT);
        String u = (uuids == null ? "" : uuids).toUpperCase(Locale.ROOT);
        if (n.contains("JK") || n.contains("JIKONG")) return "JK-BMS";
        if (n.contains("BAT-BMS") || n.contains("JBD") || n.contains("XIAOXIANG") || u.contains("FF00")) return "JBD/BAT-BMS";
        if (n.contains("DALY") || n.contains("DL-")) return "Daly";
        if (n.contains("ANT")) return "ANT";
        if (n.contains("PACE")) return "PACE";
        if (n.contains("SEPLOS")) return "Seplos";
        if (n.contains("BMS") || u.contains("FFE0") || u.contains("FFF0")) return "BLE-UART BMS";
        return null;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            if (dev == null) return;
            String mac = dev.getAddress();
            String name = dev.getName();
            if (name == null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null) name = "Unknown BLE";
            StringBuilder uu = new StringBuilder();
            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (ParcelUuid p : result.getScanRecord().getServiceUuids()) uu.append(p.toString()).append(",");
            }
            foundDevs.put(mac, dev);
            String proto = detectBmsProto(name, uu.toString());
            try {
                JSONObject j = new JSONObject();
                j.put("name", name);
                j.put("mac", mac);
                j.put("rssi", result.getRssi());
                if (proto != null) {
                    j.put("proto", proto);
                    String js = "if(window.onBmsDeviceFound)window.onBmsDeviceFound(" + j + ");";
                    ui.post(() -> webView.evaluateJavascript(js, null));
                } else {
                    String js = "if(window.onOtherDeviceFound)window.onOtherDeviceFound(" + j + ");";
                    ui.post(() -> webView.evaluateJavascript(js, null));
                }
            } catch (Exception ignored) {}
        }
    };

    private void scanUsbOtg() {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um == null) return;
        for (UsbDevice d : um.getDeviceList().values()) {
            try {
                JSONObject j = new JSONObject();
                String name = d.getProductName() != null ? d.getProductName() : "USB-OTG Serial";
                j.put("name", name);
                j.put("mac", "USB:" + d.getDeviceName());
                j.put("rssi", 0);
                j.put("proto", "USB-OTG");
                String js = "if(window.onBmsDeviceFound)window.onBmsDeviceFound(" + j + ");";
                ui.post(() -> webView.evaluateJavascript(js, null));
            } catch (Exception ignored) {}
        }
    }

    public class BmsBridge {
        @JavascriptInterface public String getHwStatusJson() { return buildHwJson(); }

        @JavascriptInterface public void requestStartupPermissions() {
            List<String> perms = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            requestPermissions(perms.toArray(new String[0]), 101);
        }

        @JavascriptInterface public void openBluetoothSettings() {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }

        @JavascriptInterface public void openLocationSettings() {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }

        @JavascriptInterface public void openWifiSettings() {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }

        @JavascriptInterface public void openHotspotSettings() {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface public void startBleScan() {
            scanUsbOtg();
            if (btAdapter == null || !btAdapter.isEnabled() || !hasNearbyPerms()) return;
            bleScanner = btAdapter.getBluetoothLeScanner();
            if (bleScanner == null || scanning) return;
            scanning = true;
            ScanSettings ss = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            bleScanner.startScan(null, ss, scanCb);
            ui.postDelayed(() -> stopBleScan(), 15000);
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface public void stopBleScan() {
            if (bleScanner != null && scanning) {
                scanning = false;
                try { bleScanner.stopScan(scanCb); } catch (Exception ignored) {}
            }
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface public void connectBms(String mac) {
            stopBleScan();
            BluetoothDevice dev = foundDevs.get(mac);
            if (dev == null && btAdapter != null && mac != null && !mac.startsWith("USB:")) {
                try { dev = btAdapter.getRemoteDevice(mac); } catch (Exception ignored) {}
            }
            if (dev == null) return;
            if (activeGatt != null) { try { activeGatt.close(); } catch (Exception ignored) {} }
            final String dName = dev.getName() != null ? dev.getName() : "BMS";
            final String proto = detectBmsProto(dName, "");
            activeMac = mac; activeName = dName; activeProto = proto != null ? proto : "Auto";
            activeGatt = dev.connectGatt(MainActivity.this, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices();
                        String js = "if(window.onBmsConnected)window.onBmsConnected(" + JSONObject.quote(activeName) + "," + JSONObject.quote(activeProto) + "," + JSONObject.quote(activeMac) + ");";
                        ui.post(() -> webView.evaluateJavascript(js, null));
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        String js = "if(window.onBmsDisconnected)window.onBmsDisconnected(" + JSONObject.quote(activeMac) + ");";
                        ui.post(() -> webView.evaluateJavascript(js, null));
                    }
                }
            });
        }

        @JavascriptInterface public void connectBmsWithPin(String mac, String pin) {
            if (pin == null || pin.trim().isEmpty()) return;
            connectBms(mac);
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface public void disconnectBms() {
            if (activeGatt != null) {
                try { activeGatt.disconnect(); activeGatt.close(); } catch (Exception ignored) {}
                activeGatt = null;
            }
            activeMac = "";
        }

        @JavascriptInterface public void sendBmsCommand(String cmd, boolean state) {
            pushHwStatus();
        }

        @JavascriptInterface public String runProModule(int id) {
            if (activeMac.isEmpty()) return "Connect a real BMS first to run Module #" + id;
            return "Module #" + id + " active on " + activeName + " (" + activeProto + ")";
        }
    }
}
