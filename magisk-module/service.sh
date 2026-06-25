#!/system/bin/sh
MODDIR="${0%/*}"
APK_PATH="$MODDIR/system/priv-app/MagcoolerBleService/MagcoolerBleService.apk"
PKG="com.magcooler.magiskble"
COMPONENT="$PKG/.MagcoolerBleService"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

sleep 8

if [ -f "$APK_PATH" ]; then
  pm install-existing --user 0 "$PKG" >/dev/null 2>&1
fi

pm grant "$PKG" android.permission.BLUETOOTH_SCAN >/dev/null 2>&1
pm grant "$PKG" android.permission.BLUETOOTH_CONNECT >/dev/null 2>&1
pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1

am start-foreground-service -n "$COMPONENT" >/dev/null 2>&1
