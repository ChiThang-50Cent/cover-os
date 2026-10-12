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

# Update the boot-persistent watchdog, then start exactly one independent instance.
adb push root-watchdog.sh /data/local/tmp/cover-root-watchdog.sh >/dev/null
adb shell "su -c '
    set -e
    mkdir -p /data/adb/service.d /data/adb/cover-launcher
    chmod 700 /data/adb/cover-launcher
    if [ -x /data/adb/service.d/cover-launcher.sh ]; then
        /data/adb/service.d/cover-launcher.sh --stop
    fi
    cp /data/local/tmp/cover-root-watchdog.sh /data/adb/service.d/cover-launcher.sh
    chmod 700 /data/adb/service.d/cover-launcher.sh
    rm -f /data/local/tmp/cover-root-watchdog.sh
    /data/adb/service.d/cover-launcher.sh
'"

adb shell am start -n $PKG/.StartActivity

cat <<EOF

Root (su) is also required for Back/Home and for taking over from Samsung's cover home.
Allow it in the Magisk popup, or grant it once from a root shell:
  uid=\$(pm list packages -U $PKG | sed 's/.*uid://')
  magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (\$uid,2,0,1,1)"
EOF
