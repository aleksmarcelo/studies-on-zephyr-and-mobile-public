#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>


LOG_MODULE_REGISTER(main, LOG_LEVEL_INF);

#define LED_NODE DT_ALIAS(led0)
#if DT_NODE_HAS_STATUS(LED_NODE, okay)
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(LED_NODE, gpios);
static bool led_state;
#else
#error "Unsupported board: led0 alias is not defined"
#endif

#define BUTTON_NODE DT_ALIAS(sw0)
#if DT_NODE_HAS_STATUS(BUTTON_NODE, okay)
static const struct gpio_dt_spec button = GPIO_DT_SPEC_GET(BUTTON_NODE, gpios);
#else
#error "Unsupported board: sw0 alias is not defined"
#endif

/* Custom Service and Characteristic UUIDs */
#define BT_UUID_LED_SERVICE BT_UUID_DECLARE_16(0xFFF0)
#define BT_UUID_LED_CHAR BT_UUID_DECLARE_16(0xFFF1)

/*
 * led_char_value store the current value of the BLE LED characteristic
 * It is like a bridge between the led state in firmware and the value exposed by BLE
 * - When a BLE client read the characteristic, this value is returned
 * - When the LED is changed (by button or BLE), this value is updated too
 */
static uint8_t led_char_value = 0;

/*
 * The connection reference (indicate_conn) is used to keep which BLE client
 * enabled indications on the characteristic. This allow:
 * - Send indications only for the subscribed client, using bt_gatt_indicate(indicate_conn, ...)
 * - Manage the connection life cycle (reference and release)
 * - Avoid send indications for not subscribed or already disconnected clients
 *
 * If you pass NULL to bt_gatt_indicate, Zephyr send to all subscribed clients
 * If you want send to a specific client, use the connection reference
 */
/* Store connection for indication */
static struct bt_conn *indicate_conn = NULL;
 
/*
 * To support multiple clients subscribed for indication, don't use indicate_conn
 * Instead, call bt_gatt_indicate(NULL, ...) so Zephyr send to all clients that enabled indication
 */

/* Read callback: returns current LED state */
static ssize_t read_led(struct bt_conn *conn,
                        const struct bt_gatt_attr *attr,
                        void *buf, uint16_t len,
                        uint16_t offset) {
  const uint8_t *value = attr->user_data;
  return bt_gatt_attr_read(conn, attr, buf, len, offset, value, sizeof(*value));
}

static void send_led_indication(uint8_t value);

/* Write callback: set LED GPIO and update value */
static ssize_t write_led(struct bt_conn *conn,
                         const struct bt_gatt_attr *attr,
                         const void *buf, uint16_t len,
                         uint16_t offset, uint8_t flags) {
  uint8_t val = *((uint8_t *)buf);

  if (val > 1) {
    return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
  }

  led_state = (val == 1);
  gpio_pin_set(led.port, led.pin, led_state);
  memcpy(attr->user_data, &led_state, sizeof(led_state));
  led_char_value = led_state;

  LOG_INF("Led state set to %d", led_state);

  // Send indication for all connected clients
  send_led_indication(led_state);

  return len;
}

/* GATT CCC (Client Characteristic Configuration) for indication */
static void led_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value) {
  // Manage explicit the indication request from a client (just log for Zephyr 3.7)
  if (value == BT_GATT_CCC_INDICATE) {
    LOG_INF("Client ask indication for LED");
    // logic to register that have at least one client subscribed,
    // if you want control indication sending manually
  } else {
    LOG_INF("Client cancel indication for LED");
    // Here you can clear flags or resources, if needed
  }
}

/*
 * To require pairing and/or authentication to access the BLE characteristic,
 * change the permissions below for one of the commented options:
 *
 * - BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT
 *   Need paired (encrypted) BLE connection for read and write
 *
 * - BT_GATT_PERM_READ_AUTHEN | BT_GATT_PERM_WRITE_AUTHEN
 *   Need authenticated pairing (like PIN or passkey) for read and write
 *
 * Example usage:
 * BT_GATT_CHARACTERISTIC(BT_UUID_LED_CHAR,
 *                        BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE,
 *                        BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT,
 *                        read_led, write_led, &led_char_value),
 */

