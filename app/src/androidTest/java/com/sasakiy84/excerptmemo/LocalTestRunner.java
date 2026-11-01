package com.sasakiy84.excerptmemo;

import android.os.SystemClock;
import androidx.test.runner.AndroidJUnitRunner;

/** Wait for the automation service to finish connecting on software-only emulators. */
public final class LocalTestRunner extends AndroidJUnitRunner {
    @Override public void onStart() {
        if (!"false".equals(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("localUiAutomation"))) {
            long deadline = SystemClock.uptimeMillis() + 240000;
            while (getUiAutomation() == null) {
                if (SystemClock.uptimeMillis() >= deadline) throw new IllegalStateException("UiAutomation could not connect");
                SystemClock.sleep(500);
            }
        }
        super.onStart();
    }
}
