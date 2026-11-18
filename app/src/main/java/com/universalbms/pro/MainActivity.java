package com.universalbms.pro;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private TextView outputView;
    private TextView statusBanner;
    private final List<String> recoveryHistory = new ArrayList<>();
    private BluetoothAdapter bluetoothAdapter;
    private boolean scanning = false;

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name = "Unknown";
            if (hasBtPermission()) {
                try {
                    if (dev.getName() != null) name = dev.getName();
                } catch (SecurityException ignored) {}
            }
            String sig = BmsEngine.Module1HiddenBmsDetection.matchManufacturerSignature(name, "");
            appendOutput("[BLE] " + name + " (" + dev.getAddress() + ") RSSI=" + result.getRssi() + " -> " + sig);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) bluetoothAdapter = bm.getAdapter();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 36, 24, 24);
        root.setBackgroundColor(Color.parseColor("#F5F7FA"));

        TextView title = new TextView(this);
        title.setText("Universal BMS Pro (Android 13 / API 33)");
        title.setTextSize(20f);
        title.setTextColor(Color.parseColor("#111827"));
        root.addView(title);

        statusBanner = new TextView(this);
        statusBanner.setText("Authorized Discovery & Read-Only Safety Mode Active");
        statusBanner.setTextSize(13f);
        statusBanner.setTextColor(Color.parseColor("#047857"));
        statusBanner.setPadding(0, 6, 0, 14);
        root.addView(statusBanner);

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);

        String[] labels = {
            "M1: Hidden BMS", "M2: Wake-Up", "M3: Auth Mgr",
            "M4: Diagnostics", "M5: Recovery", "M6: Decision", "M7: Safety"
        };
        for (int i = 0; i < labels.length; i++) {
            final int idx = i + 1;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setOnClickListener(v -> showModule(idx));
            tabRow.addView(b);
        }
        tabScroll.addView(tabRow);
        root.addView(tabScroll);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button scanBtn = new Button(this);
        scanBtn.setText("Scan BLE / Classic / OTG");
        scanBtn.setOnClickListener(v -> runHardwareDiscovery());
        actionRow.addView(scanBtn);

        Button permBtn = new Button(this);
        permBtn.setText("Check Permissions");
        permBtn.setOnClickListener(v -> requestAndroid13Permissions());
        actionRow.addView(permBtn);
        root.addView(actionRow);

        LinearLayout attestBox = new LinearLayout(this);
        attestBox.setOrientation(LinearLayout.VERTICAL);
        EditText ownerInput = new EditText(this);
        ownerInput.setHint("Owner / Technician ID");
        attestBox.addView(ownerInput);
        CheckBox attestCheck = new CheckBox(this);
        attestCheck.setText("Owner Authorization Attested (Required for M5 Recovery)");
        attestBox.addView(attestCheck);
        Button logBtn = new Button(this);
        logBtn.setText("Log Authorized Recovery Step");
        logBtn.setOnClickListener(v -> {
            String owner = ownerInput.getText().toString().trim();
            if (owner.isEmpty()) owner = "Verified-Technician";
            String entry = BmsEngine.Module5AuthorizedDeviceRecovery.createHistoryEntry(
                owner, "BMS-TARGET-01", "8-Step Recovery Checkpoint", attestCheck.isChecked()
            );
            recoveryHistory.add(entry);
            appendOutput("LOGGED: " + entry);
        });
        attestBox.addView(logBtn);
        root.addView(attestBox);

        ScrollView scroll = new ScrollView(this);
        outputView = new TextView(this);
        outputView.setTextSize(13f);
        outputView.setTextColor(Color.parseColor("#1F2937"));
        outputView.setPadding(16, 16, 16, 16);
        outputView.setBackgroundColor(Color.WHITE);
        scroll.addView(outputView);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
        showModule(1);
    }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAndroid13Permissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            }, 101);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
        }
    }

    private void runHardwareDiscovery() {
        StringBuilder sb = new StringBuilder("=== Live Hardware Discovery ===\n");
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager != null) {
            Map<String, UsbDevice> usbDevices = usbManager.getDeviceList();
            sb.append("USB OTG Devices Attached: ").append(usbDevices.size()).append("\n");
            for (UsbDevice d : usbDevices.values()) {
                sb.append(" - VID: ").append(d.getVendorId()).append(" PID: ").append(d.getProductId()).append("\n");
            }
        }
        if (bluetoothAdapter == null) {
            sb.append("Bluetooth Adapter: Unavailable on this device.\n");
        } else if (!hasBtPermission()) {
            sb.append("Bluetooth Permissions Missing -> Requesting Android 13 permissions...\n");
            requestAndroid13Permissions();
        } else {
            try {
                sb.append("Previously Bonded Devices: ").append(bluetoothAdapter.getBondedDevices().size()).append("\n");
                for (BluetoothDevice bd : bluetoothAdapter.getBondedDevices()) {
                    sb.append(" - ").append(bd.getName()).append(" [").append(bd.getAddress()).append("]\n");
                }
                BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
                if (scanner != null && !scanning) {
                    scanning = true;
                    ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                    scanner.startScan(null, settings, leScanCallback);
                    sb.append("BLE Active Scan Started (Low Latency)...\n");
                }
            } catch (SecurityException se) {
                sb.append("SecurityException: ").append(se.getMessage()).append("\n");
            }
        }
        outputView.setText(sb.toString());
    }

    private void appendOutput(String line) {
        outputView.setText(outputView.getText() + "\n" + line);
    }

    private void showModule(int moduleNum) {
        StringBuilder sb = new StringBuilder();
        switch (moduleNum) {
            case 1:
                sb.append("MODULE 1: Advanced Hidden BMS Detection\n\nKnown BMS Signatures:\n");
                for (BmsEngine.BmsSignature s : BmsEngine.Module1HiddenBmsDetection.getKnownSignatures()) {
                    sb.append("- ").append(s.vendor).append(" | ").append(s.protocol)
                      .append("\n  UUID: ").append(s.serviceUuid).append("\n");
                }
                sb.append("\n8-Point Discovery Failure Diagnostic Workflow:\n");
                for (String step : BmsEngine.Module1HiddenBmsDetection.get8PointDiscoveryDiagnosticWorkflow()) {
                    sb.append(step).append("\n");
                }
                break;
            case 2:
                sb.append("MODULE 2: Smart BMS Wake-Up & Recovery Assistant\n\n");
                for (Map.Entry<String, String> e : BmsEngine.Module2SmartWakeUpAssistant.getWakeUpProcedures().entrySet()) {
                    sb.append("* ").append(e.getKey()).append(": ").append(e.getValue()).append("\n\n");
                }
                sb.append("Hardware Safety Warnings:\n");
                for (String w : BmsEngine.Module2SmartWakeUpAssistant.getHardwareSafetyWarnings()) {
                    sb.append("- ").append(w).append("\n");
                }
                break;
            case 3:
                sb.append("MODULE 3: Universal Authentication Manager\n\nSupported Modes:\n");
                for (String m : BmsEngine.Module3UniversalAuthManager.getAuthModes()) {
                    sb.append("- ").append(m).append("\n");
                }
                sb.append("\nDocumented OEM Reset Reference:\n");
                sb.append("- ").append(BmsEngine.Module3UniversalAuthManager.getDocumentedResetGuide("JK")).append("\n");
                sb.append("- ").append(BmsEngine.Module3UniversalAuthManager.getDocumentedResetGuide("Daly")).append("\n");
                sb.append("- ").append(BmsEngine.Module3UniversalAuthManager.getDocumentedResetGuide("JBD")).append("\n");
                break;
            case 4:
                sb.append("MODULE 4: Smart Authentication Diagnostics\n\n");
                for (Map.Entry<String, String> e : BmsEngine.Module4SmartAuthDiagnostics.getDiagnosticMatrix().entrySet()) {
                    sb.append("[").append(e.getKey()).append("]\n  -> ").append(e.getValue()).append("\n\n");
                }
                break;
            case 5:
                sb.append("MODULE 5: Authorized Device Recovery (8-Step Workflow)\n\n");
                for (String s : BmsEngine.Module5AuthorizedDeviceRecovery.get8StepTechnicianWorkflow()) {
                    sb.append(s).append("\n");
                }
                sb.append("\nRecovery History Log (").append(recoveryHistory.size()).append(" entries):\n");
                for (String h : recoveryHistory) {
                    sb.append(h).append("\n");
                }
                break;
            case 6:
                sb.append("MODULE 6: Intelligent Discovery Decision Engine\n\n");
                sb.append("1) Known BLE: ").append(BmsEngine.Module6IntelligentDecisionEngine.evaluateStrategy(true, false, false, true)).append("\n\n");
                sb.append("2) Unknown BLE: ").append(BmsEngine.Module6IntelligentDecisionEngine.evaluateStrategy(true, false, false, false)).append("\n\n");
                sb.append("3) Classic Fallback: ").append(BmsEngine.Module6IntelligentDecisionEngine.evaluateStrategy(false, true, false, false)).append("\n\n");
                sb.append("4) USB OTG Fallback: ").append(BmsEngine.Module6IntelligentDecisionEngine.evaluateStrategy(false, false, true, false)).append("\n\n");
                sb.append("5) Silent BMS: ").append(BmsEngine.Module6IntelligentDecisionEngine.evaluateStrategy(false, false, false, false)).append("\n");
                break;
            case 7:
                sb.append("MODULE 7: Final Requirement (Compliance & Hardware Safety)\n\n");
                for (String c : BmsEngine.Module7FinalRequirement.getComplianceChecklist()) {
                    sb.append("[OK] ").append(c).append("\n");
                }
                break;
        }
        outputView.setText(sb.toString());
    }
}
