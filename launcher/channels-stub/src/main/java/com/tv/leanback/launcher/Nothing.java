package com.tv.leanback.launcher;

import android.app.Activity;
import android.os.Bundle;

/** Closes at once; it is here only so the package is visible to other apps (AndroidManifest.xml). */
public class Nothing extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
