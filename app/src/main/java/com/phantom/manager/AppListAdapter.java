package com.phantom.manager;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Launcher-app list with a per-app enable checkbox. Long-press an entry to
 * open its per-app profile editor.
 */
public class AppListAdapter extends BaseAdapter {

    private final Activity activity;
    private final ConfigStore store;
    private final List<AppEntry> items = new ArrayList<AppEntry>();
    private final PackageManager pm;

    public AppListAdapter(Activity activity, ConfigStore store) {
        this.activity = activity;
        this.store = store;
        this.pm = activity.getPackageManager();
    }

    public void reload() {
        final List<AppEntry> found = new ArrayList<AppEntry>();
        try {
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            for (ApplicationInfo info : installed) {
                if (pm.getLaunchIntentForPackage(info.packageName) == null) continue;
                boolean enabled = store.isEnabled(info.packageName);
                found.add(new AppEntry(info.packageName,
                        info.loadLabel(pm).toString(),
                        info.loadIcon(pm), enabled));
            }
            Collections.sort(found, (a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Exception ignored) {
        }
        activity.runOnUiThread(() -> {
            items.clear();
            items.addAll(found);
            notifyDataSetChanged();
        });
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private static class Holder {
        CheckBox box;
        ImageView icon;
        TextView label;
        TextView pkg;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final AppEntry entry = items.get(position);
        View v = convertView;
        Holder h;
        if (v == null) {
            v = LayoutInflater.from(activity).inflate(R.layout.item_app, parent, false);
            h = new Holder();
            h.box = v.findViewById(R.id.check);
            h.icon = v.findViewById(R.id.icon);
            h.label = v.findViewById(R.id.label);
            h.pkg = v.findViewById(R.id.pkg);
            v.setTag(h);
        } else {
            h = (Holder) v.getTag();
        }

        h.icon.setImageDrawable(entry.icon);
        h.label.setText(entry.label);
        h.pkg.setText(entry.pkg);

        // guard so programmatic setChecked doesn't fire a write
        CompoundButton.OnCheckedChangeListener listener =
                (buttonView, isChecked) -> {
                    if (buttonView.getTag() == null || !buttonView.getTag().equals(entry.pkg)) {
                        return;
                    }
                    entry.enabled = isChecked;
                    new Thread(() -> {
                        store.setEnabled(entry.pkg, isChecked);
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                isChecked ? "Phantom enabled for " + entry.label
                                          : "Phantom disabled for " + entry.label,
                                Toast.LENGTH_SHORT).show());
                    }).start();
                };

        h.box.setTag(entry.pkg);
        h.box.setOnCheckedChangeListener(null);
        h.box.setChecked(entry.enabled);
        h.box.setOnCheckedChangeListener(listener);
        return v;
    }
}
