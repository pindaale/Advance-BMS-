package com.universalbms.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    private static final String APP_VERSION = "4.0.0";
    private static final int REQ_PERMS = 1001;
    private static final int REQ_ENABLE_BT = 1002;
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt gatt;
    private BluetoothSocket sppSocket;
    private OutputStream sppOut;
    private volatile boolean sppRunning = false;

    private volatile boolean autoScanActive = false;
    private volatile boolean bleScanning = false;
    private String connMode = "NONE";
    private String connMac = "";
    private String connName = "";
    private int currentMtu = 23;
    private int lastRssi = 0;

    private static class GattOp {
        static final int MTU = 1, DISCOVER = 2, WRITE_DESC = 3, READ_CHAR = 4, WRITE_CHAR = 5, RSSI = 6;
        int type;
        BluetoothGattCharacteristic ch;
        BluetoothGattDescriptor desc;
        byte[] data;
        int writeType;
        boolean retried;
        GattOp(int type) { this.type = type; }
    }

    private final Queue<GattOp> gattQueue = new ConcurrentLinkedQueue<>();
    private GattOp activeOp = null;
    private final Runnable opTimeoutRunnable = () -> {
        if (activeOp != null) {
            emitLog("GATT op timeout (" + activeOp.type + "), advancing queue");
            activeOp = null;
            processNextGattOp();
        }
    };

    private final Runnable periodicScanRunnable = new Runnable() {
        @Override public void run() {
            if (!autoScanActive) return;
            emitBondedAndUsb();
            if (btAdapter != null && btAdapter.isEnabled() && hasBtPerms()) {
                try {
                    if (!btAdapter.isDiscovering()) btAdapter.startDiscovery();
                } catch (Throwable ignored) {}
            }
            mainHandler.postDelayed(this, 12000);
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) -80);
                if (dev != null) emitDevice(dev, rssi, "CLASSIC", null);
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (dev != null) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", "bond_state");
                        o.put("mac", dev.getAddress());
                        o.put("state", dev.getBondState());
                        sendJsEvent(o);
                    } catch (Throwable ignored) {}
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                emitStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        setContentView(webView);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(btReceiver, f);
        }

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        stopAllScans();
        disconnectInternal();
        try { unregisterReceiver(btReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private boolean hasBtPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAllPerms() {
        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            req.add(Manifest.permission.BLUETOOTH_SCAN);
            req.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        req.add(Manifest.permission.ACCESS_FINE_LOCATION);
        req.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        requestPermissions(req.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        emitStatus();
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
    private void startAllScans() {
        if (!hasBtPerms()) { requestAllPerms(); return; }
        autoScanActive = true;
        emitBondedAndUsb();
        if (btAdapter != null && btAdapter.isEnabled()) {
            bleScanner = btAdapter.getBluetoothLeScanner();
            if (bleScanner != null && !bleScanning) {
                ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
                bleScanner.startScan(null, settings, bleCallback);
                bleScanning = true;
            }
            mainHandler.removeCallbacks(periodicScanRunnable);
            mainHandler.post(periodicScanRunnable);
        }
        emitStatus();
    }

    private void stopAllScans() {
        autoScanActive = false;
        mainHandler.removeCallbacks(periodicScanRunnable);
        if (bleScanner != null && bleScanning) {
            try { bleScanner.stopScan(bleCallback); } catch (Throwable ignored) {}
        }
        bleScanning = false;
        if (btAdapter != null) {
            try { if (btAdapter.isDiscovering()) btAdapter.cancelDiscovery(); } catch (Throwable ignored) {}
        }
        emitStatus();
    }

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            List<String> uuids = new ArrayList<>();
            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (ParcelUuid pu : result.getScanRecord().getServiceUuids()) {
                    uuids.add(pu.getUuid().toString());
                }
            }
            emitDevice(result.getDevice(), result.getRssi(), "BLE", uuids);
        }
    };

    private void emitBondedAndUsb() {
        if (btAdapter != null && hasBtPerms()) {
            try {
                for (BluetoothDevice d : btAdapter.getBondedDevices()) {
                    emitDevice(d, -55, "BONDED", null);
                }
            } catch (Throwable ignored) {}
        }
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um != null) {
            for (UsbDevice ud : um.getDeviceList().values()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "device");
                    o.put("mac", "USB:" + ud.getVendorId() + ":" + ud.getProductId());
                    String nm = ud.getProductName() != null ? ud.getProductName() : ("USB-OTG " + ud.getDeviceName());
                    o.put("name", nm);
                    o.put("rssi", 0);
                    o.put("transport", "USB");
                    o.put("bonded", true);
                    o.put("uuids", new JSONArray());
                    sendJsEvent(o);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void emitDevice(BluetoothDevice dev, int rssi, String transport, List<String> uuids) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "device");
            o.put("mac", dev.getAddress());
            String nm = dev.getName();
            o.put("name", (nm != null && !nm.isEmpty()) ? nm : "Unknown Device");
            o.put("rssi", rssi);
            o.put("transport", transport);
            o.put("bonded", dev.getBondState() == BluetoothDevice.BOND_BONDED);
            JSONArray arr = new JSONArray();
            if (uuids != null) for (String u : uuids) arr.put(u);
            o.put("uuids", arr);
            sendJsEvent(o);
        } catch (Throwable ignored) {}
    }

    private void connectBle(String mac) {
        if (!hasBtPerms() || btAdapter == null) return;
        disconnectInternal();
        BluetoothDevice dev = btAdapter.getRemoteDevice(mac);
        connMac = mac;
        connName = dev.getName() != null ? dev.getName() : mac;
        connMode = "BLE_CONNECTING";
        emitStatus();
        gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void connectSpp(String mac) {
        if (!hasBtPerms() || btAdapter == null) return;
        disconnectInternal();
        connMac = mac;
        BluetoothDevice dev = btAdapter.getRemoteDevice(mac);
        connName = dev.getName() != null ? dev.getName() : mac;
        connMode = "SPP_CONNECTING";
        emitStatus();
        new Thread(() -> {
            try {
                if (btAdapter.isDiscovering()) btAdapter.cancelDiscovery();
                sppSocket = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                sppSocket.connect();
                sppOut = sppSocket.getOutputStream();
                sppRunning = true;
                connMode = "SPP";
                emitStatus();
                emitLog("SPP connected: " + connMac);
                InputStream in = sppSocket.getInputStream();
                byte[] buf = new byte[1024];
                while (sppRunning) {
                    int n = in.read(buf);
                    if (n > 0) {
                        byte[] pkt = Arrays.copyOf(buf, n);
                        emitPacket("RX", "SPP", pkt);
                    } else if (n < 0) break;
                }
            } catch (Throwable e) {
                emitLog("SPP error: " + e.getMessage());
            } finally {
                sppRunning = false;
                if ("SPP".equals(connMode) || "SPP_CONNECTING".equals(connMode)) {
                    connMode = "NONE";
                    emitStatus();
                }
            }
        }).start();
    }

    private void disconnectInternal() {
        gattQueue.clear();
        activeOp = null;
        mainHandler.removeCallbacks(opTimeoutRunnable);
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
        sppRunning = false;
        if (sppSocket != null) {
            try { sppSocket.close(); } catch (Throwable ignored) {}
            sppSocket = null;
            sppOut = null;
        }
        connMode = "NONE";
        connMac = "";
        connName = "";
        currentMtu = 23;
        emitStatus();
    }

    private synchronized void enqueueOp(GattOp op) {
        gattQueue.add(op);
        if (activeOp == null) processNextGattOp();
    }

    private synchronized void completeOp() {
        mainHandler.removeCallbacks(opTimeoutRunnable);
        activeOp = null;
        processNextGattOp();
    }

    private synchronized void processNextGattOp() {
        if (activeOp != null || gatt == null) return;
        GattOp op = gattQueue.poll();
        if (op == null) return;
        activeOp = op;
        mainHandler.postDelayed(opTimeoutRunnable, 3000);
        boolean ok = false;
        try {
            switch (op.type) {
                case GattOp.MTU:
                    ok = gatt.requestMtu(512);
                    break;
                case GattOp.DISCOVER:
                    ok = gatt.discoverServices();
                    break;
                case GattOp.RSSI:
                    ok = gatt.readRemoteRssi();
                    break;
                case GattOp.READ_CHAR:
                    ok = gatt.readCharacteristic(op.ch);
                    break;
                case GattOp.WRITE_DESC:
                    if (Build.VERSION.SDK_INT >= 33) {
                        ok = gatt.writeDescriptor(op.desc, op.data) == BluetoothStatusCodes.SUCCESS;
                    } else {
                        op.desc.setValue(op.data);
                        ok = gatt.writeDescriptor(op.desc);
                    }
                    break;
                case GattOp.WRITE_CHAR:
                    ok = execWriteChar(op);
                    if (!ok && !op.retried) {
                        op.retried = true;
                        op.writeType = (op.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                            ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                        ok = execWriteChar(op);
                    }
                    break;
            }
        } catch (Throwable e) {
            emitLog("GATT exec error: " + e.getMessage());
            ok = false;
        }
        if (!ok) {
            emitLog("GATT op failed immediately: " + op.type);
            completeOp();
        }
    }

    private boolean execWriteChar(GattOp op) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeCharacteristic(op.ch, op.data, op.writeType) == BluetoothStatusCodes.SUCCESS;
        } else {
            op.ch.setWriteType(op.writeType);
            op.ch.setValue(op.data);
            return gatt.writeCharacteristic(op.ch);
        }
    }
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connMode = "BLE";
                emitStatus();
                emitLog("BLE connected, queuing MTU 512 & service discovery");
                gattQueue.clear();
                activeOp = null;
                enqueueOp(new GattOp(GattOp.MTU));
                enqueueOp(new GattOp(GattOp.DISCOVER));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emitLog("BLE disconnected (status " + status + ")");
                mainHandler.post(() -> disconnectInternal());
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            currentMtu = mtu;
            emitStatus();
            emitLog("MTU=" + mtu + " status=" + status);
            completeOp();
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            emitLog("Services discovered: status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS && g != null) {
                for (BluetoothGattService svc : g.getServices()) {
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        int props = ch.getProperties();
                        boolean canNotify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                        boolean canIndicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                        if (canNotify || canIndicate) {
                            g.setCharacteristicNotification(ch, true);
                            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
                            if (cccd != null) {
                                GattOp op = new GattOp(GattOp.WRITE_DESC);
                                op.desc = cccd;
                                op.data = canIndicate
                                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                                enqueueOp(op);
                            }
                        }
                    }
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "services");
                    o.put("services", buildGattJson());
                    sendJsEvent(o);
                } catch (Throwable ignored) {}
            }
            completeOp();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            emitLog("CCCD write " + d.getCharacteristic().getUuid() + " status=" + status);
            completeOp();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS && activeOp != null && activeOp.type == GattOp.WRITE_CHAR && !activeOp.retried) {
                activeOp.retried = true;
                activeOp.writeType = (activeOp.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                emitLog("Write status=" + status + ", retrying with alternate writeType=" + activeOp.writeType);
                if (execWriteChar(activeOp)) return;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("type", "ack");
                o.put("uuid", ch.getUuid().toString());
                o.put("status", status);
                sendJsEvent(o);
            } catch (Throwable ignored) {}
            completeOp();
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            handleRead(ch, ch.getValue(), status);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value, int status) {
            handleRead(ch, value, status);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            handleNotify(ch, ch.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            handleNotify(ch, value);
        }

        @Override public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastRssi = rssi;
                emitStatus();
            }
            completeOp();
        }
    };

    private void handleRead(BluetoothGattCharacteristic ch, byte[] val, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS && val != null) {
            emitPacket("RX", ch.getUuid().toString(), val);
        }
        completeOp();
    }

    private void handleNotify(BluetoothGattCharacteristic ch, byte[] val) {
        if (val != null && val.length > 0) {
            emitPacket("RX", ch.getUuid().toString(), val);
        }
    }

    private BluetoothGattCharacteristic findCharacteristic(String svcUuid, String charUuid) {
        if (gatt == null) return null;
        if (svcUuid != null && !svcUuid.isEmpty()) {
            BluetoothGattService svc = gatt.getService(UUID.fromString(svcUuid));
            if (svc != null) return svc.getCharacteristic(UUID.fromString(charUuid));
        }
        for (BluetoothGattService s : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if (c.getUuid().toString().equalsIgnoreCase(charUuid)) return c;
            }
        }
        return null;
    }

    private JSONArray buildGattJson() {
        JSONArray arr = new JSONArray();
        if (gatt == null) return arr;
        for (BluetoothGattService svc : gatt.getServices()) {
            try {
                JSONObject so = new JSONObject();
                so.put("uuid", svc.getUuid().toString());
                JSONArray chars = new JSONArray();
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    JSONObject co = new JSONObject();
                    co.put("uuid", ch.getUuid().toString());
                    int p = ch.getProperties();
                    co.put("read", (p & BluetoothGattCharacteristic.PROPERTY_READ) != 0);
                    co.put("write", (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0);
                    co.put("writeNoResp", (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0);
                    co.put("notify", (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0);
                    co.put("indicate", (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0);
                    chars.put(co);
                }
                so.put("characteristics", chars);
                arr.put(so);
            } catch (Throwable ignored) {}
        }
        return arr;
    }

    private void emitStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "status");
            o.put("data", new JSONObject(getStatusJson()));
            sendJsEvent(o);
        } catch (Throwable ignored) {}
    }

    private String getStatusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", APP_VERSION);
            o.put("btSupported", btAdapter != null);
            o.put("btEnabled", btAdapter != null && btAdapter.isEnabled());
            o.put("bleSupported", getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
            o.put("locationEnabled", isLocationEnabled());
            o.put("permissionsGranted", hasBtPerms());
            o.put("scanning", autoScanActive);
            o.put("connected", "BLE".equals(connMode) || "SPP".equals(connMode));
            o.put("connMode", connMode);
            o.put("deviceMac", connMac);
            o.put("deviceName", connName);
            o.put("mtu", currentMtu);
            o.put("rssi", lastRssi);
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            o.put("usbCount", um != null ? um.getDeviceList().size() : 0);
            return o.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    private void emitPacket(String dir, String ch, byte[] data) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "packet");
            o.put("dir", dir);
            o.put("channel", ch);
            o.put("hex", bytesToHex(data));
            o.put("ascii", new String(data, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7E]", "."));
            sendJsEvent(o);
        } catch (Throwable ignored) {}
    }

    private void emitLog(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "log");
            o.put("msg", msg);
            sendJsEvent(o);
        } catch (Throwable ignored) {}
    }

    private void sendJsEvent(JSONObject o) {
        final String js = "window.onNativeEvent && window.onNativeEvent(" + o.toString() + ");";
        mainHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() % 2 != 0) clean = "0" + clean;
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public class AndroidBridge {
        @JavascriptInterface public String getVersion() { return APP_VERSION; }
        @JavascriptInterface public String getHardwareStatus() { return getStatusJson(); }
        @JavascriptInterface public void requestPermissions() { mainHandler.post(() -> requestAllPerms()); }
        @JavascriptInterface public void enableBluetooth() {
            mainHandler.post(() -> {
                if (btAdapter != null && !btAdapter.isEnabled()) {
                    try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT); }
                    catch (Throwable ignored) {}
                }
            });
        }
        @JavascriptInterface public void openLocationSettings() {
            mainHandler.post(() -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        }
        @JavascriptInterface public void openAppSettings() {
            mainHandler.post(() -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            });
        }
        @JavascriptInterface public void startScan() { mainHandler.post(() -> startAllScans()); }
        @JavascriptInterface public void stopScan() { mainHandler.post(() -> stopAllScans()); }
        @JavascriptInterface public void connectBle(String mac) { mainHandler.post(() -> MainActivity.this.connectBle(mac)); }
        @JavascriptInterface public void connectSpp(String mac) { mainHandler.post(() -> MainActivity.this.connectSpp(mac)); }
        @JavascriptInterface public void disconnect() { mainHandler.post(() -> disconnectInternal()); }
        @JavascriptInterface public void pairDevice(String mac) {
            mainHandler.post(() -> {
                if (btAdapter != null && hasBtPerms()) {
                    try { btAdapter.getRemoteDevice(mac).createBond(); } catch (Throwable e) { emitLog("Pair error: " + e.getMessage()); }
                }
            });
        }
        @JavascriptInterface public void readRemoteRssi() {
            if (gatt != null) enqueueOp(new GattOp(GattOp.RSSI));
        }
        @JavascriptInterface public void discoverServices() {
            if (gatt != null) enqueueOp(new GattOp(GattOp.DISCOVER));
        }
        @JavascriptInterface public String getGattServices() { return buildGattJson().toString(); }
        @JavascriptInterface public void readCharacteristic(String svcUuid, String charUuid) {
            BluetoothGattCharacteristic ch = findCharacteristic(svcUuid, charUuid);
            if (ch != null) {
                GattOp op = new GattOp(GattOp.READ_CHAR);
                op.ch = ch;
                enqueueOp(op);
            }
        }
        @JavascriptInterface public void subscribeCharacteristic(String svcUuid, String charUuid, boolean enable) {
            BluetoothGattCharacteristic ch = findCharacteristic(svcUuid, charUuid);
            if (ch != null && gatt != null) {
                gatt.setCharacteristicNotification(ch, enable);
                BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    GattOp op = new GattOp(GattOp.WRITE_DESC);
                    op.desc = cccd;
                    boolean ind = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                    op.data = enable
                        ? (ind ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    enqueueOp(op);
                }
            }
        }
        @JavascriptInterface public void writeCharacteristicHex(String svcUuid, String charUuid, String hex, String mode) {
            byte[] data = hexToBytes(hex);
            if (data.length == 0) return;
            if ("SPP".equals(connMode)) {
                writeSppHex(hex);
                return;
            }
            BluetoothGattCharacteristic ch = findCharacteristic(svcUuid, charUuid);
            if (ch == null) { emitLog("Characteristic not found: " + charUuid); return; }
            int props = ch.getProperties();
            int wt;
            if ("default".equalsIgnoreCase(mode)) wt = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            else if ("no_response".equalsIgnoreCase(mode)) wt = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            else wt = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            GattOp op = new GattOp(GattOp.WRITE_CHAR);
            op.ch = ch;
            op.data = data;
            op.writeType = wt;
            emitPacket("TX", ch.getUuid().toString(), data);
            enqueueOp(op);
        }
        @JavascriptInterface public void writeSppHex(String hex) {
            byte[] data = hexToBytes(hex);
            if (data.length == 0 || sppOut == null) return;
            new Thread(() -> {
                try {
                    sppOut.write(data);
                    sppOut.flush();
                    emitPacket("TX", "SPP", data);
                } catch (Throwable e) { emitLog("SPP TX error: " + e.getMessage()); }
            }).start();
        }
        @JavascriptInterface public void writeSppText(String text) {
            if (text == null || sppOut == null) return;
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            new Thread(() -> {
                try {
                    sppOut.write(data);
                    sppOut.flush();
                    emitPacket("TX", "SPP", data);
                } catch (Throwable e) { emitLog("SPP TX error: " + e.getMessage()); }
            }).start();
        }
    }
}
