package com.exteragram.messenger.plugins.ui;

import android.content.Context;
import android.view.View;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Renders one plugin's create_settings() rows. Also the reflection target that
// some plugins hook (createView / fillItems).
public class PluginSettingsActivity extends org.telegram.ui.Components.UniversalFragment {

    private final String pluginId;
    private String title = "";
    private final ArrayList<Map<String, Object>> rows = new ArrayList<>();

    public PluginSettingsActivity(String pluginId) {
        this.pluginId = pluginId;
    }

    public PluginSettingsActivity(com.exteragram.messenger.plugins.PluginsController.PluginWrapper w) {
        this.pluginId = w.id;
        this.title = w.name != null ? w.name : w.id;
    }

    private static PyObject engine() {
        return Python.getInstance().getModule("shuza_plugins.engine");
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Boolean && (Boolean) v;
    }

    @Override
    protected CharSequence getTitle() {
        return title == null || title.isEmpty() ? LocaleController.getString(R.string.ShuzaModTitle) : title;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        rows.clear();
        try {
            List<?> list = engine().callAttr("create_settings", pluginId).toJava(List.class);
            int index = 0;
            for (Object o : list) {
                @SuppressWarnings("unchecked")
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
                    default:
                        String value = str(m, "subtext", null);
                        if ("selector".equals(type)) {
                            value = currentSelectorLabel(m);
                        }
                        if (value != null) {
                            items.add(UItem.asButton(index, text, value));
                        } else {
                            items.add(UItem.asButton(index, text));
                        }
                }
                index++;
            }
        } catch (Throwable t) {
            FileLog.e(t);
            items.add(UItem.asShadow("Plugin settings error: " + t));
        }
    }

    @SuppressWarnings("unchecked")
    private String currentSelectorLabel(Map<String, Object> m) {
        try {
            int idx = 0;
            Object d = m.get("default");
            if (d instanceof Number) idx = ((Number) d).intValue();
            Object items = m.get("items");
            if (items instanceof List) {
                List<Object> list = (List<Object>) items;
                if (idx >= 0 && idx < list.size()) return String.valueOf(list.get(idx));
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
        String key = str(m, "key", null);
        try {
            if ("switch".equals(type)) {
                boolean now = !bool(m, "default");
                if (key != null) engine().callAttr("set_setting_java", pluginId, key, now);
                String token = str(m, "on_change_token", null);
                if (token != null) engine().callAttr("invoke_setting_callback", token, now);
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
            } else if ("selector".equals(type)) {
                showSelector(m, key);
            } else if ("input".equals(type) || "edittext".equals(type)) {
                showInput(m, key);
            } else {
                String token = str(m, "_token", null);
                if (token == null) token = str(m, "on_click_token", null);
                if (token != null) engine().callAttr("invoke_setting_callback", token, (Object) null);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    @SuppressWarnings("unchecked")
    private void showSelector(Map<String, Object> m, String key) {
        try {
            Object itemsObj = m.get("items");
            if (!(itemsObj instanceof List)) return;
            List<Object> list = (List<Object>) itemsObj;
            CharSequence[] options = new CharSequence[list.size()];
            for (int i = 0; i < list.size(); i++) options[i] = String.valueOf(list.get(i));
            String token = str(m, "on_change_token", null);
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(str(m, "text", ""));
            b.setItems(options, (dialog, which) -> {
                try {
                    if (key != null) engine().callAttr("set_setting_java", pluginId, key, which);
                    if (token != null) engine().callAttr("invoke_setting_callback", token, which);
                    if (listView != null && listView.adapter != null) listView.adapter.update(true);
                } catch (Throwable t) { FileLog.e(t); }
            });
            showDialog(b.create());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void showInput(Map<String, Object> m, String key) {
        try {
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
                try {
                    if (key != null) engine().callAttr("set_setting_java", pluginId, key, v);
                    if (token != null) engine().callAttr("invoke_setting_callback", token, v);
                    if (listView != null && listView.adapter != null) listView.adapter.update(true);
                } catch (Throwable t) { FileLog.e(t); }
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(b.create());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
