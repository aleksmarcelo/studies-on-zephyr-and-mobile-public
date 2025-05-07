package com.aleks.android

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.aleks.android.ui.theme.AndroidTheme
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
class BluetoothScanningActivity : ComponentActivity() {

    private var isScanning by mutableStateOf(false)
    private val discoveredDevices = mutableStateListOf<ScanResult>()
    private val compatibleDevices =
        mutableStateListOf<String>() // List of compatible devices by address
    private var connectingDevice by mutableStateOf<String?>(null) // Device being check at moment
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bleManager: BleManager

    // Nordic BLE Scanner
    private lateinit var scanner: BluetoothLeScannerCompat

    // Scan callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Add device if it not in list already
            if (discoveredDevices.none { it.device.address == result.device.address }) {
                discoveredDevices.add(result)
            } else {
                // Update existing device with new informations (RSSI, etc)
                val index =
                    discoveredDevices.indexOfFirst { it.device.address == result.device.address }
                if (index >= 0) {
                    discoveredDevices[index] = result
                }
            }
        }

        // Called when scan fails
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with code $errorCode")
            isScanning = false
            Toast.makeText(
                this@BluetoothScanningActivity,
                "Scan failed: ${scanErrorToString(errorCode)}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Helper to convert error codes in readable strings
    private fun scanErrorToString(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Registration failed"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature not supported"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
            else -> "Unknown error ($errorCode)"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        scanner = BluetoothLeScannerCompat.getScanner()
        bleManager = BleManager(applicationContext)

//        BleManager.disconnectDevice()

        setContent {
            AndroidTheme {
                ScanningDevicesScreen(
                    scanResults = discoveredDevices,
                    isScanning = isScanning,
                    onStartScan = { startScan() },
                    onStopScan = { stopScan() },
                    onBackClick = { finish() },
                    onDeviceClick = { device -> onDeviceSelected(device) },
                    compatibleDevices = compatibleDevices,
                    connectingDevice = connectingDevice
                )
            }
        }

        // Start scan automatically when activity opens
        startScan()
    }

    private fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN),
                    1
                )
                return
            }
        }

        try {
            isScanning = true
            discoveredDevices.clear()

            // Filter by service UUID for more efficient scanning
            val filters = listOf(
                ScanFilter.Builder()
                    //todo: identificar como implementar o filtro de UUID
//                    .setServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()
            )

            // Setup scan options
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER) //SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            Toast.makeText(this, "Scanning devices...", Toast.LENGTH_SHORT).show()
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            isScanning = false
            Toast.makeText(this, "Error starting scan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        try {
            isScanning = false
            scanner.stopScan(scanCallback)
//            Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error stopping scan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeviceSelected(result: ScanResult) {
        val device = result.device
        val deviceAddress = device.address

        if (compatibleDevices.contains(deviceAddress)) {
            // Device already checked and is compatible - return to MainActivity
            returnSelectedDeviceToMain(deviceAddress)
            return
        }

        // Start service verification using BleManager
        checkDeviceCompatibility(device)
    }

    // Helper to return selected device to MainActivity
    private fun returnSelectedDeviceToMain(deviceAddress: String) {
        val intent = Intent().apply {
            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            val remoteName = bleManager.getConnectedDeviceName()
            putExtra(EXTRA_DEVICE_NAME, remoteName)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun checkDeviceCompatibility(device: BluetoothDevice) {
        // Avoid check many devices at same time
        if (connectingDevice != null) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Bluetooth connect permission needed", Toast.LENGTH_SHORT)
                    .show()
                return
            }
        }

        try {
            connectingDevice = device.address

            // Setup callback for when device is ready
            bleManager.onDisconnected = {
                runOnUiThread {
                    if (connectingDevice == device.address) {
                        connectingDevice = null
                    }
                }
            }

            // Setup callback para quando os serviços forem descobertos
            bleManager.onServicesDiscovered = { isCompatible ->
                runOnUiThread {
                    // Clear connection state
                    connectingDevice = null
                    if (isCompatible) {
                        // Add compatible device and return to MainActivity
                        compatibleDevices.add(device.address)
                        returnSelectedDeviceToMain(device.address)
                    } else {
                        Toast.makeText(
                            this,
                            "Device not compatible",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                // Disconnect after verification
                bleManager.disconnectDevice()
            }

            // Use BleManager to test connection
            bleManager.connectToDevice(device)

            // Setup a timeout
            handler.postDelayed({
                if (connectingDevice == device.address) {
                    // Still trying connect - cancel
                    bleManager.disconnectDevice()
                    connectingDevice = null

                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Timeout checking device compatibility",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }, 6000) // Give little more time than connection timeout

        } catch (e: Exception) {
            Log.e(TAG, "Error checking compatibility: ${e.message}")
            Toast.makeText(this, "Error checking compatibility: ${e.message}", Toast.LENGTH_SHORT)
                .show()
            connectingDevice = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) {
            stopScan()
        }
        bleManager.close()
    }

    companion object {
        private const val TAG = "BluetoothScanActivity"
        const val EXTRA_DEVICE_ADDRESS = "com.aleks.android.DEVICE_ADDRESS"
        const val EXTRA_DEVICE_NAME = "com.aleks.android.DEVICE_NAME"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanningDevicesScreen(
    scanResults: List<ScanResult>,
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onBackClick: () -> Unit,
    onDeviceClick: (ScanResult) -> Unit,
    compatibleDevices: List<String>,
    connectingDevice: String?
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "BLE Devices",
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // SCAN and STOP buttons at top
                    if (isScanning) {
                        Button(
                            onClick = onStopScan,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("STOP")
                        }
                    } else {
                        Button(
                            onClick = onStartScan,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0066CC),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("SCAN")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0066CC),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Progress indicator during scanning
            if (isScanning) {
                LinearProgressIndicator(
                    color = Color.Blue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1.dp, vertical = 8.dp)
                )
            }

            // List of found devices
            LazyColumn(
                modifier = Modifier.padding(2.dp)
            ) {
                items(scanResults) { result ->
                    val device = result.device
                    val deviceName = if (ActivityCompat.checkSelfPermission(
                            LocalContext.current,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        device.name ?: "Unknown Device"
                    } else {
                        "Unknown Device (Need Permission)"
                    }
                    val isCompatible = compatibleDevices.contains(device.address)
                    val isChecking = connectingDevice == device.address

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.LightGray)
                            .padding(vertical = 4.dp)
                            .clickable(enabled = !isChecking) {
                                onDeviceClick(result)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isCompatible -> Color(0xFFE0FFE0) // Light green for compatible
                                isChecking -> Color(0xFFFFF0E0)   // Light orange for checking
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = deviceName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = device.address,
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Compatibility status
                                    if (isCompatible) {
                                        Text(
                                            text = "✓ Compatible",
                                            fontSize = 12.sp,
                                            color = Color.Green,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else if (isChecking) {
                                        Text(
                                            text = "Checking compatibility...",
                                            fontSize = 12.sp,
                                            color = Color(0xFFF57C00) // Orange
                                        )
                                    }
                                }
                            }
                            // RSSI at right
                            Text(
                                text = "${result.rssi} dBm",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                // Message when no devices found
                if (scanResults.isEmpty() && !isScanning) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No device found.\nClick SCAN to start scan.",
                                textAlign = TextAlign.Center,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}
