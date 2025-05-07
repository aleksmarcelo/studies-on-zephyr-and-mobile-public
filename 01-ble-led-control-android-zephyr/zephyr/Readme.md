# Project: LED Control by BLE on ESP32 (Zephyr)

This project show how to control a LED on ESP32 DevKitC board using Bluetooth Low Energy. 


## Prerequisites

- Zephyr RTOS 3.7.1 **Attention!**  
- ESP32 DevKitC (rev >= 0)
- west (Zephyr build tool)
- Toolchain for ESP32

## Steps to build and flash
```sh
# Download the binary blobs needed for ESP32 HAL
west blobs fetch hal_espressif

# Build the project for ESP32 DevKitC WROOM board
west build -b esp32_devkitc_wroom/esp32/procpu -p auto

# Flash the firmware to the board (use --runner esp32)
west flash --runner esp32
```

## Notes

- The BLE device name is set in `prj.conf` (like: `CONFIG_BT_DEVICE_NAME="MyLED"`)
- BLE advertising use fast interval to make easy to find by Android apps

