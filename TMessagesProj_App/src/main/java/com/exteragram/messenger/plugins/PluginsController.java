package com.exteragram.messenger.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// exteraGram-compatible facade over the ShuzaGram Mod Python plugin engine.
// Plugins reflect on this class by name, so the public surface must stay stable.
public class PluginsController {

    private static volatile PluginsController instance;

    public SharedPreferences preferences;
    public final HashMap<String, PluginWrapper> plugins = new HashMap<>();

    private PluginsController() {
        Context ctx = ApplicationLoader.applicationContext;
        preferences = ctx.getSharedPreferences("shuzamod_plugins", Context.MODE_PRIVATE);
    }

    public static PluginsController getInstance() {
        if (instance == null) {
            synchronized (PluginsController.class) {
                if (instance == null) {
                    instance = new PluginsController();
                }
            }
        }
        return instance;
    }

    private static PyObject engine() {
        return Python.getInstance().getModule("shuza_plugins.engine");
    }

    @SuppressWarnings("unchecked")
    public synchronized HashMap<String, PluginWrapper> refresh() {
        try {
            List<Object> list = engine().callAttr("list_plugins").toJava(List.class);
            plugins.clear();
            for (Object o : list) {
                Map<String, Object> row = (Map<String, Object>) o;
                PluginWrapper w = new PluginWrapper();
                w.id = asStr(row.get("id"));
                w.name = asStr(row.get("name"));
                w.version = asStr(row.get("version"));
                w.author = asStr(row.get("author"));
                w.description = asStr(row.get("description"));
                Object en = row.get("enabled");
                w.enabled = en instanceof Boolean && (Boolean) en;
                w.error = asStr(row.get("error"));
                if (w.id != null) {
                    plugins.put(w.id, w);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return plugins;
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    public PluginsController getPluginEngine(String id) {
        return this;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAllPluginSettings(String pluginId) {
        try {
            Map<String, Object> m = engine().callAttr("all_settings_java", pluginId).toJava(Map.class);
            return m != null ? m : new HashMap<>();
        } catch (Throwable t) {
            FileLog.e(t);
            return new HashMap<>();
        }
    }

    public void setPluginSetting(String pluginId, String key, Object value) {
        try {
            engine().callAttr("set_setting_java", pluginId, key, value);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public String getPluginPath(String pluginId) {
        try {
            PyObject p = engine().callAttr("plugin_path", pluginId);
            return p == null ? null : p.toString();
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public void loadPlugins() {
        try {
            engine().callAttr("scan_plugins");
            refresh();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public void loadPluginSettings(String pluginId) {
    }

    public String installPlugin(String path, boolean enable) {
        try {
            return engine().callAttr("install", path, enable).toString();
        } catch (Throwable t) {
            FileLog.e(t);
            return "error: " + t;
        }
    }

    public String setPluginEnabled(String id, boolean enabled) {
        try {
            String r = engine().callAttr("set_enabled", id, enabled).toString();
            refresh();
            return r;
        } catch (Throwable t) {
            FileLog.e(t);
            return "error: " + t;
        }
    }

    public String uninstallPlugin(String id) {
        try {
            String r = engine().callAttr("uninstall", id).toString();
            refresh();
            return r;
        } catch (Throwable t) {
            FileLog.e(t);
            return "error: " + t;
        }
    }

    public String pluginLog(String id) {
        try {
            return engine().callAttr("plugin_log", id).toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static class PluginWrapper {
        public String id, name, version, author, description, error = "";
        public boolean enabled;

        public String getId() { return id; }
        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getAuthor() { return author; }
        public String getDescription() { return description; }
        public boolean isEnabled() { return enabled; }

        public void setEnabled(boolean v) {
            PluginsController.getInstance().setPluginEnabled(id, v);
        }

        public void setIcon(String icon) {
        }
    }
}
