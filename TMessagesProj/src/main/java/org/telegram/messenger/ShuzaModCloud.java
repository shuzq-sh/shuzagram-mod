package org.telegram.messenger;

import android.app.Activity;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

// ShuzaGram Mod cloud at https://shuzagram.com/mod/api: couples, visible to
// every mod user. A couple appears only after the invited person accepts in
// their own app; invitations can be ignored per sender.
public class ShuzaModCloud {

    public static final String API = "https://shuzagram.com/mod/api";
    private static final long PROFILE_TTL = 10 * 60 * 1000L;
    private static final long ME_INTERVAL = 5 * 60 * 1000L;

    public static class Couple {
        public long userId;
        public long partnerId;
        public int since;
        public boolean hidden;
        public long hiddenBy;
        public long fetched;
        public int banner;
        public boolean mod;

        public int days() {
            return Math.max(0, (int) ((System.currentTimeMillis() / 1000 - since) / 86400));
        }
    }

    public static String levelEmoji(int days) {
        if (days >= 1095) return "💎";
        if (days >= 365) return "💍";
        if (days >= 182) return "🌸";
        if (days >= 30) return "🌱";
        return "🤍";
    }

    // --- auth -------------------------------------------------------------------

    private static String tokenKey(int account) {
        return "cloud_token_" + UserConfig.getInstance(account).getClientUserId();
    }

    public static String token(int account) {
        return ShuzaMod.prefs().getString(tokenKey(account), null);
    }