/* GATT service definition */
BT_GATT_SERVICE_DEFINE(led_svc,
                       BT_GATT_PRIMARY_SERVICE(BT_UUID_LED_SERVICE),
                       BT_GATT_CHARACTERISTIC(BT_UUID_LED_CHAR,
                                              BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE | BT_GATT_CHRC_INDICATE,
                                              BT_GATT_PERM_READ | BT_GATT_PERM_WRITE,  // Default permissions (no pairing)
                                              // BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT, // Need pairing
                                              // BT_GATT_PERM_READ_AUTHEN | BT_GATT_PERM_WRITE_AUTHEN,   // Need authenticated pairing
                                              read_led, write_led, &led_char_value),
                       BT_GATT_CCC(led_ccc_cfg_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE), );

/* Function to send BLE indication */
static void send_led_indication(uint8_t value) {
  static struct bt_gatt_indicate_params ind_params;
  ind_params.attr = &led_svc.attrs[2];  // LED characteristic
  ind_params.func = NULL;
  ind_params.data = &value;
  ind_params.len = sizeof(value);

  // Send indication for all subscribed clients
  bt_gatt_indicate(NULL, &ind_params);
}

/* Connection callbacks (optional: for logging) */
static void connected(struct bt_conn *conn, uint8_t err) {
  if (!err) {
    LOG_INF("BLE Connected");
  }
}
static void disconnected(struct bt_conn *conn, uint8_t reason) {
  LOG_INF("BLE Disconnected (reason 0x%02x)", reason);

}
BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected = connected,
    .disconnected = disconnected,
};

static void toggle_led(void) {
  led_state = !led_state;
  gpio_pin_set(led.port, led.pin, led_state);
  led_char_value = led_state;
}

// Custom advertising params: min and max 1b = 0.625ms
static const struct bt_le_adv_param adv_params = {
    .options = BT_LE_ADV_OPT_CONNECTABLE | BT_LE_ADV_OPT_USE_NAME,
    .interval_min = 0x0020,  // 20ms = (32 * 0.625ms)
    .interval_max = 0x0020,  
    .id = BT_ID_DEFAULT,
};


int init(void) {
  int err;

  /* Config LED GPIO */
  if (!device_is_ready(led.port)) {
    LOG_ERR("LED device not ready");
    return -ENODEV;
  }
  gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);

  /* Config Button GPIO */
  if (!device_is_ready(button.port)) {
    LOG_ERR("Button device not ready");
    return -ENODEV;
  }

  // Configure button as input with pull-up resistor
  gpio_pin_configure_dt(&button, GPIO_INPUT | GPIO_PULL_UP);

  /* Enable Bluetooth subsystem */
  err = bt_enable(NULL);
  if (err) {
    LOG_ERR("Bluetooth init failed (err %d)", err);
    return err;
  }
  LOG_INF("Bluetooth initialized");
  LOG_INF("Device name: %s", bt_get_name());

  /* Start advertising with custom interval */
  err = bt_le_adv_start(&adv_params, NULL, 0, NULL, 0);
  if (err) {
    LOG_ERR("Advertising failed to start (err %d)", err);
    return -1;
  }
  LOG_INF("Advertising successfully started");

  bool last_button = 1;
  while (true) {
    int val = gpio_pin_get_dt(&button);
    if (val < 0) {
      LOG_ERR("Error reading button");
    } else {
      // Detect transition from pressed (0) to released (1)
      if (last_button == 0 && val == 1) {
        toggle_led();
        printk("Button released: LED now %s\n", led_state ? "ON" : "OFF");
        send_led_indication(led_state);
      }
      last_button = val;
    }
    k_msleep(50);
  }

  return 0;
}


int main(void) {
    // if everthing is ok, the led will never blink
    int ret = init();
    //blink indefinite the led to indicate error
    while (ret != 0) {
        led_state = !led_state;
        gpio_pin_set(led.port, led.pin, led_state);
        k_msleep(200);
    }
return 0;

}

