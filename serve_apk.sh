set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
APK_DIR="${APK_DIR:-${SCRIPT_DIR}/app/build/outputs/apk/release}"
PORT="${PORT:-8000}"

if [ ! -d "${APK_DIR}" ]; then
    printf "Error: Release APK directory not found at %s\n" "${APK_DIR}" >&2
    exit 1
fi

LAN_IP=""
if command -v python3 >/dev/null 2>&1; then
    LAN_IP=$(python3 -c '
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    s.connect(("8.8.8.8", 80))
    print(s.getsockname()[0])
except Exception:
    print("")
finally:
    s.close()
' 2>/dev/null || true)
fi

if [ -z "${LAN_IP}" ]; then
    LAN_IP="<YOUR_LOCAL_IP>"
fi

printf "Serving APK directory: %s\n" "${APK_DIR}"
printf "Enter this URL into AFTVnews Downloader on your Fire TV:\n"
printf "  http://%s:%s/app-release.apk\n\n" "${LAN_IP}" "${PORT}"

exec python3 -m http.server "${PORT}" --directory "${APK_DIR}"
