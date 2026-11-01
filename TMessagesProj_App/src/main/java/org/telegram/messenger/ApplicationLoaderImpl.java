package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // ShuzaGram Mod: start the Python plugin engine off the main thread.
        new Thread(() -> {
            try {
                if (!com.chaquo.python.Python.isStarted()) {
                    com.chaquo.python.Python.start(new com.chaquo.python.android.AndroidPlatform(this));
                }
                com.chaquo.python.Python.getInstance().getModule("shuza_plugins.host").callAttr("init");
            } catch (Throwable t) {
                org.telegram.messenger.plugins.PluginHost.startError = String.valueOf(t);
                FileLog.e(t);
                android.util.Log.e("ShuzaPlugins", "engine start failed", t);
            }
        }, "ShuzaPlugins").start();
    }
}
