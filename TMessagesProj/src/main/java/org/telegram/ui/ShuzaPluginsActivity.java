package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginHost;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// ShuzaGram Mod plugin list, backed by the plugin engine through PluginHost.
public class ShuzaPluginsActivity extends UniversalFragment {

    private final ArrayList<Map<String, Object>> shown = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ShuzaModPluginsTitle);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Boolean && (Boolean) v;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        shown.clear();
        items.add(UItem.asHeader(LocaleController.getString(R.string.ShuzaModPluginsTitle)));
        int index = 0;
        for (Object o : PluginHost.listPlugins()) {
            Map<String, Object> m = (Map<String, Object>) o;
            shown.add(m);
            UItem row = UItem.asCheck(index, str(m, "name") != null ? str(m, "name") : str(m, "id")).setChecked(bool(m, "enabled"));
            items.add(row);
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
        Map<String, Object> m = shown.get(item.id);
        PluginHost.setEnabled(str(m, "id"), !bool(m, "enabled"));
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= shown.size()) return false;
        Map<String, Object> m = shown.get(item.id);
        final String id = str(m, "id");
        final String name = str(m, "name") != null ? str(m, "name") : id;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(name);
        String error = str(m, "error");
        CharSequence[] options = new CharSequence[]{
                LocaleController.getString(R.string.ShuzaModPluginSettings),
                LocaleController.getString(R.string.ShuzaModPluginUninstall)
        };
        b.setItems(options, (dialog, which) -> {
            if (which == 0) {
                presentFragment(new ShuzaPluginSettingsActivity(id, name));
            } else {
                PluginHost.uninstall(id);
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
            }
        });
        if (error != null && !error.isEmpty()) {
            b.setMessage(error);
        }
        showDialog(b.create());
        return true;
    }
}
