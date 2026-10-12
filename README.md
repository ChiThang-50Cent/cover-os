# Cover Launcher

Launcher for the Galaxy Z Flip3 cover screen (display 512x260). Needs root.

- **Idle page** (page 0, the first thing you see): big clock, date, and the newest 3 notifications
  (tap one to open its app). Swipe left for the apps. Home and Back return here. Notification access is
  granted by `install.sh`.
- **Close all** button on the idle page: force-stops every app that has an activity on the cover screen
  (the launcher and system UI stay). It does not touch apps running only on the main screen.
- **App grid**: 4x2 icons per page, swipe sideways. Tap opens the app on the cover screen.
  Long-press pins/unpins an app (pinned apps come first, amber label).
- **Call assist** (folded phone only): when an activity whose class name matches `incall|voip|videocall` starts on the
  main screen, its task is moved to the cover screen; if the call uses the front camera (faces the inner screen when
  folded) it taps "switch camera" via uiautomator. Verified with Messenger only; the camera flip relies on Vietnamese
  Messenger labels. Samsung's own phone-call UI is left to Samsung.
- **Gestures** (invisible strips over every app): swipe in from the left or right edge = Back,
  swipe up from the bottom edge (white handle) = Home.
- **Always on**: when Samsung's cover home (`SubHomeActivity`) comes up, the launcher replaces it.
  An app already in front (a video call, say) is never disturbed. Starts at boot.
- Open the **Cover Launcher** icon on the main screen to start it manually.

## Build / install

```
./build.sh                                   # plain SDK tools, no Gradle; output build/CoverLauncher.apk
ANDROID_SERIAL=<serial> ./install.sh         # install, grant permissions, start
```

Root must be granted to `dev.vmn.coverlauncher` (Magisk popup, or the command `install.sh` prints).

## How it works

| Piece | Role |
|---|---|
| `LauncherActivity` | The grid. Launches apps with `ActivityOptions.setLaunchDisplayId` on its own display. |
| `CoverService` | Foreground service. Watches `logcat -b events` (via su) for `wm_on_top_resumed_gained_called` of the stock cover home and starts the launcher; hosts the gesture overlay. |
| `EdgeGestures` | `TYPE_APPLICATION_OVERLAY` strips on the cover display. Back = root `input -d <id> keyevent 4`; Home = root `am start --display <id>`. |
| `Root` | One persistent `su` shell. |
| `BootReceiver` | Starts the service after boot and after reinstall. |

The cover display can be hidden from `DisplayManager`, even from `cmd display get-displays`.
Its id is read from the base display records in `su -c "dumpsys display"` (smallest non-default
**internal** display). Virtual displays such as scrcpy are excluded so a temporary display id is
never cached as the cover screen.

## Known limits

- Samsung Settings hides overlay windows, so the Back/Home strips do not work while Settings is in front.
- The cover has a keyguard when the phone is closed. With a swipe-only lock the service dismisses it automatically
  (folded + awake + keyguard showing). With a PIN/pattern it does nothing: you must unlock before the launcher shows.
- Gesture strips swallow touches on the outer 20 px (sides) and 16 px (bottom) of every app.
- `install.sh` puts the app on the battery-optimisation allowlist. Without it Android 15 refuses to start the
  foreground service from a background broadcast (`ForegroundServiceStartNotAllowedException`).
