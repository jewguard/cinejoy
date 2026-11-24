set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
TARGET_IP="${1:-${FIRE_TV_IP:-}}"
TARGET_PORT="${FIRE_TV_PORT:-5555}"
APK_PATH="${APK_PATH:-${SCRIPT_DIR}/app/build/outputs/apk/release/app-release.apk}"
PACKAGE_NAME="pk.cinejoy.tv"

if [ -z "${TARGET_IP}" ]; then
    printf "Enter target Fire TV IP address: "
    read -r TARGET_IP
fi

if [ -z "${TARGET_IP}" ]; then
    printf "Error: Target device IP address is required.\n" >&2
    exit 1
fi

if [ ! -f "${APK_PATH}" ]; then
    printf "Error: APK not found at %s\n" "${APK_PATH}" >&2
    exit 1
fi

DEVICE_ADDR="${TARGET_IP}:${TARGET_PORT}"
printf "Connecting to %s...\n" "${DEVICE_ADDR}"
adb connect "${DEVICE_ADDR}"

printf "Installing %s onto %s...\n" "${APK_PATH}" "${DEVICE_ADDR}"
adb -s "${DEVICE_ADDR}" install -r "${APK_PATH}"

printf "Launching %s...\n" "${PACKAGE_NAME}"
adb -s "${DEVICE_ADDR}" shell "am start -n ${PACKAGE_NAME}/.MainActivity || monkey -p ${PACKAGE_NAME} -c android.intent.category.LAUNCHER 1"
