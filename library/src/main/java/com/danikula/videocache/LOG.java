package com.danikula.videocache;

import android.util.Log;

public class LOG {

    public static final String  LOG_TAG = "@AndroidVideoCache";
    public static       boolean DEBUG   = false;

    private LOG() {

    }

    public static final void v(String log) {
        if (DEBUG)
            Log.v(LOG_TAG, log);
    }

    public static final void debug(String log) {
        if (DEBUG)
            Log.d(LOG_TAG, log);
    }

    public static final void info(String log) {
        if (DEBUG)
            Log.i(LOG_TAG, log);
    }

    public static final void warn(String log) {
        if (DEBUG)
            Log.w(LOG_TAG, log);
    }

    public static final void warn(String log, String message) {
        warn(log + "\n" + message);
    }

    public static final void warn(String log, String title, String message) {
        warn(log + "\n" + title + "\n" + message);
    }

    public static final void warn(String log, Throwable e) {
        warn(log);
        e.printStackTrace();
    }

    public static final void error(String log) {
        Log.e(LOG_TAG, "" + log);
    }

    public static final void error(String log, Throwable e) {
        error(log);
        e.printStackTrace();
    }
}
