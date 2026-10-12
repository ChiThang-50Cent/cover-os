package dev.vmn.coverlauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Icon on the main screen: starts the service, which opens the launcher on the cover display. */
public final class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startForegroundService(new Intent(this, CoverService.class).putExtra(CoverService.EXTRA_SHOW, true));
        finish();
    }
}
