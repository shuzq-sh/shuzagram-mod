package org.telegram.messenger.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.telegram.messenger.FileLog;

// Bridge between Telegram code and the plugin engine (Python, see
// TMessagesProj_App/src/main/python/shuza_plugins). Telegram calls the static
// dispatch methods; they are no-ops until the engine registers itself.
public final class PluginHost {

    // Returned by a hook to drop the message, request or update.
    public static final Object CANCEL = new Object();

    public interface Callback {
        Object call(Object arg);
    }

    public interface Engine {
        void onAppEvent(String event);

        Object onSendMessage(int account, Object params);

        Object preRequest(int account, String name, Object request);

        Object postRequest(int account, String name, Object response, Object error);

        Object onUpdate(int account, String name, Object update);

        Object onUpdates(int account, String name, Object updates);

        List<Object> menuItems(String type, Map<String, Object> context);

        void menuClick(String itemId, Map<String, Object> context);

        List<Object> listPlugins();

        String install(String path, boolean enable);

        String setEnabled(String id, boolean enabled);

        String uninstall(String id);

        List<Object> createSettings(String id);

        String pluginLog(String id);

        String engineInfo();

        Object invokeSetting(String token, Object value);
    }

    public static volatile Engine engine;
    public static volatile String startError;

    // Fast-path flags kept up to date by the engine so Telegram does not call
    // into Python for every request when no plugin asked for it.
    public static volatile boolean hasSendHooks, hasRequestHooks, hasResponseHooks, hasUpdateHooks;

    public static void register(Engine e) {
        engine = e;
    }

    public static Object onSendMessage(int account, Object params) {
        Engine e = engine;
        if (e == null || !hasSendHooks) {
            return null;
        }
        try {
            return e.onSendMessage(account, params);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public static Object preRequest(int account, Object request) {
        Engine e = engine;
        if (e == null || !hasRequestHooks || request == null) {
            return null;
        }
        try {
            return e.preRequest(account, requestName(request), request);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public static Object postRequest(int account, Object request, Object response, Object error) {
        Engine e = engine;
        if (e == null || !hasResponseHooks || request == null) {
            return null;
        }
        try {
            return e.postRequest(account, requestName(request), response, error);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public static Object onUpdate(int account, Object update) {
        Engine e = engine;
        if (e == null || !hasUpdateHooks || update == null) {
            return null;
        }
        try {
            return e.onUpdate(account, update.getClass().getSimpleName(), update);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public static Object onUpdates(int account, Object updates) {
        Engine e = engine;
        if (e == null || !hasUpdateHooks || updates == null) {
            return null;
        }
        try {
            return e.onUpdates(account, updates.getClass().getSimpleName(), updates);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public static List<Object> menuItems(String type, Map<String, Object> context) {
        Engine e = engine;
        if (e == null) {
            return new ArrayList<>();
        }
        try {
            List<Object> items = e.menuItems(type, context);
            return items != null ? items : new ArrayList<>();
        } catch (Throwable t) {
            FileLog.e(t);
            return new ArrayList<>();
        }
    }

    public static void menuClick(String itemId, Map<String, Object> context) {
        Engine e = engine;
        if (e != null) {
            try {
                e.menuClick(itemId, context);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    // Library-side UI helpers (null-safe passthrough to the engine).
    public static java.util.List<Object> listPlugins() {
        Engine e = engine;
        if (e == null) return new ArrayList<>();
        try { java.util.List<Object> l = e.listPlugins(); return l != null ? l : new ArrayList<>(); }
        catch (Throwable t) { FileLog.e(t); return new ArrayList<>(); }
    }

    public static java.util.List<Object> createSettings(String id) {
        Engine e = engine;
        if (e == null) return new ArrayList<>();
        try { java.util.List<Object> l = e.createSettings(id); return l != null ? l : new ArrayList<>(); }
        catch (Throwable t) { FileLog.e(t); return new ArrayList<>(); }
    }

    public static String install(String path, boolean enable) {
        Engine e = engine;
        if (e == null) return "error: engine not ready";
        try { return e.install(path, enable); } catch (Throwable t) { FileLog.e(t); return "error: " + t; }
    }

    public static String setEnabled(String id, boolean enabled) {
        Engine e = engine;
        if (e == null) return "error: engine not ready";
        try { return e.setEnabled(id, enabled); } catch (Throwable t) { FileLog.e(t); return "error: " + t; }
    }

    public static String uninstall(String id) {
        Engine e = engine;
        if (e == null) return "error: engine not ready";
        try { return e.uninstall(id); } catch (Throwable t) { FileLog.e(t); return "error: " + t; }
    }

    public static String pluginLog(String id) {
        Engine e = engine;
        if (e == null) return "";
        try { return e.pluginLog(id); } catch (Throwable t) { return ""; }
    }

    public static void invokeSetting(String token, Object value) {
        Engine e = engine;
        if (e == null) return;
        try { e.invokeSetting(token, value); } catch (Throwable t) { FileLog.e(t); }
    }

    public static void appEvent(String event) {
        Engine e = engine;
        if (e != null) {
            try {
                e.onAppEvent(event);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    // exteraGram names requests like "TL_messages_sendMessage"; newer TL
    // classes in this tree are nested ("messages_sendMessage" inside TL_messages),
    // so fall back to the simple class name.
    public static String requestName(Object request) {
        return request.getClass().getSimpleName();
    }
}
