package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// ShuzaGram Mod settings and local store: ghost mode filters outgoing requests,
// deleted messages and edit history are kept in a separate database so the
// Telegram cache itself stays untouched.
public class ShuzaMod {

    public static final String PREFS = "shuzamod";

    public static boolean ghostOnline;
    public static boolean ghostTyping;
    public static boolean ghostRead;
    public static boolean ghostStories;
    public static boolean keepDeleted;
    public static boolean keepEdits;

    private static volatile boolean loaded;

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (ShuzaMod.class) {
            if (loaded) {
                return;
            }
            SharedPreferences p = prefs();
            ghostOnline = p.getBoolean("ghost_online", false);
            ghostTyping = p.getBoolean("ghost_typing", false);
            ghostRead = p.getBoolean("ghost_read", false);
            ghostStories = p.getBoolean("ghost_stories", false);
            keepDeleted = p.getBoolean("keep_deleted", true);
            keepEdits = p.getBoolean("keep_edits", true);
            loaded = true;
            updateGlassState();
        }
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void set(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        loaded = false;
        load();
    }

    private static volatile long forceGhostReadUntil;

    public static void forceGhostRead(long millis) {
        forceGhostReadUntil = System.currentTimeMillis() + millis;
    }

    // Returns true when the request must not be sent at all. Some requests are
    // rewritten in place instead (offline status, view counters).
    public static boolean filterRequest(TLObject object) {
        load();
        if (object instanceof TL_account.updateStatus) {
            if (ghostOnline) {
                ((TL_account.updateStatus) object).offline = true;
            }
            return false;
        }
        if (ghostTyping && (object instanceof TLRPC.TL_messages_setTyping || object instanceof TLRPC.TL_messages_setEncryptedTyping)) {
            return true;
        }
        if (ghostRead || System.currentTimeMillis() < forceGhostReadUntil) {
            if (object instanceof TLRPC.TL_messages_readHistory || object instanceof TLRPC.TL_channels_readHistory
                    || object instanceof TLRPC.TL_messages_readMessageContents || object instanceof TLRPC.TL_channels_readMessageContents
                    || object instanceof TLRPC.TL_messages_readDiscussion || object instanceof TLRPC.TL_messages_readEncryptedHistory
                    || object instanceof TLRPC.TL_messages_readMentions || object instanceof TLRPC.TL_messages_readReactions) {
                return true;
            }
            if (object instanceof TLRPC.TL_messages_getMessagesViews) {
                ((TLRPC.TL_messages_getMessagesViews) object).increment = false;
                return false;
            }
        }
        if (ghostStories && (object instanceof TL_stories.TL_stories_readStories || object instanceof TL_stories.TL_stories_incrementStoryViews)) {
            return true;
        }
        return false;
    }

    // Glass: a blurred photo (or the built-in ShuzaGram gradient) is the window
    // background of the whole app, and the main surface colors get transparency,
    // so every screen looks like frosted glass over it.

    public static File backgroundOriginal() {
        return new File(ApplicationLoader.getFilesDirFixed(), "shuzamod_bg_original.jpg");
    }

