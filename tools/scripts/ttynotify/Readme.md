# ttynotify

`ttynotify` is a command-line utility for monitoring and notifying about serial port (TTY) connections on Linux systems. It allows you to quickly identify which devices are connected to the system's serial ports, making it easier to diagnose and track connected devices.

## Usage

Execute o utilitário diretamente no terminal:

```bash
./ttynotify
```

save `ttynotify.desktop` on `~/.config/autostart/` to run it automatically on startup (note: replace exec path): 
```
#!/usr/bin/env xdg-open
[Desktop Entry]
Type=Application
Name=ttynotify
Exec=/home/aleks/bin/scripts/ttynotify.sh
X-GNOME-Autostart-enabled=true
```


![](resources/images/1-port-conn.png)

![](resources/images/2-ports-conn.png)

![](resources/images/no-port-conn.png)