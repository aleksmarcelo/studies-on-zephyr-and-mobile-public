package com.aleks.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aleks.android.ui.theme.AndroidTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var enableBluetoothLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private var isBluetoothEnabledState = mutableStateOf(false)

    // New: saves the last connected address for automatic reconnection
    private var lastTriedDeviceAddress: String? = null

    // Creating constant reference to BluetoothAdapter
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // BLE manager for LED control
    private lateinit var bleManager: BleManager

    // Instead of a custom DevicePreferenceManager, let's use SharedPreferences directly for now
    private lateinit var sharedPrefs: SharedPreferences

    // Connected device state (compose state for recomposition)
    private var connectedDeviceAddress by mutableStateOf<String?>(null)
    private var savedDeviceAddress by mutableStateOf<String?>(null)
    private var savedDeviceName by mutableStateOf<String?>(null)

    // Launcher for the scanning activity - Moved to the top of the class
    private lateinit var scanDeviceLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    // Receiver to monitor Bluetooth state and reconnect when necessary
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                isBluetoothEnabledState.value = (state == BluetoothAdapter.STATE_ON)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        // Try to reconnect to previously selected device
                        savedDeviceAddress?.let { address ->
                            connectToDevice(address)
                        }
                    }

                    BluetoothAdapter.STATE_OFF -> {
                        // Disconnect when Bluetooth is turned off
                        bleManager.disconnectDevice()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isBluetoothEnabledState.value = bluetoothAdapter?.isEnabled ?: false


        enableEdgeToEdge()
        setContent {
            AndroidTheme {
                BleLedControlApp(
                    bleManager = bleManager,
                    bluetoothAdapter = bluetoothAdapter,
                    enableBluetoothLauncher = enableBluetoothLauncher,
                    isBluetoothEnabledState = isBluetoothEnabledState,
                    onScanningDevicesClick = { launchScanningActivity() },
                    onClearMemory = { clearMemoryAndDisconnect() },
                    ledState = bleManager.ledState,
                    isConnectedState = bleManager.isConnected,
                    onLedToggle = { bleManager.toggleLed() },
                    connectedDeviceAddress = connectedDeviceAddress,
                    savedDeviceAddress = savedDeviceAddress,
                    savedDeviceName = savedDeviceName
                )
            }
        }

        // Initialize the Bluetooth adapter
        // Initialize SharedPreferences instead of DevicePreferenceManager
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        savedDeviceAddress = sharedPrefs.getString(PREF_DEVICE_ADDRESS, null)
        savedDeviceName = sharedPrefs.getString(PREF_DEVICE_NAME, null)

        // Request all necessary permissions for Bluetooth functionality
        requestBluetoothPermissions()

        // Initialize the BLE manager
        bleManager = BleManager(this)


        // Set up the connection state listener, similar to nRF Connect
        bleManager.connectionStateListener = { state, device ->
            when (state) {
                BleManager.BleConnectionState.CONNECTED -> {
                    Log.d("MainActivity", "Connected to device: ${device?.address}")
                    connectedDeviceAddress = device?.address

                }

                BleManager.BleConnectionState.DISCONNECTED -> {
                    Log.d("MainActivity", "Disconnected from device: ${device?.address}")
                    connectedDeviceAddress = null

                    // Small delay to avoid rapid reconnection loop
                    if (isBluetoothEnabledState.value)
                        savedDeviceAddress?.let { address ->
                            Handler(mainLooper).postDelayed({
                                connectToDevice(address)
                            }, 2000)
                        }
                }

                else -> {
                    Log.d("MainActivity", "Connection state changed: $state")
                }
            }
        }


        // Launcher to activate Bluetooth and handle the request result
        // The launcher is registered here to handle Bluetooth activation result
        // When successfully activated, the Bluetooth state is updated
        enableBluetoothLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                // Implementation based on nRF Connect - more robust verification
                when (result.resultCode) {
                    RESULT_OK -> {
//                        isBluetoothEnabledState.value = true
                        Toast.makeText(this, "Bluetooth activated successfully", Toast.LENGTH_SHORT)
                            .show()
                    }

                    RESULT_CANCELED -> {
                        // If user canceled, try to enable directly if we have permission
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                            if (ActivityCompat.checkSelfPermission(
//                                    this,
//                                    Manifest.permission.BLUETOOTH_CONNECT
//                                ) == PackageManager.PERMISSION_GRANTED
//                            ) {
//                                tryToEnableBluetooth()
//                            }
//                        } else {
//                            tryToEnableBluetooth()
//                        }
                    }
                }

                // Check final state after the attempt
                isBluetoothEnabledState.value = bluetoothAdapter?.isEnabled ?: false
            }

        // Launcher to start the scanning Activity and retrieve the selected device
        scanDeviceLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val deviceAddress =
                        result.data?.getStringExtra(BluetoothScanningActivity.EXTRA_DEVICE_ADDRESS)
                    val deviceName =
                        result.data?.getStringExtra(BluetoothScanningActivity.EXTRA_DEVICE_NAME)

                    //save the address in SharedPreferences
                    sharedPrefs.edit().putString(PREF_DEVICE_ADDRESS, deviceAddress).apply()

                    deviceAddress?.let { address ->
                        connectToDevice(address)
                    }

                } else {
                    //get the device address saved again if it is
                    sharedPrefs.getString(PREF_DEVICE_ADDRESS, null)?.let { address ->
                        connectToDevice(address)
                    }
                }

            }

        // Try to restore the device from saved instance state or SharedPreferences
        if (savedInstanceState?.containsKey(KEY_CONNECTED_DEVICE_ADDRESS) == true) {
            savedInstanceState.getString(KEY_CONNECTED_DEVICE_ADDRESS)?.let { address ->
                connectedDeviceAddress = address
                connectToDevice(address)
            }
        }
        // If not in the instance but in preferences (for app restart)
        else if (!savedDeviceAddress.isNullOrEmpty()) {
            connectedDeviceAddress = savedDeviceAddress
            connectToDevice(savedDeviceAddress!!)
        }

        // Register receiver for Bluetooth state changes
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )


    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save the address of the connected device
        connectedDeviceAddress?.let { address ->
            outState.putString(KEY_CONNECTED_DEVICE_ADDRESS, address)
        }
    }

    private fun launchScanningActivity() {
        // Start the scanning activity
        val intent = Intent(this, BluetoothScanningActivity::class.java)
        scanDeviceLauncher.launch(intent)

        // Disconnect from the current device
        bleManager.disconnectDevice()
        connectedDeviceAddress = null
        savedDeviceAddress = null
    }

    /**
     * Connects to the BLE device using the provided address.
     * If already connected, displays a warning message.
     */
    @SuppressLint("MissingPermission")
    private fun connectToDevice(address: String) {
        connectedDeviceAddress = null

        if (!isBluetoothEnabledState.value) return

        bluetoothAdapter?.let { adapter ->
            val device = adapter.getRemoteDevice(address)
            val remoteName = device.name ?: "Unknown Device"

            if (bleManager.isConnected() && address == connectedDeviceAddress) {
                Toast.makeText(
                    this,
                    "Already connected to device ${remoteName}",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            try {
                Toast.makeText(
                    this,
                    "Trying to connect to ${device.name ?: remoteName}...",
                    Toast.LENGTH_LONG
                ).show()

                // Connect to device

                // Save for reconnection
                bleManager.connectToDevice(device)

//                // Save the address in SharedPreferences to persist between restarts
//                sharedPrefs.edit().putString(PREF_DEVICE_ADDRESS, address).apply()
                savedDeviceAddress = address
            } catch (e: Exception) {
                Toast.makeText(this, "Error connecting: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("MainActivity", "Error connecting to device", e)
                connectedDeviceAddress = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disconnect from BLE device when the activity is destroyed
        bleManager.disconnectDevice()
        // Unregister Bluetooth state receiver
        unregisterReceiver(bluetoothStateReceiver)
    }

    // Checks if BLUETOOTH_CONNECT permission is granted (Android 12+)
    private fun checkBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-Android 12 permissions are granted at install time
            true
        }
    }

    // Function to request Bluetooth permissions - moved inside the class
    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                1
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                1
            )
        }
    }