    public static File backgroundFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), "shuzamod_bg.jpg");
    }

    public static boolean hasCustomBackground() {
        return backgroundOriginal().exists();
    }

    // Tapping a .plugin file in a chat offers to install it instead of opening it.
    public static boolean offerInstallPlugin(android.app.Activity activity, String path, String fileName) {
        if (activity == null) {
            return false;
        }
        try {
            org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(activity);
            b.setTitle(LocaleController.getString(R.string.ShuzaModPluginInstall));
            b.setMessage(fileName + "\n\n" + LocaleController.getString(R.string.ShuzaModPluginWarning));
            b.setPositiveButton(LocaleController.getString(R.string.ShuzaModPluginInstall), (d, w) -> Utilities.globalQueue.postRunnable(() -> {
                String result;
                try {
                    java.io.File dir = new java.io.File(ApplicationLoader.getFilesDirFixed(), "plugins");
                    dir.mkdirs();
                    byte[] bytes = java.nio.file.Files.readAllBytes(new java.io.File(path).toPath());
                    String name = fileName;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("__id__\\s*=\\s*[\"']([A-Za-z0-9_]+)").matcher(new String(bytes, 0, Math.min(bytes.length, 4000), "UTF-8"));
                    if (m.find()) name = m.group(1) + ".plugin";
                    java.io.File out = new java.io.File(dir, name);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                        fo.write(bytes);
                    }
                    result = org.telegram.messenger.plugins.PluginHost.install(out.getAbsolutePath(), true);
                } catch (Exception e) {
                    FileLog.e(e);
                    result = "error: " + e;
                }
                final boolean ok = result != null && !result.startsWith("error");
                AndroidUtilities.runOnUIThread(() -> {
                    org.telegram.ui.ActionBar.BaseFragment f = org.telegram.ui.LaunchActivity.getSafeLastFragment();
                    if (f != null) {
                        org.telegram.ui.Components.BulletinFactory.of(f).createSimpleBulletin(ok ? R.raw.done : R.raw.error,
                                LocaleController.getString(ok ? R.string.ShuzaModPluginInstalled : R.string.ShuzaModPluginInstallError)).show();
                    }
                });
            }));
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            b.show();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean liquidGlass() {
        return prefs().getBoolean("liquid_glass", true);
    }

    // Saturation multiplier applied to the blurred wallpaper so glass stays vivid.
    public static float glassVibrancy() {
        return liquidGlass() ? 1.24f : 1f;
    }

    public static boolean ghostScheduledSend() {
        return prefs().getBoolean("ghost_scheduled", false);
    }

    public static boolean glassEnabled() {
        return prefs().getBoolean("app_bg_glass", false);
    }

    // 0..100 sliders
    public static int glassOpacity() {
        return prefs().getInt("glass_opacity", 55);
    }

    public static int backgroundBlur() {
        return prefs().getInt("glass_blur", 60);
    }

    public static int backgroundDim() {
        return prefs().getInt("glass_dim", 20);
    }

    private static volatile boolean glassActive;
    private static int alphaSurface, alphaGray, alphaBar, alphaPanel, alphaMenu;

    public static void updateGlassState() {
        glassActive = glassEnabled();
        int o = glassOpacity();
        alphaSurface = 255 * (25 + o * 65 / 100) / 100;
        alphaGray = alphaSurface * 55 / 100;
        alphaBar = Math.min(255, alphaSurface * 115 / 100);
        alphaPanel = Math.min(255, alphaSurface * 120 / 100);
        alphaMenu = Math.min(255, alphaSurface * 140 / 100);
    }

    // Called for every theme color lookup.
    public static int glassColor(int key, int color) {
        if (!glassActive) {
            return color;
        }
        int alpha;
        if (key == org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhite || key == org.telegram.ui.ActionBar.Theme.key_chat_emojiPanelBackground) {
            alpha = alphaSurface;
        } else if (key == org.telegram.ui.ActionBar.Theme.key_windowBackgroundGray || key == org.telegram.ui.ActionBar.Theme.key_graySection) {
            alpha = alphaGray;
        } else if (key == org.telegram.ui.ActionBar.Theme.key_actionBarDefault || key == org.telegram.ui.ActionBar.Theme.key_actionBarActionModeDefault) {
            alpha = alphaBar;
        } else if (key == org.telegram.ui.ActionBar.Theme.key_chat_messagePanelBackground) {
            alpha = alphaPanel;
        } else if (key == org.telegram.ui.ActionBar.Theme.key_chats_menuBackground) {
            alpha = alphaMenu;
        } else {
            return color;
        }
        int a = (color >>> 24) * alpha / 255;
        return (color & 0x00ffffff) | (a << 24);
    }

    private static android.graphics.Bitmap glassBitmap;
    private static long glassStamp = -1;

    public static android.graphics.Bitmap glassBitmap() {
        File file = backgroundFile();
        if (!file.exists()) {
            renderBackground();
        }
        if (glassBitmap == null || glassStamp != file.lastModified()) {
            glassBitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            glassStamp = file.lastModified();
        }
        return glassBitmap;
    }

    // Window background for the whole app.
    public static class GlassDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();

        private final Runnable tick = this::invalidateSelf;

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Bitmap bitmap = glassActive ? glassBitmap() : null;
            android.graphics.Rect b = getBounds();
            if (bitmap == null) {
                canvas.drawColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhite) | 0xff000000);
                return;
            }
            float scale = Math.max(b.width() / (float) bitmap.getWidth(), b.height() / (float) bitmap.getHeight());
            float dx = 0, dy = 0;
            boolean animate = !hasCustomBackground() && prefs().getBoolean("glass_animated", false);
            if (animate) {
                // Slow drift of the built-in gradient: a slightly enlarged image
                // moving along a lazy ellipse, redrawn at a low frame rate.
                scale *= 1.18f;
                double t = android.os.SystemClock.elapsedRealtime() / 1000.0;
                dx = (float) (Math.sin(t / 9.0) * b.width() * 0.07);
                dy = (float) (Math.cos(t / 13.0) * b.height() * 0.05);
            }
            matrix.setScale(scale, scale);
            matrix.postTranslate(b.left + (b.width() - bitmap.getWidth() * scale) / 2f + dx, b.top + (b.height() - bitmap.getHeight() * scale) / 2f + dy);
            canvas.drawBitmap(bitmap, matrix, paint);
            if (animate) {
                unscheduleSelf(tick);
                scheduleSelf(tick, android.os.SystemClock.uptimeMillis() + 66);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    // Copies the picked image (scaled down) as the new original and renders it.
    public static boolean setBackground(android.net.Uri uri) {
        try {
            android.content.ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = resolver.openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            android.graphics.Bitmap bitmap;
            try (java.io.InputStream in = resolver.openInputStream(uri)) {
                bitmap = android.graphics.BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) {
                return false;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(backgroundOriginal())) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out);
            }
            bitmap.recycle();
            return renderBackground();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    // The built-in background: soft lilac, blue and mint light blobs on deep navy.
    private static android.graphics.Bitmap defaultBackground() {
        int w = 720, h = 1600;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(0xff0d0b24);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float[][] blobs = {{0.15f, 0.12f, 0.75f, 0xff8f6bff}, {0.95f, 0.35f, 0.65f, 0xff3d7bff}, {0.2f, 0.7f, 0.7f, 0xff5ef2c8}, {0.9f, 0.95f, 0.6f, 0xffd36bff}};
        for (float[] blob : blobs) {
            int color = (int) (long) blob[3];
            float r = blob[2] * w;
            paint.setShader(new android.graphics.RadialGradient(blob[0] * w, blob[1] * h, r, new int[]{color & 0xb0ffffff, color & 0x00ffffff}, null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(blob[0] * w, blob[1] * h, r, paint);
        }
        return bitmap;
    }

    public static boolean renderBackground() {
        try {
            android.graphics.Bitmap source = hasCustomBackground() ? android.graphics.BitmapFactory.decodeFile(backgroundOriginal().getAbsolutePath()) : defaultBackground();
            if (source == null) {
                return false;
            }
            int blur = backgroundBlur();
            int dim = backgroundDim();
            android.graphics.Bitmap result;
            if (blur > 0) {
                // Blur a downscaled copy: cheaper and gives a softer glass look.
                int w = Math.max(1, source.getWidth() / 4), h = Math.max(1, source.getHeight() / 4);
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(source, w, h, true).copy(android.graphics.Bitmap.Config.ARGB_8888, true);
                Utilities.stackBlurBitmap(small, Math.max(1, blur * 25 / 100));
                result = android.graphics.Bitmap.createScaledBitmap(small, source.getWidth(), source.getHeight(), true).copy(android.graphics.Bitmap.Config.ARGB_8888, true);
                small.recycle();
            } else {
                result = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            }
            source.recycle();
            if (dim > 0) {
                new android.graphics.Canvas(result).drawColor(android.graphics.Color.argb(dim * 200 / 100, 0, 0, 0));
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(backgroundFile())) {
                result.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
            }
            result.recycle();
            glassBitmap = null;
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void resetBackground() {
        backgroundOriginal().delete();
        renderBackground();
    }

    // Fonts bundled in assets/shuzafonts (SIL Open Font License). They apply
    // to message text, the chat list and bold text after a restart.
    public static final String[][] FONTS = {{"", ""}, {"manrope", "Manrope"}, {"nunito", "Nunito"}, {"montserrat", "Montserrat"}, {"comfortaa", "Comfortaa"}};
    private static android.graphics.Typeface fontRegular, fontBold;
    private static boolean fontLoaded;

    public static String fontName() {
        return prefs().getString("font", "");
    }

    public static android.graphics.Typeface font(boolean bold) {
        if (!fontLoaded) {
            if (ApplicationLoader.applicationContext == null) {
                return null;
            }
            fontLoaded = true;
            String name = fontName();
            if (!TextUtils.isEmpty(name)) {
                fontRegular = loadFont(name, 400);
                fontBold = loadFont(name, 650);
            }
        }
        return bold ? fontBold : fontRegular;
    }

    private static android.graphics.Typeface loadFont(String name, int weight) {
        String path = "shuzafonts/" + name + ".ttf";
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                return new android.graphics.Typeface.Builder(ApplicationLoader.applicationContext.getAssets(), path)
                    .setFontVariationSettings("'wght' " + weight).build();
            }
            return android.graphics.Typeface.createFromAsset(ApplicationLoader.applicationContext.getAssets(), path);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // Settings backup: every mod preference except account tokens, plus the
    // background photo, in one JSON file.
    public static String exportBackup() throws Exception {
        org.json.JSONObject root = new org.json.JSONObject();
        org.json.JSONObject values = new org.json.JSONObject();
        for (java.util.Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
            if (e.getKey().startsWith("cloud_token_")) {
                continue;
            }
            values.put(e.getKey(), e.getValue());
        }
        root.put("shuzamod_backup", 1);
        root.put("prefs", values);
        if (hasCustomBackground()) {
            byte[] bytes = java.nio.file.Files.readAllBytes(backgroundOriginal().toPath());
            root.put("background", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
        }
        return root.toString();
    }

    public static boolean importBackup(String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            if (root.optInt("shuzamod_backup") != 1) {
                return false;
            }
            org.json.JSONObject values = root.getJSONObject("prefs");
            SharedPreferences.Editor editor = prefs().edit();
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("cloud_token_")) {
                    continue;
                }
                Object v = values.get(key);
                if (v instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) v);
                } else if (v instanceof Integer) {
                    editor.putInt(key, (Integer) v);
                } else if (v instanceof Long) {
                    editor.putLong(key, (Long) v);
                } else if (v instanceof Double) {
                    editor.putFloat(key, ((Double) v).floatValue());
                } else if (v instanceof String) {
                    editor.putString(key, (String) v);
                }
            }
            editor.commit();
            String background = root.optString("background", null);
            if (!TextUtils.isEmpty(background)) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(backgroundOriginal())) {
                    out.write(android.util.Base64.decode(background, android.util.Base64.NO_WRAP));
                }
            }
            loaded = false;
            load();
            renderBackground();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    // Shows the app as ShuzaGram everywhere in the interface: local strings and
    // language packs downloaded from Telegram. Links and domains stay as they are.
    // Android's \\w already matches Cyrillic, so no extra flags (Android rejects
    // UNICODE_CHARACTER_CLASS). Any failure leaves the text unchanged.
    private static java.util.regex.Pattern brand;
    private static boolean brandFailed;

    public static String rebrand(String value) {
        if (value == null || brandFailed || (value.indexOf("Telegram") < 0 && value.indexOf("Телеграм") < 0)) {
            return value;
        }
        try {
            if (brand == null) {
                brand = java.util.regex.Pattern.compile("(?<![\\w/@.\\-])(Telegram|Телеграмм?)(?![\\w\\-]|\\.(?:org|me|dog|com)\\b)");
            }
            return brand.matcher(value).replaceAll("ShuzaGram");
        } catch (Throwable e) {
            brandFailed = true;
            return value;
        }
    }

    // Deleted messages and edit history. Private chats and basic groups share
    // one message id space per account, so they use dialog key 0; channels and
    // supergroups use -channel_id.

    public static long dialogKey(TLRPC.Message message) {
        if (message != null && message.peer_id != null && message.peer_id.channel_id != 0) {
            return -message.peer_id.channel_id;
        }
        return 0;
    }

    private static final ConcurrentHashMap<Integer, Set<String>> deletedCache = new ConcurrentHashMap<>();

    private static Set<String> deletedSet(int account) {
        Set<String> set = deletedCache.get(account);
        if (set != null) {
            return set;
        }
        synchronized (deletedCache) {
            set = deletedCache.get(account);
            if (set != null) {
                return set;
            }
            set = Collections.newSetFromMap(new ConcurrentHashMap<>());
            try (Cursor c = db().getReadableDatabase().rawQuery("SELECT dialog, mid FROM deleted WHERE account = ?", new String[]{String.valueOf(account)})) {
                while (c.moveToNext()) {
                    set.add(c.getLong(0) + ":" + c.getInt(1));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            deletedCache.put(account, set);
            return set;
        }
    }

    public static boolean isDeleted(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0) {
            return false;
        }
        return deletedSet(account).contains(dialogKey(messageObject.messageOwner) + ":" + messageObject.getId());
    }

    public static void markDeleted(int account, long dialogKey, ArrayList<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<String> set = deletedSet(account);
        int now = (int) (System.currentTimeMillis() / 1000);
        try {
            SQLiteDatabase w = db().getWritableDatabase();
            w.beginTransaction();
            try {
                for (Integer id : ids) {
                    ContentValues v = new ContentValues();
                    v.put("account", account);
                    v.put("dialog", dialogKey);
                    v.put("mid", id);
                    v.put("date", now);
                    w.insertWithOnConflict("deleted", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                    set.add(dialogKey + ":" + id);
                }
                w.setTransactionSuccessful();
            } finally {
                w.endTransaction();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.shuzaModMessagesMarked));
    }

    public static void saveEdit(int account, TLRPC.Message oldMessage) {
        if (oldMessage == null || oldMessage.message == null) {
            return;
        }
        try {
            ContentValues v = new ContentValues();
            v.put("account", account);
            v.put("dialog", dialogKey(oldMessage));
            v.put("mid", oldMessage.id);
            v.put("date", oldMessage.edit_date != 0 ? oldMessage.edit_date : oldMessage.date);
            v.put("text", oldMessage.message);
            db().getWritableDatabase().insert("edits", null, v);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static class Edit {
        public int date;
        public String text;
    }

    public static ArrayList<Edit> getEdits(int account, MessageObject messageObject) {
        ArrayList<Edit> result = new ArrayList<>();
        if (messageObject == null || messageObject.messageOwner == null) {
            return result;
        }
        try (Cursor c = db().getReadableDatabase().rawQuery("SELECT date, text FROM edits WHERE account = ? AND dialog = ? AND mid = ? ORDER BY rowid",
                new String[]{String.valueOf(account), String.valueOf(dialogKey(messageObject.messageOwner)), String.valueOf(messageObject.getId())})) {
            while (c.moveToNext()) {
                Edit e = new Edit();
                e.date = c.getInt(0);
                e.text = c.getString(1);
                result.add(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static boolean hasEdits(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || (messageObject.messageOwner.flags & TLRPC.MESSAGE_FLAG_EDITED) == 0) {
            return false;
        }
        try (Cursor c = db().getReadableDatabase().rawQuery("SELECT 1 FROM edits WHERE account = ? AND dialog = ? AND mid = ? LIMIT 1",
                new String[]{String.valueOf(account), String.valueOf(dialogKey(messageObject.messageOwner)), String.valueOf(messageObject.getId())})) {
            return c.moveToNext();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void clearStore() {
        try {
            SQLiteDatabase w = db().getWritableDatabase();
            w.delete("deleted", null, null);
            w.delete("edits", null, null);
        } catch (Exception e) {
            FileLog.e(e);
        }
        deletedCache.clear();
    }

    private static Store store;

    private static synchronized Store db() {
        if (store == null) {
            store = new Store(ApplicationLoader.applicationContext);
        }
        return store;
    }

    private static class Store extends SQLiteOpenHelper {
        Store(Context context) {
            super(context, "shuzamod.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE deleted (account INTEGER, dialog INTEGER, mid INTEGER, date INTEGER, PRIMARY KEY (account, dialog, mid))");
            db.execSQL("CREATE TABLE edits (account INTEGER, dialog INTEGER, mid INTEGER, date INTEGER, text TEXT)");
            db.execSQL("CREATE INDEX edits_msg ON edits (account, dialog, mid)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
