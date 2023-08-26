package de.paystory.thermal_printer;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Base64;

import com.bthrust.escposprinter.EscPosCharsetEncoding;
import com.bthrust.escposprinter.EscPosPrinter;
import com.bthrust.escposprinter.connection.DeviceConnection;
import com.bthrust.escposprinter.connection.bluetooth.BluetoothConnection;
import com.bthrust.escposprinter.connection.bluetooth.BluetoothConnections;
import com.bthrust.escposprinter.connection.bluetooth.BluetoothPrintersConnections;
import com.bthrust.escposprinter.connection.tcp.TcpConnection;
import com.bthrust.escposprinter.connection.usb.UsbConnection;
import com.bthrust.escposprinter.connection.usb.UsbConnections;
import com.bthrust.escposprinter.exceptions.EscPosConnectionException;
import com.bthrust.escposprinter.textparser.PrinterTextParserImg;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public class ThermalPrinterCordovaPlugin extends CordovaPlugin {
    private final HashMap<String, DeviceConnection> connections = new HashMap<>();

    @Override
    public boolean execute(String action, JSONArray args,
                           final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                if (action.equals("listPrinters")) {
                    try {
                        ThermalPrinterCordovaPlugin.this.listPrinters(callbackContext, args.getJSONObject(0));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else if (action.startsWith("printFormattedText")) {
                    ThermalPrinterCordovaPlugin.this.printFormattedText(callbackContext, action, args.getJSONObject(0));
                } else if (action.equals("openCashBoxDrawer")) {
                    ThermalPrinterCordovaPlugin.this.openCashBoxDrawer(callbackContext, args.getJSONObject(0));
                }  else if (action.equals("getEncoding")) {
                    ThermalPrinterCordovaPlugin.this.getEncoding(callbackContext, args.getJSONObject(0));
                } else if (action.equals("disconnectPrinter")) {
                    ThermalPrinterCordovaPlugin.this.disconnectPrinter(callbackContext, args.getJSONObject(0));
                } else if (action.equals("requestPermissions")) {
                    ThermalPrinterCordovaPlugin.this.requestUSBPermissions(callbackContext, args.getJSONObject(0));
                } else if (action.equals("bitmapToHexadecimalString")) {
                    ThermalPrinterCordovaPlugin.this.bitmapToHexadecimalString(callbackContext, args.getJSONObject(0));
                }
            } catch (JSONException exception) {
                callbackContext.error(exception.getMessage());
            }
        });

        return true;
    }

    private void bitmapToHexadecimalString(CallbackContext callbackContext, JSONObject data) throws JSONException {
        String encodedString = data.getString("base64");
        byte[] decodedString = Base64.decode(encodedString.contains(",")
            ? encodedString.substring(encodedString.indexOf(",") + 1) : encodedString, Base64.DEFAULT);
        data.put("bytes", decodedString);
        this.bytesToHexadecimalString(callbackContext, data);
    }

    private void bytesToHexadecimalString(CallbackContext callbackContext, JSONObject data) throws JSONException {
        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        try {
            byte[] bytes = (byte[]) data.get("bytes");
            Bitmap decodedByte = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            callbackContext.success(PrinterTextParserImg.bitmapToHexadecimalString(printer, decodedByte));
        } catch (Exception e) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", e.getMessage());
            }}));
        }
    }

    private void requestUSBPermissions(CallbackContext callbackContext, JSONObject data) throws JSONException {
        DeviceConnection connection = ThermalPrinterCordovaPlugin.this.getPrinterConnection(callbackContext, data);
        if (connection != null) {
            String intentName = "thermalPrinterUSBRequest" + ((UsbConnection) connection).getDevice().getDeviceId();

            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                cordova.getActivity().getBaseContext(),
                0,
                new Intent(intentName),
                0
            );

            ArrayList<BroadcastReceiver> broadcastReceiverArrayList = new ArrayList<>();
            BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action != null && action.equals(intentName)) {
                        for (BroadcastReceiver br : broadcastReceiverArrayList) {
                            if (br != null) {
                                cordova.getActivity().unregisterReceiver(br);
                            }
                        }
                        synchronized (this) {
                            UsbManager usbManager = (UsbManager) ThermalPrinterCordovaPlugin.this.cordova.getActivity().getSystemService(Context.USB_SERVICE);
                            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                if (usbManager != null && usbDevice != null) {
                                    callbackContext.success(new JSONObject(new HashMap<String, Object>() {{
                                        put("granted", true);
                                    }}));
                                    return;
                                }
                            }
                            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                                put("granted", false);
                            }}));
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter(intentName);
            cordova.getActivity().registerReceiver(broadcastReceiver, filter);
            broadcastReceiverArrayList.add(broadcastReceiver);

            UsbManager usbManager = (UsbManager) this.cordova.getActivity().getSystemService(Context.USB_SERVICE);
            if (usbManager != null) {
                usbManager.requestPermission(((UsbConnection) connection).getDevice(), permissionIntent);
            }
        }
    }

    private void listPrinters(CallbackContext callbackContext, JSONObject data) throws JSONException {
        JSONArray printers = new JSONArray();

        String type = data.getString("type");
        if (type.equals("bluetooth")) {
            if (!this.cordova.hasPermission(Manifest.permission.BLUETOOTH)) {
                callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                    put("error", "Missing permission for " + Manifest.permission.BLUETOOTH);
                }}));
                return;
            }
            if (!this.checkBluetooth(callbackContext)) {
                return;
            }
            try {
                BluetoothConnections printerConnections = new BluetoothConnections();
                for (BluetoothConnection bluetoothConnection : printerConnections.getList()) {
                    BluetoothDevice bluetoothDevice = bluetoothConnection.getDevice();
                    JSONObject printerObj = new JSONObject();
                    try { printerObj.put("address", bluetoothDevice.getAddress()); } catch (Exception ignored) {}
                    try { printerObj.put("bondState", bluetoothDevice.getBondState()); } catch (Exception ignored) {}
                    try { printerObj.put("name", bluetoothDevice.getName()); } catch (Exception ignored) {}
                    try { printerObj.put("type", bluetoothDevice.getType()); } catch (Exception ignored) {}
                    try { printerObj.put("features", bluetoothDevice.getUuids()); } catch (Exception ignored) {}
                    try { printerObj.put("deviceClass", bluetoothDevice.getBluetoothClass().getDeviceClass()); } catch (Exception ignored) {}
                    try { printerObj.put("majorDeviceClass", bluetoothDevice.getBluetoothClass().getMajorDeviceClass()); } catch (Exception ignored) {}
                    printers.put(printerObj);
                }
            } catch (Exception e) {
                callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                    put("error", e.getMessage());
                }}));
            }
        } else {
            UsbConnections printerConnections = new UsbConnections(this.cordova.getActivity());
            for (UsbConnection usbConnection : printerConnections.getList()) {
                UsbDevice usbDevice = usbConnection.getDevice();
                JSONObject printerObj = new JSONObject();
                try { printerObj.put("productName", Objects.requireNonNull(usbDevice.getProductName()).trim()); } catch (Exception ignored) {}
                try { printerObj.put("manufacturerName", usbDevice.getManufacturerName()); } catch (Exception ignored) {}
                try { printerObj.put("deviceId", usbDevice.getDeviceId()); } catch (Exception ignored) {}
                try { printerObj.put("serialNumber", usbDevice.getSerialNumber()); } catch (Exception ignored) {}
                try { printerObj.put("vendorId", usbDevice.getVendorId()); } catch (Exception ignored) {}
                printers.put(printerObj);
            }
        }

        callbackContext.success(printers);
    }

    private void printFormattedText(CallbackContext callbackContext, String action, JSONObject data) throws JSONException {
        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        try {
            int dotsFeedPaper = data.has("mmFeedPaper")
                ? printer.mmToPx((float) data.getDouble("mmFeedPaper"))
                : data.optInt("dotsFeedPaper", 20);
            if (action.endsWith("OpenCashBox")) {
                printer.printFormattedTextAndOpenCashBox(data.getString("text"), dotsFeedPaper);
            } else if (action.endsWith("Cut")) {
                printer.printFormattedTextAndCut(data.getString("text"), dotsFeedPaper);
            } else {
                printer.printFormattedText(data.getString("text"), dotsFeedPaper);
            }
            callbackContext.success();
        } catch (EscPosConnectionException e) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", e.getMessage());
            }}));
        } catch (Exception e) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", e.getMessage());
            }}));
        }
    }

    private void openCashBoxDrawer(CallbackContext callbackContext, JSONObject data) throws JSONException {
        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        try {
            printer.openCashBoxDrawer();
            callbackContext.success();
        } catch (EscPosConnectionException e) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", e.getMessage());
            }}));
        } catch (Exception e) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", e.getMessage());
            }}));
        }
    }

    private void getEncoding(CallbackContext callbackContext, JSONObject data) throws JSONException {
        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        callbackContext.success(new JSONObject(new HashMap<String, Object>() {{
            EscPosCharsetEncoding encoding = printer.getEncoding();
            if (encoding != null) {
                callbackContext.success(new JSONObject(new HashMap<String, Object>() {{
                    put("name", encoding.getName());
                    put("command", encoding.getCommand());
                }}));
            } else {
                callbackContext.success("null");
            }
        }}));
    }

    private void disconnectPrinter(CallbackContext callbackContext, JSONObject data) throws JSONException {
        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        printer.disconnectPrinter();
        callbackContext.success();
    }

    private DeviceConnection getDevice(CallbackContext callbackContext, String type, String id, String address, int port) {
        String hashKey = type + "-" + id;
        if (this.connections.containsKey(hashKey)) {
            DeviceConnection connection = this.connections.get(hashKey);
            if (connection != null) {
                if (connection.isConnected()) {
                    return connection;
                } else {
                    this.connections.remove(hashKey);
                }
            }
        }

        if (type.equals("bluetooth")) {
            if (!this.checkBluetooth(callbackContext)) {
                return null;
            }
            if (!this.cordova.hasPermission(Manifest.permission.BLUETOOTH)) {
                callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                    put("error", "Missing permission for " + Manifest.permission.DISABLE_KEYGUARD);
                }}));
                return null;
            }
            if (id.equals("first")) {
                return BluetoothPrintersConnections.selectFirstPaired();
            }
            BluetoothConnections printerConnections = new BluetoothConnections();
            for (BluetoothConnection bluetoothConnection : printerConnections.getList()) {
                BluetoothDevice bluetoothDevice = bluetoothConnection.getDevice();
                try { if (bluetoothDevice.getAddress().equals(id)) { return bluetoothConnection; } } catch (Exception ignored) {}
                try { if (bluetoothDevice.getName().equals(id)) { return bluetoothConnection; } } catch (Exception ignored) {}
            }
        } else if (type.equals("tcp")) {
            return new TcpConnection(address, port);
        } else {
            UsbConnections printerConnections = new UsbConnections(this.cordova.getActivity());
            for (UsbConnection usbConnection : printerConnections.getList()) {
                UsbDevice usbDevice = usbConnection.getDevice();
                try { if (usbDevice.getDeviceId() == Integer.parseInt(id)) { return usbConnection; } } catch (Exception ignored) {}
                try { if (Objects.requireNonNull(usbDevice.getProductName()).trim().equals(id)) { return usbConnection; } } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private EscPosPrinter getPrinter(CallbackContext callbackContext, JSONObject data) throws JSONException {
        DeviceConnection deviceConnection = this.getPrinterConnection(callbackContext, data);
        if (deviceConnection == null) {
            throw new JSONException("Device not found");
        }

        EscPosCharsetEncoding charsetEncoding = null;
        try {
            if (data.optJSONObject("charsetEncoding") != null) {
                JSONObject charsetEncodingData = data.optJSONObject("charsetEncoding");
                if (charsetEncodingData == null) {
                    charsetEncodingData = new JSONObject();
                }
                charsetEncoding = new EscPosCharsetEncoding(
                    charsetEncodingData.optString("charsetName", "windows-1252"),
                    charsetEncodingData.optInt("charsetId", 16)
                );
            }
        } catch (Exception exception) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", exception.getMessage());
            }}));
            throw new JSONException(exception.getMessage());
        }

        try {
            return new EscPosPrinter(
                deviceConnection,
                data.optInt("printerDpi", 203),
                (float) data.optDouble("printerWidthMM", 48f),
                data.optInt("printerNbrCharactersPerLine", 32),
                charsetEncoding
            );
        } catch (Exception e) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", e.getMessage());
            }}));
            throw new JSONException(e.getMessage());
        }
    }

    private DeviceConnection getPrinterConnection(CallbackContext callbackContext, JSONObject data) throws JSONException {
        String type = data.getString("type");
        String id = data.getString("id");
        String hashKey = type + "-" + id;
        DeviceConnection deviceConnection = this.getDevice(
            callbackContext,
            data.getString("type"),
            data.optString("id"),
            data.optString("address"),
            data.optInt("port", 9100)
        );
        if (deviceConnection == null) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "Device not found or not connected!");
                put("type", type);
                put("id", id);
            }}));
        }
        if (!this.connections.containsKey(hashKey)) {
            this.connections.put(hashKey, deviceConnection);
        }
        return deviceConnection;
    }

    private boolean checkBluetooth(CallbackContext callbackContext) {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "Device doesn't support Bluetooth!");
            }}));
            return false;
        } else if (!mBluetoothAdapter.isEnabled()) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "Device not enabled Bluetooth!");
            }}));
            return false;
        }
        return true;
    }
}