// Add this new function to properly clear memory and reset reconnection


    private fun clearMemoryAndDisconnect() {
        // Disconnect current device
        bleManager.disconnectDevice()

        // Reset connection state
        connectedDeviceAddress = null
        lastTriedDeviceAddress = null
        savedDeviceAddress = null

        // Clear shared preferences
        sharedPrefs.edit().clear().apply()

        Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_CONNECTED_DEVICE_ADDRESS = "connected_device_address"
        internal const val PREFS_NAME = "BleAppPreferences"
        internal const val PREF_DEVICE_ADDRESS = "saved_device_address"
        internal const val PREF_DEVICE_NAME = "saved_device_name"
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleLedControlApp(
    bleManager: BleManager,
    bluetoothAdapter: BluetoothAdapter?,
    enableBluetoothLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    isBluetoothEnabledState: androidx.compose.runtime.MutableState<Boolean>,
    onScanningDevicesClick: () -> Unit,
    onClearMemory: () -> Unit,
    ledState: StateFlow<Boolean>,
    isConnectedState: StateFlow<Boolean>,
    onLedToggle: () -> Unit,
    connectedDeviceAddress: String?,
    savedDeviceAddress: String?,
    savedDeviceName: String?
) {
    val isBluetoothEnabled by isBluetoothEnabledState
    val ledStateValue by ledState.collectAsState()
    val isConnected by isConnectedState.collectAsState()

    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val deviceName = connectedDeviceAddress?.let { address ->
        bluetoothAdapter?.getRemoteDevice(address)?.name
    } ?: "Unknown Device"


    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                // Cabeçalho do drawer (similar ao nRF Connect)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0066CC))
                        .padding(16.dp)
                ) {
                    // Logo ou ícone
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Logo",
                        modifier = Modifier
                            .size(80.dp)
                            .align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Android LED Control",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "BLE Control Application",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                HorizontalDivider()

                // Item do menu Scanning Devices
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Search, contentDescription = "Scan") },
                    label = { Text("Scan Devices") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                        }
                        onScanningDevicesClick()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Delete, contentDescription = "Clear Memory") },
                    label = { Text("Clear Memory") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                        }
                        // Call the new function instead of handling it here
                        onClearMemory()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Android Led",
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = Color.White
                            )
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
                if (!isBluetoothEnabled) {
                    ShowBluetoothDisabledBanner(
                        onEnableClick = {
                            requestBluetoothEnable(
                                context,
                                bluetoothAdapter,
                                enableBluetoothLauncher
                            )
                        }
                    )
                }
                // Centers the LED control panel on screen
                if (isBluetoothEnabled)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        DeviceControlPanel(
                            isConnected = isConnected,
                            connectedDeviceAddress = connectedDeviceAddress,
                            savedDeviceAddress = savedDeviceAddress,  // Pass the savedDeviceAddress
                            deviceName = deviceName,
                            onScanningDevicesClick = onScanningDevicesClick,
                            ledStateValue = ledStateValue,
                            onLedToggle = onLedToggle
                        )
                    }

            }
        }
    }
}

