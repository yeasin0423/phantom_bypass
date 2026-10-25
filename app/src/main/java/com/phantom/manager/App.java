package com.phantom.manager;

import android.content.Context;

/**
 * Tiny Application holder so Su helpers can reach a temp directory
 * from anywhere.
 */
public final class App extends android.app.Application {

    private static Context sCtx;

    @Override
    public void onCreate() {
        super.onCreate();
        sCtx = this;
    }

    public static Context ctx() {
        return sCtx;
    }
}
