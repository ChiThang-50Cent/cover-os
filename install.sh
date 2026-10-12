#!/usr/bin/env bash
# Installs the built APK, grants the permissions that need no UI, and starts the launcher on the cover screen.
# With several adb transports to the same phone, pick one: ANDROID_SERIAL=<serial> ./install.sh
set -euo pipefail
cd "$(dirname "$0")"

PKG=dev.vmn.coverlauncher

adb install -r build/CoverLauncher.apk
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell "su -c 'dumpsys deviceidle whitelist +$PKG'" >/dev/null
adb shell "su -c 'cmd notification allow_listener $PKG/$PKG.NotifService'"
adb shell am start -n $PKG/.StartActivity

cat <<EOF

Root (su) is also required for Back/Home and for taking over from Samsung's cover home.
Allow it in the Magisk popup, or grant it once from a root shell:
  uid=\$(pm list packages -U $PKG | sed 's/.*uid://')
  magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (\$uid,2,0,1,1)"
EOF
