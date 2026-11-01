package org.telegram.ui;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginHost;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Renders one plugin's settings rows produced by the engine.
public class ShuzaPluginSettingsActivity extends UniversalFragment {

    private final String pluginId;
    private final String title;
    private final ArrayList<Map<String, Object>> rows = new ArrayList<>();

    public ShuzaPluginSettingsActivity(String pluginId, String title) {
        this.pluginId = pluginId;
        this.title = title;
    }

    @Override
    protected CharSequence getTitle() {
        return title != null ? title : LocaleController.getString(R.string.ShuzaModPluginsTitle);
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }

    private static boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Boolean && (Boolean) v;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        rows.clear();
        int index = 0;
        for (Object o : PluginHost.createSettings(pluginId)) {
            Map<String, Object> m = (Map<String, Object>) o;
            rows.add(m);
            String type = str(m, "type", "text");
            String text = str(m, "text", "");
            switch (type) {
                case "header":
                    items.add(UItem.asHeader(text));
                    break;
                case "divider":
                    items.add(UItem.asShadow(str(m, "text", null)));
                    break;
                case "switch":
                    items.add(UItem.asCheck(index, text).setChecked(bool(m, "default")));
                    break;
                case "selector":
                    items.add(UItem.asButton(index, text, selectorLabel(m)));
                    break;
                default:
                    String value = str(m, "subtext", null);
                    if ("input".equals(type) || "edittext".equals(type)) value = str(m, "default", null);
                    items.add(value != null ? UItem.asButton(index, text, value) : UItem.asButton(index, text));
            }
            index++;
        }
        if (rows.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.ShuzaModPluginsEmpty)));
        }
    }

    @SuppressWarnings("unchecked")
    private String selectorLabel(Map<String, Object> m) {
        try {
            int idx = m.get("default") instanceof Number ? ((Number) m.get("default")).intValue() : 0;
            Object items = m.get("items");
            if (items instanceof List) {
                List<Object> l = (List<Object>) items;
                if (idx >= 0 && idx < l.size()) return String.valueOf(l.get(idx));
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= rows.size()) return;
        Map<String, Object> m = rows.get(item.id);
        String type = str(m, "type", "text");
        if ("switch".equals(type)) {
            boolean now = !bool(m, "default");
            m.put("default", now);
            String token = str(m, "on_change_token", null);
            if (token != null) PluginHost.invokeSetting(token, now);
            if (listView != null && listView.adapter != null) listView.adapter.update(true);
        } else if ("selector".equals(type)) {
            showSelector(m);
        } else if ("input".equals(type) || "edittext".equals(type)) {
            showInput(m);
        } else {
            String token = str(m, "_token", null);
            if (token == null) token = str(m, "on_click_token", null);
            if (token != null) PluginHost.invokeSetting(token, null);
            if (listView != null && listView.adapter != null) listView.adapter.update(true);
        }
    }

    @SuppressWarnings("unchecked")
    private void showSelector(Map<String, Object> m) {
        Object itemsObj = m.get("items");
        if (!(itemsObj instanceof List)) return;
        List<Object> l = (List<Object>) itemsObj;
        CharSequence[] options = new CharSequence[l.size()];
        for (int i = 0; i < l.size(); i++) options[i] = String.valueOf(l.get(i));
        String token = str(m, "on_change_token", null);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(str(m, "text", ""));
        b.setItems(options, (dialog, which) -> {
            m.put("default", which);
            if (token != null) PluginHost.invokeSetting(token, which);
            if (listView != null && listView.adapter != null) listView.adapter.update(true);
        });
        showDialog(b.create());
    }

    private void showInput(Map<String, Object> m) {
        Context context = getParentActivity();
        EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setText(str(m, "default", ""));
        int pad = AndroidUtilities.dp(22);
        edit.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(8));
        String token = str(m, "on_change_token", null);
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(str(m, "text", ""));
        b.setView(edit);
        b.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            String v = edit.getText().toString();
            m.put("default", v);
            if (token != null) PluginHost.invokeSetting(token, v);
            if (listView != null && listView.adapter != null) listView.adapter.update(true);
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