    // Runs onReady (on a background thread) once the account has a token.
    public static void ensureAuth(int account, Runnable onReady) {
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        ensureObserver(account);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (token(account) == null) {
                    JSONObject res = call(account, "POST", "/auth/register", new JSONObject().put("user_id", UserConfig.getInstance(account).getClientUserId()), false);
                    String token = res.optString("token", null);
                    if (TextUtils.isEmpty(token)) {
                        return;
                    }
                    ShuzaMod.prefs().edit().putString(tokenKey(account), token).apply();
                }
                onReady.run();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    // An incoming invitation message triggers an immediate check, so the
    // invitation window shows up while the app is open.
    private static final HashSet<Integer> observed = new HashSet<>();

    private static void ensureObserver(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!observed.add(account)) {
                return;
            }
            String marker = "ShuzaGram Mod";
            NotificationCenter.getInstance(account).addObserver((id, acc, args) -> {
                if (args.length < 2 || !(args[1] instanceof ArrayList)) {
                    return;
                }
                for (Object o : (ArrayList<?>) args[1]) {
                    if (o instanceof MessageObject) {
                        MessageObject m = (MessageObject) o;
                        if (!m.isOut() && m.messageOwner != null && m.messageOwner.message != null && m.messageOwner.message.startsWith("💞") && m.messageOwner.message.contains(marker)) {
                            AndroidUtilities.runOnUIThread(() -> checkMe(org.telegram.ui.LaunchActivity.instance, account, true), 1500);
                            return;
                        }
                    }
                }
            }, NotificationCenter.didReceiveNewMessages);
        });
    }

    // --- couples ------------------------------------------------------------------

    private static final ConcurrentHashMap<Long, Couple> profiles = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> profilesFetched = new ConcurrentHashMap<>();
    private static final HashSet<Long> pendingIds = new HashSet<>();

    // Cached couple of any user; starts a background refresh when stale and
    // posts shuzaModCoupleUpdated when the answer arrives.
    public static Couple coupleFor(int account, long userId) {
        if (userId <= 0) {
            return null;
        }
        Long fetched = profilesFetched.get(userId);
        if (fetched == null || System.currentTimeMillis() - fetched > PROFILE_TTL) {
            requestProfile(account, userId);
        }
        return profiles.get(userId);
    }

    private static void requestProfile(int account, long userId) {
        // Batch: the first id schedules one request for everything queued.
        synchronized (pendingIds) {
            if (!pendingIds.add(userId) || pendingIds.size() > 1) {
                return;
            }
        }
        AndroidUtilities.runOnUIThread(() -> ensureAuth(account, () -> {
            ArrayList<Long> ids;
            synchronized (pendingIds) {
                ids = new ArrayList<>(pendingIds);
                pendingIds.clear();
            }
            if (ids.isEmpty()) {
                return;
            }
            try {
                JSONObject res = call(account, "GET", "/profiles?ids=" + TextUtils.join(",", ids), null, true);
                JSONObject map = res.optJSONObject("profiles");
                long now = System.currentTimeMillis();
                for (Long id : ids) {
                    profilesFetched.put(id, now);
                    JSONObject p = map != null ? map.optJSONObject(String.valueOf(id)) : null;
                    if (p == null) {
                        profiles.remove(id);
                    } else {
                        Couple c = new Couple();
                        c.userId = id;
                        c.partnerId = p.optLong("partner");
                        c.since = p.optInt("since");
                        c.hidden = p.optBoolean("hidden");
                        c.banner = p.optInt("banner");
                        c.mod = p.optBoolean("mod");
                        c.fetched = now;
                        profiles.put(id, c);
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.shuzaModCoupleUpdated);
                    // Names in the chat list pick up the ShuzaGram badge.
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        }), 300);
    }

    private static final ConcurrentHashMap<Integer, Couple> myCouple = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, ArrayList<Long>> invites = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, ArrayList<Long>> ignored = new ConcurrentHashMap<>();
    private static final HashSet<Long> shownInvites = new HashSet<>();

    public static ArrayList<Long> invites(int account) {
        ArrayList<Long> list = invites.get(account);
        return list != null ? list : new ArrayList<>();
    }

    public static ArrayList<Long> ignored(int account) {
        ArrayList<Long> list = ignored.get(account);
        return list != null ? list : new ArrayList<>();
    }
    private static final ConcurrentHashMap<Integer, Long> meChecked = new ConcurrentHashMap<>();

    public static Couple myCouple(int account) {
        return myCouple.get(account);
    }

    // Periodic check: own couple state and incoming invites.
    public static void checkMe(Activity activity, int account, boolean force) {
        Long last = meChecked.get(account);
        if (!force && last != null && System.currentTimeMillis() - last < ME_INTERVAL) {
            return;
        }
        meChecked.put(account, System.currentTimeMillis());
        ensureAuth(account, () -> {
            try {
                JSONObject me = call(account, "GET", "/me", null, true);
                long self = UserConfig.getInstance(account).getClientUserId();
                JSONObject c = me.optJSONObject("couple");
                Couple oldSelf = profiles.get(self);
                if (c != null) {
                    Couple couple = new Couple();
                    couple.banner = oldSelf != null ? oldSelf.banner : 0;
                    couple.userId = self;
                    couple.partnerId = c.optLong("a") == self ? c.optLong("b") : c.optLong("a");
                    couple.since = c.optInt("since");
                    couple.hidden = c.optBoolean("hidden");
                    couple.hiddenBy = c.optLong("hidden_by");
                    myCouple.put(account, couple);
                    profiles.put(self, couple);
                } else {
                    myCouple.remove(account);
                    if (oldSelf != null && oldSelf.banner != 0) {
                        oldSelf.partnerId = 0;
                    } else {
                        profiles.remove(self);
                    }
                }
                ArrayList<Long> inv = new ArrayList<>();
                JSONArray invArr = me.optJSONArray("invites");
                for (int i = 0; invArr != null && i < invArr.length(); i++) {
                    inv.add(invArr.optJSONObject(i).optLong("from_id"));
                }
                ArrayList<Long> ign = new ArrayList<>();
                JSONArray ignArr = me.optJSONArray("ignored");
                for (int i = 0; ignArr != null && i < ignArr.length(); i++) {
                    ign.add(ignArr.optLong(i));
                }
                invites.put(account, inv);
                ignored.put(account, ign);
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.shuzaModCoupleUpdated);
                    if (activity == null || activity.isFinishing() || myCouple(account) != null) {
                        return;
                    }
                    for (Long fromId : inv) {
                        // Each invitation pops up once; unanswered ones stay in the list in settings.
                        if (shownInvites.add(fromId)) {
                            showInvite(activity, account, fromId);
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static void showInvite(Activity activity, int account, long fromId) {
        TLRPC.User from = MessagesController.getInstance(account).getUser(fromId);
        String name = from != null ? UserObject.getUserName(from) : String.valueOf(fromId);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ShuzaModCoupleInviteTitle));
        builder.setMessage(LocaleController.formatString(R.string.ShuzaModCoupleInviteText, name));
        builder.setPositiveButton(LocaleController.getString(R.string.ShuzaModCoupleAccept), (d, w) -> accept(account, fromId, () -> checkMe(activity, account, true)));
        builder.setNegativeButton(LocaleController.getString(R.string.ShuzaModCoupleDecline), (d, w) -> decline(account, fromId, null));
        builder.setNeutralButton(LocaleController.getString(R.string.ShuzaModCoupleIgnore), (d, w) -> ignore(account, fromId, null));
        builder.show();
    }

    public static void accept(int account, long fromId, Runnable onDone) {
        post(account, "/couple/accept", fromId, null, onDone);
    }

    public static void decline(int account, long fromId, Runnable onDone) {
        post(account, "/couple/decline", fromId, null, onDone);
    }

    public static void ignore(int account, long fromId, Runnable onDone) {
        post(account, "/couple/ignore", fromId, null, onDone);
    }

    public static void unignore(int account, long fromId, Runnable onDone) {
        post(account, "/couple/unignore", fromId, null, onDone);
    }

    public static void invite(int account, long partnerId, Runnable onDone) {
        post(account, "/couple/invite", partnerId, "partner_id", onDone);
    }

    // Hiding the status (or breaking up) tells the partner with a message
    // from this account.
    public static void setVisible(int account, boolean visible, Runnable onDone) {
        Couple couple = myCouple(account);
        ensureAuth(account, () -> {
            try {
                call(account, "POST", "/couple/visibility", new JSONObject().put("visible", visible), true);
                if (!visible && couple != null) {
                    sendToPartner(account, couple.partnerId, LocaleController.getString(R.string.ShuzaModCoupleHiddenMessage));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            done(account, onDone);
        });
    }

    public static void breakUp(int account, Runnable onDone) {
        Couple couple = myCouple(account);
        ensureAuth(account, () -> {
            try {
                call(account, "POST", "/couple/break", new JSONObject(), true);
                if (couple != null) {
                    sendToPartner(account, couple.partnerId, LocaleController.getString(R.string.ShuzaModCoupleBrokeMessage));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            done(account, onDone);
        });
    }

    private static void post(int account, String path, long id, String field, Runnable onDone) {
        ensureAuth(account, () -> {
            try {
                call(account, "POST", path, new JSONObject().put(field != null ? field : "from_id", id), true);
            } catch (Exception e) {
                FileLog.e(e);
            }
            done(account, onDone);
        });
    }

    private static void done(int account, Runnable onDone) {
        profilesFetched.clear();
        meChecked.remove(account);
        checkMe(null, account, true);
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.shuzaModCoupleUpdated);
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    private static void sendToPartner(int account, long partnerId, String text) {
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, partnerId)));
    }

    // --- ShuzaGram badge -----------------------------------------------------------
    // A small mark after the names of people who use the mod.

    public static boolean isModUser(int account, long userId) {
        if (userId <= 0 || !ShuzaMod.prefs().getBoolean("show_badges", true)) {
            return false;
        }
        Couple profile = coupleFor(account, userId);
        return profile != null && profile.mod;
    }

    public static CharSequence withBadge(int account, long userId, CharSequence name, float textSize) {
        if (name == null || !isModUser(account, userId)) {
            return name;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
        sb.append("  ");
        BadgeDrawable badge = new BadgeDrawable();
        int size = (int) (textSize * 0.85f);
        badge.setBounds(0, 0, size, size);
        int align = android.os.Build.VERSION.SDK_INT >= 29 ? android.text.style.DynamicDrawableSpan.ALIGN_CENTER : android.text.style.DynamicDrawableSpan.ALIGN_BASELINE;
        sb.setSpan(new android.text.style.ImageSpan(badge, align), sb.length() - 1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // Gradient circle with a white S, matching the app icon colors.
    public static class BadgeDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        public BadgeDrawable() {
            text.setColor(0xffffffff);
            text.setTextAlign(android.graphics.Paint.Align.CENTER);
            text.setTypeface(AndroidUtilities.bold());
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            fill.setShader(new android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom, 0xffa58bff, 0xff3fd9b0, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, fill);
            text.setTextSize(r * 1.3f);
            float y = b.exactCenterY() - (text.descent() + text.ascent()) / 2f;
            canvas.drawText("S", b.exactCenterX(), y, text);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            text.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // --- banners ------------------------------------------------------------------
    // A wide picture in the profile header, seen by every mod user.

    public static final String BANNERS = "https://shuzagram.com/mod/banners/";
    private static final android.util.LruCache<String, android.graphics.Bitmap> bannerCache = new android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, android.graphics.Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final HashSet<String> bannerLoading = new HashSet<>();

    public static android.graphics.Bitmap banner(int account, long userId) {
        Couple profile = coupleFor(account, userId);
        if (profile == null || profile.banner == 0) {
            return null;
        }
        String key = userId + "_" + profile.banner;
        android.graphics.Bitmap bitmap = bannerCache.get(key);
        if (bitmap != null) {
            return bitmap;
        }
        synchronized (bannerLoading) {
            if (!bannerLoading.add(key)) {
                return null;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                java.io.File dir = new java.io.File(ApplicationLoader.applicationContext.getCacheDir(), "shuzamod_banners");
                dir.mkdirs();
                java.io.File file = new java.io.File(dir, key + ".jpg");
                if (!file.exists()) {
                    HttpURLConnection connection = (HttpURLConnection) new URL(BANNERS + userId + ".jpg?v=" + profile.banner).openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(20000);
                    try (InputStream in = connection.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                        }
                    }
                }
                android.graphics.Bitmap loaded = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                if (loaded != null) {
                    bannerCache.put(key, loaded);
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.shuzaModCoupleUpdated));
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                synchronized (bannerLoading) {
                    bannerLoading.remove(key);
                }
            }
        });
        return null;
    }

    private static final android.graphics.Paint bannerPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    private static final android.graphics.Paint shadePaint = new android.graphics.Paint();
    private static final android.graphics.Matrix bannerMatrix = new android.graphics.Matrix();

    // Center-crops the banner into the header and darkens it so names stay readable.
    public static void drawBanner(android.graphics.Canvas canvas, android.graphics.Bitmap bitmap, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        bannerMatrix.setScale(scale, scale);
        bannerMatrix.postTranslate((width - bitmap.getWidth() * scale) / 2f, (height - bitmap.getHeight() * scale) / 2f);
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        canvas.drawBitmap(bitmap, bannerMatrix, bannerPaint);
        shadePaint.setShader(new android.graphics.LinearGradient(0, 0, 0, height, new int[]{0x55000000, 0x10000000, 0x99000000}, new float[]{0f, 0.4f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, shadePaint);
        canvas.restore();
    }

    // Crops the picked image to 16:9, uploads it and refreshes the own profile.
    public static void uploadBanner(int account, android.net.Uri uri, Utilities.Callback<Boolean> onDone) {
        ensureAuth(account, () -> {
            boolean ok = false;
            try {
                android.content.ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in = resolver.openInputStream(uri)) {
                    android.graphics.BitmapFactory.decodeStream(in, null, bounds);
                }
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) {
                    sample *= 2;
                }
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inSampleSize = sample;
                android.graphics.Bitmap src;
                try (InputStream in = resolver.openInputStream(uri)) {
                    src = android.graphics.BitmapFactory.decodeStream(in, null, options);
                }
                if (src != null) {
                    int w = src.getWidth(), h = src.getHeight();
                    int cw = w, ch = w * 9 / 16;
                    if (ch > h) {
                        ch = h;
                        cw = h * 16 / 9;
                    }
                    android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch);
                    android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(cropped, 1280, 720, true);
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, jpeg);
                    HttpURLConnection connection = (HttpURLConnection) new URL(API + "/banner/upload").openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(30000);
                    connection.setRequestProperty("Authorization", "Bearer " + token(account));
                    connection.setRequestProperty("Content-Type", "image/jpeg");
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(jpeg.toByteArray());
                    }
                    ok = connection.getResponseCode() == 200;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final boolean result = ok;
            profilesFetched.clear();
            AndroidUtilities.runOnUIThread(() -> onDone.run(result));
        });
    }

    public static void deleteBanner(int account, Runnable onDone) {
        ensureAuth(account, () -> {
            try {
                call(account, "POST", "/banner/delete", new JSONObject(), true);
            } catch (Exception e) {
                FileLog.e(e);
            }
            done(account, onDone);
        });
    }

    public static boolean hasOwnBanner(int account) {
        Couple self = coupleFor(account, UserConfig.getInstance(account).getClientUserId());
        return self != null && self.banner != 0;
    }

    // --- HTTP -----------------------------------------------------------------------

    private static JSONObject call(int account, String method, String path, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        if (auth) {
            String token = token(account);
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes("UTF-8"));
            }
        }
        int code = connection.getResponseCode();
        if (code == 401) {
            ShuzaMod.prefs().edit().remove(tokenKey(account)).apply();
        }
        InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            return new JSONObject();
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        }
    }
}
