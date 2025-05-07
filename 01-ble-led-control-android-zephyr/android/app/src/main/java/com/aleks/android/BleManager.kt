package com.aleks.android

//importa explicitando que o BleManager a ser estendido é o da Nordic
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import no.nordicsemi.android.ble.BleManager as NordicBleManager

/**
 * BLE communication manager to control the LED
 */
class BleManager(context: Context) : NordicBleManager(context) {
    companion object {
        private const val TAG = "BleManager"

        // UUIDs must match exactly the Zephyr firmware in main.c
        private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private val LED_CHAR_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    }

    private val handler = Handler(Looper.getMainLooper())

    // Connection state and LED state 
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _ledState = MutableStateFlow(false)
    val ledState: StateFlow<Boolean> = _ledState

    // Callback for disconnection events
    var onDisconnected: (() -> Unit)? = null

    // Callback for when services are discovered and compatibility is verified
    var onServicesDiscovered: ((Boolean) -> Unit)? = null

    // Flag to prevent duplicate callback calls
    private var serviceDiscoveryCallbackExecuted = false

    // GATT characteristics
    private var ledCharacteristic: BluetoothGattCharacteristic? = null

    // Add a flag to track the latest compatibility result
    private var lastCompatibilityResult: Boolean? = null

    // Add a flag to know if we're in the process of checking compatibility
    private var isCheckingCompatibility = false

    // Safer callback mechanism that persists
    private var servicesDiscoveredListener: ((Boolean) -> Unit)? = null

