package com.jijian.translate;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Test APK only: its own UID enables its otherwise disabled test service. */
public final class TestControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        boolean enabled=intent.getBooleanExtra("enabled",false);
        context.getPackageManager().setComponentEnabledSetting(new ComponentName(context,TestRecognitionService.class),enabled?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
        setResultData(enabled?"enabled":"disabled");
    }
}
