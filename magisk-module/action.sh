#!/system/bin/sh
PKG="com.magcooler.magiskble"
PREFIX="com.magcooler.magiskble.action"
CFG="/data/adb/modules/binghuan_charge_cooler/config.prop"
STATE="/data/adb/binghuan_charge_cooler/state.json"

ensure_service() {
  am start-foreground-service --user 0 -n "$PKG/.MagcoolerBleService" >/dev/null 2>&1
}

send() {
  ensure_service
  am broadcast --user 0 -n "$PKG/.CommandReceiver" -a "$1" ${2:-} >/dev/null 2>&1
}

save_cfg() {
  key="$(echo "$1" | tr -cd 'a-z_')"
  val="$2"
  [ -n "$key" ] || exit 1
  mkdir -p "$(dirname "$CFG")"
  [ -f "$CFG" ] || cp /data/adb/modules/binghuan_charge_cooler/config.prop "$CFG" 2>/dev/null || touch "$CFG"
  if grep -q "^$key=" "$CFG" 2>/dev/null; then
    sed -i "s/^$key=.*/$key=$val/" "$CFG"
  else
    echo "$key=$val" >> "$CFG"
  fi
}

case "${1:-status}" in
  set-level) send "$PREFIX.SET_LEVEL" "--ei level ${2:-6}" ;;
  boost) [ "${2:-on}" = "off" ] && send "$PREFIX.BOOST" "--ez on false" || send "$PREFIX.BOOST" "--ez on true" ;;
  smart) [ "${2:-on}" = "off" ] && send "$PREFIX.SMART" "--ez on false" || send "$PREFIX.SMART" "--ez on true" ;;
  turn-on) send "$PREFIX.TURN_ON" ;;
  turn-off) send "$PREFIX.TURN_OFF" ;;
  refresh) send "$PREFIX.REFRESH" ;;
  reconnect) send "$PREFIX.RECONNECT" ;;
  save) save_cfg "${2:-}" "${3:-}" ;;
  status) cat "$STATE" 2>/dev/null || echo '{"connected":false,"status":"暂无状态"}' ;;
  *) echo "Usage: $0 {set-level N|boost on/off|smart on/off|turn-on|turn-off|refresh|reconnect|save key value|status}" ;;
esac