/**
 * Bluetooth disabled alert that appears at the top of the screen
 */
@Composable
fun ShowBluetoothDisabledBanner(onEnableClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xffe04949)) // Orange color for alert
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Bluetooth Disabled",
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onEnableClick,
            modifier = Modifier.padding(start = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White // Slightly lighter background
            )
        ) {
            Text(text = "ENABLE", color = Color.Red)
        }
    }
}

/**
 * Helper function to manage Bluetooth activation request.
 * Called when the user clicks the "ENABLE" button
 */
private fun requestBluetoothEnable(
    context: Context,
    bluetoothAdapter: BluetoothAdapter?,
    enableBluetoothLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    if (bluetoothAdapter == null) {
        Toast.makeText(context, "Bluetooth is not supported on this device", Toast.LENGTH_SHORT)
            .show()
    } else if (!bluetoothAdapter.isEnabled) {
        try {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
//            Toast.makeText(context, "Requesting Bluetooth activation...", Toast.LENGTH_SHORT)
//                .show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error requesting Bluetooth: ${e.message}", Toast.LENGTH_LONG)
                .show()
        }
    }
}

/**
 * Device control panel containing connection status and LED control
 */
@Composable
fun DeviceControlPanel(
    isConnected: Boolean,
    connectedDeviceAddress: String?,
    savedDeviceAddress: String?,  // Add parameter instead of accessing SharedPreferences
    deviceName: String = "Unknown Device",
    onScanningDevicesClick: () -> Unit,
    ledStateValue: Boolean,
    onLedToggle: () -> Unit
) {
    // Remove verticalArrangement.Center here since Box already centers content
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (connectedDeviceAddress == null && savedDeviceAddress == null) {
            ShowSelectDevice(
                isConnected = isConnected,
                connectedDeviceAddress = connectedDeviceAddress,
                onScanningDevicesClick = onScanningDevicesClick
            )
        } else if (!isConnected && connectedDeviceAddress == null && savedDeviceAddress != null) {
            // Show a message that reconnection is in progress
            Text(
                text = "Reconnecting to saved device...",
                modifier = Modifier.padding(bottom = 16.dp),
                color = Color(0xFFFFA000) // Amber color for warning/connecting state
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // LED control (only when connected)
        if (isConnected) {
            LedControlScreen(
                ledState = ledStateValue,
                isConnected = isConnected,
                deviceAddr = connectedDeviceAddress ?: "Unknown Device",
                deviceName,
                onToggleLed = onLedToggle
            )
        } else if (connectedDeviceAddress != null) {
            Button(
                onClick = { /* does nothing when disabled */ },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray,
                    contentColor = Color.White
                ),
                modifier = Modifier.padding(top = 16.dp),
                enabled = false
            ) {
                Text(
                    text = "$deviceName is Off",
                    fontSize = 18.sp
                )
            }
        }
    }
}

