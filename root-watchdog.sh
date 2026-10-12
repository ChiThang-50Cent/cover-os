#!/system/bin/sh
# Independent Magisk service.d watchdog; heartbeat uses elapsedRealtime seconds.
set -u
SCRIPT=/data/adb/service.d/cover-launcher.sh
STATE=/data/adb/cover-launcher
PKG=dev.vmn.coverlauncher
HEARTBEAT=/data/user/0/$PKG/files/heartbeat
POLL=5
STALE=30
BACKOFF=30

watchdog_pid() {
    pid=
    [ -r "$STATE/watchdog.pid" ] && read -r pid < "$STATE/watchdog.pid"
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    [ -r "/proc/$pid/cmdline" ] || return 1
    command_line=$(tr '\000' ' ' < "/proc/$pid/cmdline")
    case "$command_line" in *"$SCRIPT --daemon"*) return 0 ;; *) return 1 ;; esac
}

case "${1:-}" in
    --stop)
        if watchdog_pid; then
            kill -TERM "$pid"
            for attempt in 1 2 3 4 5; do
                kill -0 "$pid" 2>/dev/null || break
                sleep 1
            done
            watchdog_pid && kill -KILL "$pid" 2>/dev/null
        fi
        exit 0 ;;
    --status)
        if watchdog_pid; then cat "$STATE/watchdog.status"; else echo state=stopped; fi
        exit 0 ;;
    --daemon) ;;
    '')
        nohup /system/bin/sh "$SCRIPT" --daemon </dev/null >/dev/null 2>&1 &
        exit 0 ;;
    *) echo "Usage: $SCRIPT [--status|--stop]" >&2; exit 2 ;;
esac

mkdir -p "$STATE"
chmod 700 "$STATE"
exec 9>"$STATE/watchdog.lock"
# mksh keeps extra descriptors private unless explicitly passed to the child.
/system/bin/flock -n 9 9>&9 || exit 0
printf '%s\n' "$$" > "$STATE/watchdog.pid"
trap 'rm -f "$STATE/watchdog.pid"; exit 0' TERM INT HUP

log() {
    if [ -f "$STATE/watchdog.log" ] && [ "$(stat -c %s "$STATE/watchdog.log")" -gt 102400 ]; then
        mv -f "$STATE/watchdog.log" "$STATE/watchdog.log.previous"
    fi
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$STATE/watchdog.log"
}

status() {
    printf 'watchdog_pid=%s\nstate=%s\n%s\n' "$$" "$1" "$2" > "$STATE/watchdog.status.tmp"
    mv -f "$STATE/watchdog.status.tmp" "$STATE/watchdog.status"
}

uptime_seconds() {
    read -r now unused < /proc/uptime
    now=${now%%.*}
}

app_pids() {
    pids=
    for candidate in $(pidof "$PKG" 2>/dev/null); do
        [ -r "/proc/$candidate/cmdline" ] || continue
        name=$(tr '\000' '\n' < "/proc/$candidate/cmdline")
        [ "$name" = "$PKG" ] && pids="$pids $candidate"
    done
}

status waiting_for_unlock 'reason=boot_or_credential_storage'
while [ "$(getprop sys.boot_completed)" != 1 ] ||
        [ "$(/system/bin/timeout 5 am get-started-user-state 0 2>/dev/null)" != RUNNING_UNLOCKED ]; do
    sleep "$POLL" 9>&- &
    wait $! || true
done
log 'Boot and credential storage ready'
last_start=0
while true; do
    uptime_seconds
    app_pids
    hb=
    [ -r "$HEARTBEAT" ] && read -r hb < "$HEARTBEAT"
    age=-1
    case "$hb" in ''|*[!0-9]*) ;; *) age=$((now - hb)) ;; esac
    if [ -n "$pids" ] && [ "$age" -ge 0 ] && [ "$age" -le "$STALE" ]; then
        status healthy "app_pids=$pids heartbeat_age=$age"
    elif [ "$last_start" -ne 0 ] && [ $((now - last_start)) -lt "$BACKOFF" ]; then
        status starting "app_pids=$pids heartbeat_age=$age"
    else
        log "Recovering: app_pids=$pids heartbeat_age=$age"
        status recovering "app_pids=$pids heartbeat_age=$age"
        # SIGKILL works even for SIGSTOP or an unresponsive main/worker thread.
        for candidate in $pids; do kill -KILL "$candidate" 2>/dev/null || true; done
        /system/bin/timeout 5 am force-stop --user 0 "$PKG" >/dev/null 2>&1 || true
        rm -f "$HEARTBEAT"
        if ! /system/bin/timeout 10 am start-foreground-service --user 0 -n "$PKG/.CoverService" >> "$STATE/watchdog.log" 2>&1; then
            log 'Foreground service start failed; will retry after backoff'
        fi
        uptime_seconds
        last_start=$now
    fi
    # Do not let an orphaned sleep retain the singleton lock during replacement.
    sleep "$POLL" 9>&- &
    wait $! || true
done
