package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Self-updater: reads https://shuzagram.com/mod/version.json
// {"version_code": 71840, "version_name": "1.0.1", "url": "https://shuzagram.com/mod/ShuzaGram-mod.apk", "changelog": "..."}
// and offers to download and install a newer APK.
public class ShuzaModUpdater {

    public static final String VERSION_URL = "https://shuzagram.com/mod/version.json";
    private static final long AUTO_CHECK_INTERVAL = 3 * 60 * 60 * 1000L;

    private static boolean checking;
    private static long lastAutoCheck;

    public static void autoCheck(Activity activity) {
        long now = System.currentTimeMillis();
        if (now - lastAutoCheck < AUTO_CHECK_INTERVAL) {
            return;
        }
        lastAutoCheck = now;
        check(activity, false);
    }

    public static void check(Activity activity, boolean manual) {
        if (checking || activity == null) {
            return;
        }
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject info = null;
            try {
                info = new JSONObject(new String(fetch(VERSION_URL), "UTF-8"));
            } catch (Exception e) {
                FileLog.e(e);
            }
            final JSONObject result = info;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (activity.isFinishing()) {
                    return;
                }
                int current = currentVersionCode();
                if (result == null) {
                    if (manual) {
                        alert(activity, LocaleController.getString(R.string.ShuzaModUpdateError));
                    }
                    return;
                }
                int remote = result.optInt("version_code", 0);
                String url = result.optString("url", "");
                if (remote <= current || TextUtils.isEmpty(url)) {
                    if (manual) {
                        alert(activity, LocaleController.getString(R.string.ShuzaModUpdateNone));
                    }
                    return;
                }
                if (!manual && ShuzaMod.prefs().getInt("skipped_update", 0) == remote) {
                    return;
                }
                offer(activity, remote, result.optString("version_name", String.valueOf(remote)), result.optString("changelog", ""), url);
            });
        });
    }

    private static void offer(Activity activity, int remote, String versionName, String changelog, String url) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.formatString(R.string.ShuzaModUpdateTitle, versionName));
        builder.setMessage(TextUtils.isEmpty(changelog) ? LocaleController.getString(R.string.ShuzaModUpdateText) : changelog);
        builder.setPositiveButton(LocaleController.getString(R.string.ShuzaModUpdateInstall), (d, w) -> download(activity, url));
        builder.setNegativeButton(LocaleController.getString(R.string.ShuzaModUpdateLater), (d, w) -> ShuzaMod.prefs().edit().putInt("skipped_update", remote).apply());
        builder.show();
    }

    private static void download(Activity activity, String url) {
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
            dir.mkdirs();
            File apk = new File(dir, "shuzamod-update.apk");
            boolean ok = false;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                ok = connection.getResponseCode() == 200 && apk.length() > 0;
            } catch (Exception e) {
                FileLog.e(e);
            }
            final boolean downloaded = ok;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (!downloaded) {
                    alert(activity, LocaleController.getString(R.string.ShuzaModUpdateError));
                    return;
                }
                install(activity, apk);
            });
        });
    }

    private static void install(Activity activity, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                alert(activity, LocaleController.getString(R.string.ShuzaModUpdateAllow));
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", apk);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
            alert(activity, LocaleController.getString(R.string.ShuzaModUpdateError));
        }
    }

    private static void alert(Activity activity, String text) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ShuzaModTitle));
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.show();
    }

    public static int currentVersionCode() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setUseCaches(false);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
