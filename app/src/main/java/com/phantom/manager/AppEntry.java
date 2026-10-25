package com.phantom.manager;

import android.graphics.drawable.Drawable;

public class AppEntry {
    public final String pkg;
    public final String label;
    public final Drawable icon;
    public boolean enabled;

    public AppEntry(String pkg, String label, Drawable icon, boolean enabled) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
        this.enabled = enabled;
    }
}
