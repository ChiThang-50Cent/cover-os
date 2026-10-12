package dev.vmn.coverlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts {@link CoverService} after boot and after the app is reinstalled. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        c.startForegroundService(new Intent(c, CoverService.class));
    }
}
