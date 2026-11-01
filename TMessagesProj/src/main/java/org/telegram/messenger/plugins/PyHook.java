package org.telegram.messenger.plugins;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;

// Xposed hook objects whose callbacks live in Python. Python cannot subclass
// Java classes at runtime, so it implements these small interfaces instead.
public class PyHook extends XC_MethodHook {

    public interface Handler {
        void before(Object param);

        void after(Object param);
    }

    public interface Replacer {
        Object replace(Object param);
    }

    private static volatile Boolean hookingReady;

    // Pine needs one-time setup before the first hook.
    public static synchronized boolean ensureHooking() {
        if (hookingReady != null) {
            return hookingReady;
        }
        try {
            top.canyie.pine.PineConfig.debug = false;
            top.canyie.pine.PineConfig.debuggable = false;
            top.canyie.pine.Pine.ensureInitialized();
            hookingReady = true;
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
            hookingReady = false;
        }
        return hookingReady;
    }

    private final Handler handler;

    public PyHook(Handler handler, int priority) {
        super(priority);
        this.handler = handler;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        handler.before(param);
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        handler.after(param);
    }

    public static class Replacement extends XC_MethodReplacement {
        private final Replacer replacer;

        public Replacement(Replacer replacer, int priority) {
            super(priority);
            this.replacer = replacer;
        }

        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            return replacer.replace(param);
        }
    }
}