/**
 * Component that displays the connection status
 */
@Composable
fun ShowSelectDevice(
    isConnected: Boolean,
    connectedDeviceAddress: String?,
    onScanningDevicesClick: () -> Unit
) {
    Text(
        text = "No device connected",
        modifier = Modifier.padding(bottom = 16.dp),
        color = Color.Gray
    )

    Button(
        onClick = onScanningDevicesClick,
        modifier = Modifier.padding(bottom = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066CC))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                modifier = Modifier.padding(end = 8.dp)
            )
            Text("SELECT DEVICE")
        }
    }
}

/**
 * LED control screen
 */
@Composable
fun LedControlScreen(
    ledState: Boolean,
    isConnected: Boolean,
    deviceAddr: String, // Parameter for device name
    deviceName: String,
    onToggleLed: () -> Unit
) {


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Show connected device information
        DisplayConnectedDeviceInfo(deviceName, deviceAddr)

        // Use light bulb drawable instead of circle
        Image(
            painter = painterResource(
                id = if (ledState)
                    R.drawable.light_bulb_on
                else
                    R.drawable.light_bulb_off
            ),
            contentDescription = if (ledState) "LED is ON" else "LED is OFF",
            modifier = Modifier
                .size(120.dp)
//                .clickable(enabled = isConnected) { onToggleLed() }
        )

        // Status text below the light bulb
        Text(
            text = if (ledState) "ON" else "OFF",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp),
            color = if (ledState) Color(0xFF4CAF50) else Color.Gray
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Toggle button
        Button(
            onClick = onToggleLed,
            enabled = isConnected,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ledState) Color(0xFFE91E63) else Color(0xFF4CAF50),
                contentColor = Color.White
            )
        ) {
            Text(text = if (ledState) "Turn OFF" else "Turn ON")
        }


        // Connection status
        if (!isConnected) {
            Text(
                text = "Disconnected",
                color = Color.Red,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }


}

@Composable
private fun DisplayConnectedDeviceInfo(deviceName: String, deviceAddr: String) {
    ListItem(
        headlineContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE0E0E0)) // Different shading
                    .padding(8.dp),
                contentAlignment = Alignment.Center // Centers the text
            ) {
                Text(
                    "Connected to",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Black
                )
            }
        },
        supportingContent = {
            Column {
                Text(
                    deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Text(
                    deviceAddr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .border(1.dp, Color.LightGray), // Adds border around
        colors = ListItemDefaults.colors(containerColor = Color(0xFFF5F5F5))
    )
}


