#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_SRC="${APK_SRC:-}"
if [ -z "$APK_SRC" ]; then
  APK_SRC="$(find "$ROOT_DIR/magisk-ble-service/build/outputs/apk/release" -maxdepth 1 -type f -name '*.apk' | head -n 1)"
fi
MODULE_DIR="$ROOT_DIR/magisk-module"
STAGE_DIR="$ROOT_DIR/magisk-module-build"
ZIP_NAME="BinghuanChargeCooler.zip"

rm -rf "$STAGE_DIR"
if [ -z "$APK_SRC" ] || [ ! -f "$APK_SRC" ]; then
  echo "Release APK not found. Run ./gradlew :magisk-ble-service:assembleRelease first." >&2
  exit 1
fi
mkdir -p "$STAGE_DIR/system/priv-app/MagcoolerBleService" "$STAGE_DIR/system/etc/binghuan_charge_cooler"

cp "$APK_SRC" "$STAGE_DIR/system/priv-app/MagcoolerBleService/MagcoolerBleService.apk"
cp "$MODULE_DIR/module.prop" "$STAGE_DIR/module.prop"
cp "$MODULE_DIR/service.sh" "$STAGE_DIR/service.sh"
cp "$MODULE_DIR/customize.sh" "$STAGE_DIR/customize.sh"
cp "$MODULE_DIR/uninstall.sh" "$STAGE_DIR/uninstall.sh"
cp "$MODULE_DIR/config.prop" "$STAGE_DIR/config.prop"
cp "$MODULE_DIR/config.prop" "$STAGE_DIR/system/etc/binghuan_charge_cooler/config.prop"
if [ -f "$MODULE_DIR/action.sh" ]; then cp "$MODULE_DIR/action.sh" "$STAGE_DIR/action.sh"; fi
if [ -d "$MODULE_DIR/webroot" ]; then cp -a "$MODULE_DIR/webroot" "$STAGE_DIR/webroot"; fi

chmod 0755 "$STAGE_DIR/service.sh" "$STAGE_DIR/customize.sh" "$STAGE_DIR/uninstall.sh"
if [ -f "$STAGE_DIR/action.sh" ]; then chmod 0755 "$STAGE_DIR/action.sh"; fi

( cd "$STAGE_DIR" && zip -qr "$ROOT_DIR/$ZIP_NAME" . )

echo "$ROOT_DIR/$ZIP_NAME"