    enum class BleConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        SERVICES_DISCOVERING,
        READY,
        INCOMPATIBLE,
        CONNECTION_FAILED
    }

    var connectionStateListener: ((BleConnectionState, BluetoothDevice?) -> Unit)? = null

    private var connectedDevice: BluetoothDevice? =
        null // Adicione esta variável para armazenar o dispositivo conectado

    private val connectionObserver =
        object : no.nordicsemi.android.ble.observer.ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                Log.d(TAG, "Connecting to device: ${device.address}")
                notifyConnectionState(BleConnectionState.CONNECTING, device)
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                Log.d(TAG, "Connected to device: ${device.address}")
                _isConnected.value = true
                connectedDevice = device // Armazene o dispositivo conectado
                notifyConnectionState(BleConnectionState.CONNECTED, device)
                serviceDiscoveryCallbackExecuted = false
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                Log.d(TAG, "Disconnecting from device: ${device.address}")
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                Log.d(TAG, "Disconnected from device: ${device.address}, reason: $reason")
                _isConnected.value = false
                connectedDevice = null // Limpe o dispositivo conectado
                onDisconnected?.invoke()
                notifyConnectionState(BleConnectionState.DISCONNECTED, device)
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                // Device is connected and services are discovered
                Log.d(TAG, "Device ready: ${device.address}")

                // Subscribing to indications for the LED characteristic
                ledCharacteristic?.let { characteristic ->
                    enableIndications(characteristic)
                        .done {
                            Log.d(
                                TAG,
                                "Successfully subscribed to indications for LED characteristic"
                            )
                            // Set up a callback to handle incoming notifications
                            setNotificationCallback(characteristic)
                                .with { device, data ->
                                    if (data.value != null) {
                                        val receivedMessage =
                                            data.value!!.joinToString("") { byte ->
                                                byte.toInt().toString()
                                            }
                                        Log.d(TAG, "Received indication: $receivedMessage")
                                        _ledState.value = receivedMessage.toInt() != 0
                                    } else {
                                        Log.d(TAG, "Received empty indication")
                                    }
                                }
                        }
                        .fail { _, status ->
                            Log.e(TAG, "Failed to subscribe to indications, status: $status")
                        }
                        .enqueue()
//                notifyConnectionState(BleConnectionState.CONNECTED, device)
                }


                // Read initial LED state
                readLedState()
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Log.d(TAG, "Failed to connect to device: ${device.address}, reason: $reason")

                // If connection failed, mark as incompatible
                if (!serviceDiscoveryCallbackExecuted) {
                    Log.d(TAG, "Device failed to connect, considering incompatible")
                    safeExecuteServicesDiscoveredCallback(false)
                }

                onDisconnected?.invoke()
            }
        }

    // Method to safely execute service discovery callback
    private fun safeExecuteServicesDiscoveredCallback(isCompatible: Boolean) {
        if (!serviceDiscoveryCallbackExecuted) {
            serviceDiscoveryCallbackExecuted = true
            lastCompatibilityResult = isCompatible

            Log.d(TAG, "⚠️ EXECUTING service discovery callback: isCompatible = $isCompatible")

            // First try the transient callback
            if (onServicesDiscovered != null) {
                Log.d(TAG, "✅ Using transient onServicesDiscovered callback")
                handler.post {
                    try {
                        onServicesDiscovered?.invoke(isCompatible)
                        Log.d(TAG, "✅ Successfully invoked transient callback")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error with transient callback: ${e.message}")
                    }
                }
            }
            // Then always use the persistent callback as backup
            else if (servicesDiscoveredListener != null) {
                Log.d(TAG, "✅ Using persistent services discovered listener")
                handler.post {
                    try {
                        servicesDiscoveredListener?.invoke(isCompatible)
                        Log.d(TAG, "✅ Successfully invoked persistent callback")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error with persistent callback: ${e.message}")
                    }
                }
            } else {
                Log.e(TAG, "❌ BOTH callbacks are NULL! Cannot notify about compatibility!")
            }
        } else {
            Log.d(TAG, "⚠️ Service discovery callback already executed, ignoring")
        }
    }

    // Gets device characteristics and verifies compatibility with the service established in the Zephyr module
    override fun getGattCallback(): BleManagerGattCallback {
        return object : BleManagerGattCallback() {

            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                Log.d(TAG, "Checking for required service...")

                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    Log.d(TAG, "Service found: $SERVICE_UUID")
                    ledCharacteristic = service.getCharacteristic(LED_CHAR_UUID)

                    if (ledCharacteristic != null) {
                        val canRead =
                            ledCharacteristic?.properties?.and(BluetoothGattCharacteristic.PROPERTY_READ) != 0
                        val canWrite =
                            ledCharacteristic?.properties?.and(BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        val canIndicate =
                            ledCharacteristic?.properties?.and(BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

                        val ledCharPropertiesValid = canRead && canWrite && canIndicate

                        Log.d(TAG, "Device compatibility: $ledCharPropertiesValid")
                        safeExecuteServicesDiscoveredCallback(ledCharPropertiesValid)

                        return ledCharPropertiesValid
                    } else {
                        Log.d(TAG, "Characteristic NOT found: $LED_CHAR_UUID")
                        safeExecuteServicesDiscoveredCallback(false)
                        return false
                    }
                } else {
                    Log.d(TAG, "Service NOT found: $SERVICE_UUID")
                    safeExecuteServicesDiscoveredCallback(false)
                    return false
                }
            }

            override fun onServicesInvalidated() {
                Log.d(TAG, "Services invalidated")
                ledCharacteristic = null
            }
        }
    }

    // Reset state when connecting to a new device
    fun connectToDevice(device: BluetoothDevice) {

        // Close any existing connection
//        close()

        // Reset for new connection
        serviceDiscoveryCallbackExecuted = false
        lastCompatibilityResult = null
        isCheckingCompatibility = true

        Log.d(TAG, "Connecting to device: ${device.address}")

        // Connect with Nordic's BleManager
        setConnectionObserver(connectionObserver)
        connect(device)
            .timeout(5000) // 15 seconds timeout
            .retry(3, 2000) // Retry 3 times with 2 second delay
            .useAutoConnect(false) // Use direct connect for more reliability
            .enqueue()

        // Backup timer to ensure callback is always executed
        handler.postDelayed({
            if (!serviceDiscoveryCallbackExecuted) {
                Log.d(
                    TAG,
                    "Timeout for service discovery, forcing callback with incompatible result"
                )
                safeExecuteServicesDiscoveredCallback(false)
            }
        }, 10000) // 10 seconds maximum time for discovery
    }

    // Disconnect and clean up
    fun disconnectDevice() {
        disconnect().enqueue()
    }

    // Read LED state from device
    fun readLedState() {
        if (!isConnected.value || ledCharacteristic == null) {
            Log.e(TAG, "Cannot read LED state: not connected or characteristic not available")
            return
        }

        readCharacteristic(ledCharacteristic)
            .with { device, data ->
                if (data.value != null && data.value!!.isNotEmpty()) {
                    val ledValue = data.value!![0].toInt() != 0
                    Log.d(TAG, "Read LED state: $ledValue")
                    //Usado pra notificar a recomposição da UI
                    _ledState.value = ledValue
                }
            }
            .fail { device, status ->
                Log.e(TAG, "Failed to read LED characteristic, status: $status")
            }
            .enqueue()
    }

    // Toggle the LED state
    fun toggleLed() {
        if (!isConnected.value || ledCharacteristic == null) {
            Log.e(TAG, "Cannot toggle LED: not connected or characteristic not available")
            return
        }

        // Toggle the state
        val newState = !_ledState.value
        Log.d(TAG, "Toggling LED to: $newState")

        // Create data byte array (1 = on, 0 = off)
        val data = if (newState) byteArrayOf(1) else byteArrayOf(0)

        // Write to the characteristic
        writeCharacteristic(ledCharacteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .done {
                Log.d(TAG, "LED state changed to $newState")
                _ledState.value = newState
            }
            .fail { device, status ->
                Log.e(TAG, "Failed to write LED characteristic, status: $status")
            }
            .enqueue()
    }

    private fun notifyConnectionState(state: BleConnectionState, device: BluetoothDevice? = null) {
        handler.post {
            connectionStateListener?.invoke(state, device)
        }
    }

    fun getConnectedDeviceName(): String? {
        return connectedDevice?.name
    }

}
