package com.exteragram.messenger.plugins.ui;

import android.view.View;

import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

// Simple plugin list: enable/disable, open settings, uninstall. Exists mainly
// so exteraGram plugins that import it resolve; the main list lives in the mod
// settings too.
public class PluginsActivity extends org.telegram.ui.Components.UniversalFragment {

    private final ArrayList<PluginsController.PluginWrapper> shown = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ShuzaModPluginsTitle);
    }

    @Override
    public boolean onFragmentCreate() {
        PluginsController.getInstance().refresh();
        return super.onFragmentCreate();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        shown.clear();
        items.add(UItem.asHeader(LocaleController.getString(R.string.ShuzaModPluginsTitle)));
        PluginsController controller = PluginsController.getInstance();
        controller.refresh();
        int index = 0;
        for (PluginsController.PluginWrapper w : controller.plugins.values()) {
            shown.add(w);
            String sub = w.version != null ? "v" + w.version : "";
            if (w.error != null && !w.error.isEmpty()) sub = w.error;
            items.add(UItem.asCheck(index, w.name == null ? w.id : w.name).setChecked(w.isEnabled()));
            index++;
        }
        if (shown.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.ShuzaModPluginsEmpty)));
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.ShuzaModPluginsHint)));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= shown.size()) return;
        PluginsController.PluginWrapper w = shown.get(item.id);
        PluginsController.getInstance().setPluginEnabled(w.id, !w.isEnabled());
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= shown.size()) return false;
        PluginsController.PluginWrapper w = shown.get(item.id);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(w.name == null ? w.id : w.name);
        b.setItems(new CharSequence[]{
                LocaleController.getString(R.string.ShuzaModPluginSettings),
                LocaleController.getString(R.string.ShuzaModPluginUninstall)
        }, (dialog, which) -> {
            if (which == 0) {
                presentFragment(new PluginSettingsActivity(w));
            } else {
                PluginsController.getInstance().uninstallPlugin(w.id);
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
            }
        });
        showDialog(b.create());
        return true;
    }
}
