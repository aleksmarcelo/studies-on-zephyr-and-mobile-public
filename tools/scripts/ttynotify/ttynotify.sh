#!/bin/bash

# Autor: Marcelo Aleksandravicius

# Dipendenza
#sudo apt install inotify-tools


echo "Monitoring /dev for serial device connections..."

# Lista formattata
list_serial_devices() {
  local ports
  ports=$(ls /dev/ttyUSB* /dev/ttyACM* 2>/dev/null | sort)
  if [[ -z "$ports" ]]; then
    echo "<b>Serial ports:</b>\nNone detected"
  else
    echo -e "<b>Serial ports detected:</b>\n$ports"
  fi
}

inotifywait -m /dev -e create -e delete |
while read path action file; do
  if [[ "$file" == ttyUSB* || "$file" == ttyACM* ]]; then
    current_list=$(list_serial_devices)

    if [[ "$action" == *CREATE* ]]; then
      echo "[+] Connected: /dev/$file"
      notify-send --icon=dialog-information --hint=int:transient:1 \
        --app-name="TTY Notify" --urgency=normal \
        --expire-time=3000 \
        --category=dev \
        -- "Connected: /dev/$file" "$current_list"
    elif [[ "$action" == *DELETE* ]]; then
      echo "[-] Disconnected: /dev/$file"
      notify-send --icon=dialog-warning --hint=int:transient:1 \
        --app-name="TTY Notify" --urgency=normal \
        --expire-time=3000 \
        --category=dev \
        -- "Disconnected: /dev/$file" "$current_list"
    fi

    # Mostra anche il conteggio nel terminale
    count=$(echo "$current_list" | grep -E '^/dev/' | wc -l)
    echo "[=] Serial devices connected: $count"
  fi
done

