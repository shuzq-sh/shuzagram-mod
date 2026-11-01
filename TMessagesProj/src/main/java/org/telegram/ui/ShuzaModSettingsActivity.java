package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ShuzaMod;
import org.telegram.messenger.ShuzaModCloud;
import org.telegram.messenger.ShuzaModUpdater;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

import static org.telegram.messenger.LocaleController.getString;

// ShuzaGram Mod settings: a hub with one page per feature.
public class ShuzaModSettingsActivity extends UniversalFragment {

    private static final int PAGE_HUB = 0, PAGE_GLASS = 1, PAGE_GHOST = 2, PAGE_MESSAGES = 3, PAGE_COUPLE = 4, PAGE_THEMES = 5, PAGE_BANNER = 6, PAGE_LOOK = 7, PAGE_OTHER = 8;
    private static final int OPEN_LOOK = 8, OPEN_OTHER = 9;
    private static final int GLASS_ANIMATED = 13;
    private static final int LOOK_ICONS = 60, LOOK_RESTART = 61, FONT_BASE = 70;
    private static final int OTHER_KEEPALIVE = 80, OTHER_CALLS = 81, OTHER_BADGES = 82, OTHER_EXPORT = 83, OTHER_IMPORT = 84;
    private static final int REQUEST_EXPORT = 2028, REQUEST_IMPORT = 2029, REQUEST_PLUGIN = 2030;
    private static final int OPEN_PLUGINS = 90, PLUGIN_INSTALL = 91;
    private boolean fontChanged;

    private static final int OPEN_GLASS = 1, OPEN_GHOST = 2, OPEN_MESSAGES = 3, OPEN_COUPLE = 4, OPEN_THEMES = 5, OPEN_BANNER = 6, CHECK_UPDATE = 7;
    private static final int GLASS_TOGGLE = 10, GLASS_PICK = 11, GLASS_RESET = 12;
    private static final int GHOST_ONLINE = 20, GHOST_TYPING = 21, GHOST_READ = 22, GHOST_STORIES = 23, GHOST_SCHEDULED = 24;
    private static final int KEEP_DELETED = 30, KEEP_EDITS = 31, CLEAR_STORE = 32;
    private static final int COUPLE_VISIBLE = 40, COUPLE_BREAK = 41, COUPLE_PARTNER = 42;
    private static final int BANNER_PICK = 50, BANNER_DELETE = 51;
    private static final int THEME_BASE = 100, INVITE_BASE = 1000, IGNORED_BASE = 5000;

    private static final int REQUEST_BACKGROUND = 2026, REQUEST_BANNER = 2027;

    private static final String[][] THEMES = {
        {"shuza_lilac.attheme", "ShuzaGram Lilac"},
        {"shuza_mint.attheme", "ShuzaGram Mint"},
        {"shuza_amoled.attheme", "ShuzaGram AMOLED"},
        {"shuza_crimson.attheme", "ShuzaGram Crimson"},
    };

    private final int page;
    private boolean bannerUploading;

    public ShuzaModSettingsActivity() {
        this(PAGE_HUB);
    }

    public ShuzaModSettingsActivity(int page) {
        this.page = page;
    }

    @Override
    protected CharSequence getTitle() {
        switch (page) {
            case PAGE_GLASS: return getString(R.string.ShuzaModGlassTitle);
            case PAGE_GHOST: return getString(R.string.ShuzaModGhostHeader);
            case PAGE_MESSAGES: return getString(R.string.ShuzaModMessagesHeader);
            case PAGE_COUPLE: return getString(R.string.ShuzaModCoupleHeader);
            case PAGE_THEMES: return getString(R.string.ShuzaModThemesHeader);
            case PAGE_BANNER: return getString(R.string.ShuzaModBannerTitle);
            case PAGE_LOOK: return getString(R.string.ShuzaModLookTitle);
            case PAGE_OTHER: return getString(R.string.ShuzaModOtherTitle);
            default: return getString(R.string.ShuzaModTitle);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        ShuzaMod.load();
        switch (page) {
            case PAGE_GLASS: fillGlass(items); break;
            case PAGE_GHOST: fillGhost(items); break;
            case PAGE_MESSAGES: fillMessages(items); break;
            case PAGE_COUPLE: fillCouple(items); break;
            case PAGE_THEMES: fillThemes(items); break;
            case PAGE_BANNER: fillBanner(items); break;
            case PAGE_LOOK: fillLook(items); break;
            case PAGE_OTHER: fillOther(items); break;
            default: fillHub(items);
        }
    }

    private static UItem row(int id, IconBackgroundColors color, int icon, CharSequence title, CharSequence subtitle) {
        return SettingsActivity.SettingCell.Factory.of(id, color.top, color.bottom, icon, title, subtitle);
    }

    private void fillHub(ArrayList<UItem> items) {
        items.add(UItem.asShadow(null));
        items.add(row(OPEN_GLASS, IconBackgroundColors.PURPLE, R.drawable.settings_features, getString(R.string.ShuzaModGlassTitle), getString(R.string.ShuzaModGlassSubtitle)));
        items.add(row(OPEN_BANNER, IconBackgroundColors.BLUE_LIGHT, R.drawable.settings_account, getString(R.string.ShuzaModBannerTitle), getString(R.string.ShuzaModBannerSubtitle)));
        items.add(row(OPEN_LOOK, IconBackgroundColors.CYAN, R.drawable.settings_language, getString(R.string.ShuzaModLookTitle), getString(R.string.ShuzaModLookSubtitle)));
        items.add(row(OPEN_THEMES, IconBackgroundColors.ORANGE, R.drawable.settings_chat, getString(R.string.ShuzaModThemesHeader), getString(R.string.ShuzaModThemesSubtitle)));
        items.add(UItem.asShadow(null));
        int invites = ShuzaModCloud.myCouple(currentAccount) == null ? ShuzaModCloud.invites(currentAccount).size() : 0;
        ShuzaModCloud.Couple couple = ShuzaModCloud.myCouple(currentAccount);
        CharSequence coupleSubtitle = couple != null ? "💞 " + userName(couple.partnerId) + " · " + LocaleController.formatPluralString("ShuzaModTogetherDays", couple.days())
            : invites > 0 ? "💌 " + LocaleController.formatPluralString("ShuzaModCoupleInvites", invites) : getString(R.string.ShuzaModCoupleSubtitle);
        items.add(row(OPEN_COUPLE, IconBackgroundColors.RED, R.drawable.settings_gift, getString(R.string.ShuzaModCoupleHeader), coupleSubtitle));
        items.add(row(OPEN_GHOST, IconBackgroundColors.GRAY, R.drawable.settings_privacy, getString(R.string.ShuzaModGhostHeader), ghostSummary()));
        items.add(row(OPEN_MESSAGES, IconBackgroundColors.GREEN, R.drawable.settings_data, getString(R.string.ShuzaModMessagesHeader), getString(R.string.ShuzaModMessagesSubtitle)));
        items.add(row(OPEN_OTHER, IconBackgroundColors.BLUE_DEEP, R.drawable.settings_devices, getString(R.string.ShuzaModOtherTitle), getString(R.string.ShuzaModOtherSubtitle)));
        items.add(UItem.asShadow(null));
        items.add(row(OPEN_PLUGINS, IconBackgroundColors.PURPLE, R.drawable.settings_features, getString(R.string.ShuzaModPluginsTitle), getString(R.string.ShuzaModPluginsSubtitle)));
        items.add(UItem.asButton(PLUGIN_INSTALL, R.drawable.msg_download, getString(R.string.ShuzaModPluginInstall)));
        items.add(UItem.asShadow(getString(R.string.ShuzaModPluginWarning)));
        items.add(row(CHECK_UPDATE, IconBackgroundColors.BLUE, R.drawable.settings_faq, getString(R.string.ShuzaModCheckUpdate), LocaleController.formatString(R.string.ShuzaModVersion, ShuzaModUpdater.currentVersionCode())));
        items.add(UItem.asShadow(null));
    }

    private CharSequence ghostSummary() {
        int on = (ShuzaMod.ghostOnline ? 1 : 0) + (ShuzaMod.ghostTyping ? 1 : 0) + (ShuzaMod.ghostRead ? 1 : 0) + (ShuzaMod.ghostStories ? 1 : 0);
        return on == 0 ? getString(R.string.ShuzaModGhostOff) : LocaleController.formatString(R.string.ShuzaModGhostOn, on, 4);
    }

    private void fillGlass(ArrayList<UItem> items) {
        items.add(UItem.asCheck(GLASS_TOGGLE, getString(R.string.ShuzaModGlassToggle)).setChecked(ShuzaMod.glassEnabled()));
        items.add(UItem.asShadow(getString(R.string.ShuzaModGlassInfo)));
        if (!ShuzaMod.glassEnabled()) {
            return;
        }
        items.add(UItem.asHeader(getString(R.string.ShuzaModGlassOpacity)));
        items.add(UItem.asIntSlideView(1, 0, ShuzaMod.glassOpacity(), 100, v -> v + "%", v -> setGlassInt("glass_opacity", v, false)));
        items.add(UItem.asHeader(getString(R.string.ShuzaModBackgroundBlur)));
        items.add(UItem.asIntSlideView(1, 0, ShuzaMod.backgroundBlur(), 100, v -> v + "%", v -> setGlassInt("glass_blur", v, true)));
        items.add(UItem.asHeader(getString(R.string.ShuzaModBackgroundDim)));
        items.add(UItem.asIntSlideView(1, 0, ShuzaMod.backgroundDim(), 100, v -> v + "%", v -> setGlassInt("glass_dim", v, true)));
        if (!ShuzaMod.hasCustomBackground()) {
            items.add(UItem.asCheck(GLASS_ANIMATED, getString(R.string.ShuzaModGlassAnimated)).setChecked(ShuzaMod.prefs().getBoolean("glass_animated", false)));
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(GLASS_PICK, R.drawable.msg_photos, getString(ShuzaMod.hasCustomBackground() ? R.string.ShuzaModBackgroundChange : R.string.ShuzaModBackgroundPick)).accent());
        if (ShuzaMod.hasCustomBackground()) {
            items.add(UItem.asButton(GLASS_RESET, R.drawable.msg_reset, getString(R.string.ShuzaModBackgroundDefault)));
        }
        items.add(UItem.asShadow(getString(R.string.ShuzaModBackgroundInfo)));
    }

    private void fillLook(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.ShuzaModLookTextSize)));
        items.add(UItem.asIntSlideView(1, 12, org.telegram.messenger.SharedConfig.fontSize, 30, v -> String.valueOf(v), this::setTextSize));
        items.add(UItem.asHeader(getString(R.string.ShuzaModLookBubbles)));
        items.add(UItem.asIntSlideView(1, 0, org.telegram.messenger.SharedConfig.bubbleRadius, 17, v -> String.valueOf(v), this::setBubbleRadius));
        items.add(UItem.asShadow(getString(R.string.ShuzaModLookSizesInfo)));
        items.add(UItem.asHeader(getString(R.string.ShuzaModLookFont)));
        String current = ShuzaMod.fontName();
        for (int i = 0; i < ShuzaMod.FONTS.length; i++) {
            String title = i == 0 ? getString(R.string.ShuzaModLookFontSystem) : ShuzaMod.FONTS[i][1];
            items.add(UItem.asRadio(FONT_BASE + i, title).setChecked(current.equals(ShuzaMod.FONTS[i][0])));
        }
        if (fontChanged) {
            items.add(UItem.asButton(LOOK_RESTART, R.drawable.msg_retry, getString(R.string.ShuzaModLookRestart)).accent());
        }
        items.add(UItem.asShadow(getString(R.string.ShuzaModLookFontInfo)));
        items.add(UItem.asButton(LOOK_ICONS, R.drawable.msg_customize, getString(R.string.ShuzaModLookIcons)));
        items.add(UItem.asShadow(getString(R.string.ShuzaModLookIconsInfo)));
    }

    private void fillOther(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.ShuzaModOtherTitle)));
        items.add(UItem.asCheck(OTHER_KEEPALIVE, getString(R.string.ShuzaModKeepAlive)).setChecked(keepAliveEnabled()));
        items.add(UItem.asCheck(OTHER_CALLS, getString(R.string.ShuzaModConfirmCalls)).setChecked(ShuzaMod.prefs().getBoolean("confirm_calls", true)));
        items.add(UItem.asCheck(OTHER_BADGES, getString(R.string.ShuzaModBadges)).setChecked(ShuzaMod.prefs().getBoolean("show_badges", true)));
        items.add(UItem.asShadow(getString(R.string.ShuzaModOtherInfo)));
        items.add(UItem.asHeader(getString(R.string.ShuzaModBackupHeader)));
        items.add(UItem.asButton(OTHER_EXPORT, R.drawable.msg_download, getString(R.string.ShuzaModBackupExport)));
        items.add(UItem.asButton(OTHER_IMPORT, R.drawable.msg_reset, getString(R.string.ShuzaModBackupImport)));
        items.add(UItem.asShadow(getString(R.string.ShuzaModBackupInfo)));
    }

    private static boolean keepAliveEnabled() {
        android.content.SharedPreferences p = org.telegram.messenger.MessagesController.getGlobalNotificationsSettings();
        return p.getBoolean("pushService", true);
    }

    private void setTextSize(int size) {
        if (size == org.telegram.messenger.SharedConfig.fontSize) {
            return;
        }
        org.telegram.messenger.SharedConfig.fontSize = size;
        org.telegram.messenger.SharedConfig.fontSizeIsDefault = false;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putInt("fons_size", size).apply();
        Theme.createCommonMessageResources();
    }

    private void setBubbleRadius(int radius) {
        if (radius == org.telegram.messenger.SharedConfig.bubbleRadius) {
            return;
        }
        org.telegram.messenger.SharedConfig.bubbleRadius = radius;
        org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", radius).apply();
    }

    private void fillGhost(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.ShuzaModGhostHeader)));
        items.add(UItem.asCheck(GHOST_ONLINE, getString(R.string.ShuzaModGhostOnline)).setChecked(ShuzaMod.ghostOnline));
        items.add(UItem.asCheck(GHOST_TYPING, getString(R.string.ShuzaModGhostTyping)).setChecked(ShuzaMod.ghostTyping));
        items.add(UItem.asCheck(GHOST_READ, getString(R.string.ShuzaModGhostRead)).setChecked(ShuzaMod.ghostRead));
        items.add(UItem.asCheck(GHOST_STORIES, getString(R.string.ShuzaModGhostStories)).setChecked(ShuzaMod.ghostStories));
        items.add(UItem.asCheck(GHOST_SCHEDULED, getString(R.string.ShuzaModGhostScheduled)).setChecked(ShuzaMod.ghostScheduledSend()));
        items.add(UItem.asShadow(getString(R.string.ShuzaModGhostScheduledInfo)));
    }

    private void fillMessages(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.ShuzaModMessagesHeader)));
        items.add(UItem.asCheck(KEEP_DELETED, getString(R.string.ShuzaModKeepDeleted)).setChecked(ShuzaMod.keepDeleted));
        items.add(UItem.asCheck(KEEP_EDITS, getString(R.string.ShuzaModKeepEdits)).setChecked(ShuzaMod.keepEdits));
        items.add(UItem.asShadow(getString(R.string.ShuzaModMessagesInfo)));
        items.add(UItem.asButton(CLEAR_STORE, R.drawable.msg_delete, getString(R.string.ShuzaModClearStore)).red());
        items.add(UItem.asShadow(null));
    }

    private void fillThemes(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.ShuzaModThemesHeader)));
        for (int i = 0; i < THEMES.length; i++) {
            items.add(UItem.asButton(THEME_BASE + i, R.drawable.msg_theme, THEMES[i][1]));
        }
        items.add(UItem.asShadow(getString(R.string.ShuzaModThemesInfo)));
    }

    private void fillBanner(ArrayList<UItem> items) {
        boolean has = ShuzaModCloud.hasOwnBanner(currentAccount);
        items.add(UItem.asShadow(getString(R.string.ShuzaModBannerInfo)));
        items.add(UItem.asButton(BANNER_PICK, R.drawable.msg_photos, getString(bannerUploading ? R.string.ShuzaModBannerUploading : has ? R.string.ShuzaModBannerChange : R.string.ShuzaModBannerPick)).accent());
        if (has) {
            items.add(UItem.asButton(BANNER_DELETE, R.drawable.msg_delete, getString(R.string.ShuzaModBannerDelete)).red());
        }
        items.add(UItem.asShadow(null));
    }

    private void fillCouple(ArrayList<UItem> items) {
        ShuzaModCloud.Couple couple = ShuzaModCloud.myCouple(currentAccount);
        if (couple != null) {
            items.add(UItem.asHeader(getString(R.string.ShuzaModCoupleHeader)));
            items.add(UItem.asButton(COUPLE_PARTNER, "💞 " + LocaleController.formatString(R.string.ShuzaModCoupleWith, userName(couple.partnerId)),
                ShuzaModCloud.levelEmoji(couple.days()) + " " + LocaleController.formatPluralString("ShuzaModTogetherDays", couple.days())));
            boolean blockedByPartner = couple.hidden && couple.hiddenBy != 0 && couple.hiddenBy != getUserConfig().getClientUserId();
            items.add(UItem.asCheck(COUPLE_VISIBLE, getString(R.string.ShuzaModCoupleVisible)).setChecked(!couple.hidden).setEnabled(!blockedByPartner));
            items.add(UItem.asShadow(getString(blockedByPartner ? R.string.ShuzaModCoupleHiddenByPartner : R.string.ShuzaModCoupleVisibleInfo)));
            items.add(UItem.asButton(COUPLE_BREAK, R.drawable.msg_delete, getString(R.string.ShuzaModCoupleBreak)).red());
            items.add(UItem.asShadow(null));
        } else {
            ArrayList<Long> invites = ShuzaModCloud.invites(currentAccount);
            if (!invites.isEmpty()) {
                items.add(UItem.asHeader(getString(R.string.ShuzaModCoupleInvitesHeader)));
                for (int i = 0; i < invites.size() && i < 50; i++) {
                    items.add(UItem.asButton(INVITE_BASE + i, "💌 " + userName(invites.get(i)), getString(R.string.ShuzaModCoupleInviteShort)));
                }
            }
            items.add(UItem.asShadow(getString(R.string.ShuzaModCoupleNone)));
        }
        ArrayList<Long> ignored = ShuzaModCloud.ignored(currentAccount);
        if (!ignored.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.ShuzaModCoupleIgnoredHeader)));
            for (int i = 0; i < ignored.size() && i < 50; i++) {
                items.add(UItem.asButton(IGNORED_BASE + i, userName(ignored.get(i))));
            }
            items.add(UItem.asShadow(getString(R.string.ShuzaModCoupleIgnoredInfo)));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case OPEN_GLASS: presentFragment(new ShuzaModSettingsActivity(PAGE_GLASS)); return;
            case OPEN_GHOST: presentFragment(new ShuzaModSettingsActivity(PAGE_GHOST)); return;
            case OPEN_MESSAGES: presentFragment(new ShuzaModSettingsActivity(PAGE_MESSAGES)); return;
            case OPEN_COUPLE: presentFragment(new ShuzaModSettingsActivity(PAGE_COUPLE)); return;
            case OPEN_THEMES: presentFragment(new ShuzaModSettingsActivity(PAGE_THEMES)); return;
            case OPEN_BANNER: presentFragment(new ShuzaModSettingsActivity(PAGE_BANNER)); return;
            case OPEN_LOOK: presentFragment(new ShuzaModSettingsActivity(PAGE_LOOK)); return;
            case OPEN_OTHER: presentFragment(new ShuzaModSettingsActivity(PAGE_OTHER)); return;
            case OPEN_PLUGINS: presentFragment(new ShuzaPluginsActivity()); return;
            case PLUGIN_INSTALL: pickImageOrFile(); return;
            case GLASS_ANIMATED:
                ShuzaMod.prefs().edit().putBoolean("glass_animated", !ShuzaMod.prefs().getBoolean("glass_animated", false)).apply();
                glassChanged(false);
                return;
            case LOOK_ICONS: presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)); return;
            case LOOK_RESTART: restartApp(); return;
            case OTHER_KEEPALIVE: {
                boolean enabled = !keepAliveEnabled();
                org.telegram.messenger.MessagesController.getGlobalNotificationsSettings().edit().putBoolean("pushService", enabled).putBoolean("pushConnection", enabled).apply();
                for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                        org.telegram.tgnet.ConnectionsManager.getInstance(a).setPushConnectionEnabled(enabled);
                    }
                }
                ApplicationLoader.startPushService();
                refresh();
                return;
            }
            case OTHER_CALLS: ShuzaMod.prefs().edit().putBoolean("confirm_calls", !ShuzaMod.prefs().getBoolean("confirm_calls", true)).apply(); refresh(); return;
            case OTHER_BADGES:
                ShuzaMod.prefs().edit().putBoolean("show_badges", !ShuzaMod.prefs().getBoolean("show_badges", true)).apply();
                getNotificationCenter().postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, org.telegram.messenger.MessagesController.UPDATE_MASK_NAME);
                refresh();
                return;
            case OTHER_EXPORT:
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, "ShuzaGram-backup.json");
                    startActivityForResult(intent, REQUEST_EXPORT);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                return;
            case OTHER_IMPORT:
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, REQUEST_IMPORT);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                return;
            case CHECK_UPDATE: ShuzaModUpdater.check(getParentActivity(), true); return;
            case GLASS_TOGGLE:
                ShuzaMod.prefs().edit().putBoolean("glass", !ShuzaMod.glassEnabled()).apply();
                glassChanged(false);
                return;
            case GLASS_PICK: pickImage(REQUEST_BACKGROUND); return;
            case GLASS_RESET:
                Utilities.globalQueue.postRunnable(() -> {
                    ShuzaMod.resetBackground();
                    AndroidUtilities.runOnUIThread(() -> glassChanged(false));
                });
                return;
            case GHOST_ONLINE: toggle("ghost_online", !ShuzaMod.ghostOnline); return;
            case GHOST_TYPING: toggle("ghost_typing", !ShuzaMod.ghostTyping); return;
            case GHOST_READ: toggle("ghost_read", !ShuzaMod.ghostRead); return;
            case GHOST_STORIES: toggle("ghost_stories", !ShuzaMod.ghostStories); return;
            case GHOST_SCHEDULED: toggle("ghost_scheduled", !ShuzaMod.ghostScheduledSend()); return;
            case KEEP_DELETED: toggle("keep_deleted", !ShuzaMod.keepDeleted); return;
            case KEEP_EDITS: toggle("keep_edits", !ShuzaMod.keepEdits); return;
            case CLEAR_STORE:
                confirm(getString(R.string.ShuzaModClearStore), getString(R.string.ShuzaModClearStoreConfirm), getString(R.string.Delete), () -> {
                    ShuzaMod.clearStore();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.ShuzaModCleared)).show();
                });
                return;
            case BANNER_PICK:
                if (!bannerUploading) {
                    pickImage(REQUEST_BANNER);
                }
                return;
            case BANNER_DELETE: ShuzaModCloud.deleteBanner(currentAccount, this::refresh); return;
            case COUPLE_PARTNER: {
                ShuzaModCloud.Couple c = ShuzaModCloud.myCouple(currentAccount);
                if (c != null) {
                    Bundle args = new Bundle();
                    args.putLong("user_id", c.partnerId);
                    presentFragment(new ProfileActivity(args));
                }
                return;
            }
            case COUPLE_VISIBLE: {
                ShuzaModCloud.Couple c = ShuzaModCloud.myCouple(currentAccount);
                if (c == null) {
                    return;
                }
                if (!c.hidden) {
                    confirm(getString(R.string.ShuzaModCoupleVisible), getString(R.string.ShuzaModCoupleHideConfirm), getString(R.string.ShuzaModCoupleHide),
                        () -> ShuzaModCloud.setVisible(currentAccount, false, this::refreshCouple));
                } else {
                    ShuzaModCloud.setVisible(currentAccount, true, this::refreshCouple);
                }
                return;
            }
            case COUPLE_BREAK:
                confirm(getString(R.string.ShuzaModCoupleBreak), getString(R.string.ShuzaModCoupleBreakConfirm), getString(R.string.ShuzaModCoupleBreak),
                    () -> ShuzaModCloud.breakUp(currentAccount, this::refreshCouple));
                return;
        }
        if (item.id >= FONT_BASE && item.id < FONT_BASE + ShuzaMod.FONTS.length) {
            String name = ShuzaMod.FONTS[item.id - FONT_BASE][0];
            if (!name.equals(ShuzaMod.fontName())) {
                ShuzaMod.prefs().edit().putString("font", name).apply();
                fontChanged = true;
                refresh();
            }
            return;
        }
        if (item.id >= INVITE_BASE && item.id < INVITE_BASE + 50) {
            ArrayList<Long> invites = ShuzaModCloud.invites(currentAccount);
            int index = item.id - INVITE_BASE;
            if (index < invites.size()) {
                ShuzaModCloud.showInvite(getParentActivity(), currentAccount, invites.get(index));
            }
        } else if (item.id >= IGNORED_BASE && item.id < IGNORED_BASE + 50) {
            ArrayList<Long> ignored = ShuzaModCloud.ignored(currentAccount);
            int index = item.id - IGNORED_BASE;
            if (index < ignored.size()) {
                long id = ignored.get(index);
                confirm(userName(id), getString(R.string.ShuzaModCoupleUnignoreConfirm), getString(R.string.ShuzaModCoupleUnignore),
                    () -> ShuzaModCloud.unignore(currentAccount, id, this::refreshCouple));
            }
        } else if (item.id >= THEME_BASE && item.id < THEME_BASE + THEMES.length) {
            applyTheme(THEMES[item.id - THEME_BASE]);
        }
    }

    private void confirm(CharSequence title, CharSequence text, CharSequence button, Runnable action) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setMessage(text);
        builder.setPositiveButton(button, (d, w) -> action.run());
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void pickImageOrFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PLUGIN);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void installPluginFromUri(Uri uri) {
        Utilities.globalQueue.postRunnable(() -> {
            String result = null;
            try {
                File dir = new File(ApplicationLoader.getFilesDirFixed(), "plugins");
                dir.mkdirs();
                String name = "plugin_" + System.currentTimeMillis() + ".plugin";
                try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[16384];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    String head = new String(buf.toByteArray(), 0, Math.min(buf.size(), 4000), "UTF-8");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("__id__\\s*=\\s*[\"']([A-Za-z0-9_]+)").matcher(head);
                    if (m.find()) name = m.group(1) + ".plugin";
                    File out = new File(dir, name);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                        fo.write(buf.toByteArray());
                    }
                    result = org.telegram.messenger.plugins.PluginHost.install(out.getAbsolutePath(), true);
                }
            } catch (Exception e) {
                FileLog.e(e);
                result = "error: " + e;
            }
            final boolean ok = result != null && !result.startsWith("error");
            AndroidUtilities.runOnUIThread(() -> {
                BulletinFactory.of(this).createSimpleBulletin(ok ? R.raw.done : R.raw.error,
                        getString(ok ? R.string.ShuzaModPluginInstalled : R.string.ShuzaModPluginInstallError)).show();
            });
        });
    }

    private void pickImage(int requestCode) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_PLUGIN) {
            installPluginFromUri(uri);
            return;
        }
        if (requestCode == REQUEST_EXPORT || requestCode == REQUEST_IMPORT) {
            Utilities.globalQueue.postRunnable(() -> {
                boolean ok;
                try {
                    if (requestCode == REQUEST_EXPORT) {
                        try (java.io.OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri)) {
                            out.write(ShuzaMod.exportBackup().getBytes("UTF-8"));
                        }
                        ok = true;
                    } else {
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                            byte[] chunk = new byte[16384];
                            int n;
                            while ((n = in.read(chunk)) > 0) {
                                buffer.write(chunk, 0, n);
                            }
                        }
                        ok = ShuzaMod.importBackup(buffer.toString("UTF-8"));
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    ok = false;
                }
                final boolean result = ok;
                AndroidUtilities.runOnUIThread(() -> {
                    BulletinFactory.of(this).createSimpleBulletin(result ? R.raw.done : R.raw.error, getString(result ? (requestCode == REQUEST_EXPORT ? R.string.ShuzaModBackupSaved : R.string.ShuzaModBackupRestored) : R.string.ShuzaModBackupFailed)).show();
                    if (result && requestCode == REQUEST_IMPORT) {
                        glassChanged(false);
                    }
                });
            });
            return;
        }
        if (requestCode == REQUEST_BACKGROUND) {
            Utilities.globalQueue.postRunnable(() -> {
                boolean ok = ShuzaMod.setBackground(uri);
                AndroidUtilities.runOnUIThread(() -> {
                    if (ok) {
                        glassChanged(false);
                    }
                });
            });
        } else if (requestCode == REQUEST_BANNER) {
            bannerUploading = true;
            refresh();
            ShuzaModCloud.uploadBanner(currentAccount, uri, ok -> {
                bannerUploading = false;
                refresh();
                BulletinFactory.of(this).createSimpleBulletin(ok ? R.raw.done : R.raw.error, getString(ok ? R.string.ShuzaModBannerDone : R.string.ShuzaModUpdateError)).show();
            });
        }
    }

    // Slider changes: opacity applies instantly, blur and dim re-render the
    // background image; both wait for a short pause in the slider movement.
    private Runnable pendingGlass;

    private void setGlassInt(String key, int value, boolean rerender) {
        ShuzaMod.prefs().edit().putInt(key, value).apply();
        if (pendingGlass != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingGlass);
        }
        pendingGlass = () -> {
            pendingGlass = null;
            if (rerender) {
                Utilities.globalQueue.postRunnable(() -> {
                    ShuzaMod.renderBackground();
                    AndroidUtilities.runOnUIThread(() -> glassChanged(true));
                });
            } else {
                glassChanged(true);
            }
        };
        AndroidUtilities.runOnUIThread(pendingGlass, 250);
    }

    // Recolors every open screen so the new glass settings show everywhere.
    private void glassChanged(boolean keepList) {
        ShuzaMod.updateGlassState();
        Theme.reloadAllResources(getParentActivity());
        if (getParentActivity() != null) {
            getParentActivity().getWindow().getDecorView().invalidate();
        }
        if (parentLayout != null) {
            parentLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
        }
        if (!keepList) {
            refresh();
        }
    }

    private void restartApp() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void toggle(String key, boolean value) {
        ShuzaMod.set(key, value);
        refresh();
    }

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void refreshCouple() {
        ShuzaModCloud.checkMe(getParentActivity(), currentAccount, true);
        AndroidUtilities.runOnUIThread(this::refresh, 1500);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (page == PAGE_HUB || page == PAGE_COUPLE || page == PAGE_BANNER) {
            ShuzaModCloud.checkMe(getParentActivity(), currentAccount, true);
            ShuzaModCloud.coupleFor(currentAccount, getUserConfig().getClientUserId());
            AndroidUtilities.runOnUIThread(this::refresh, 1500);
        } else {
            refresh();
        }
    }

    private String userName(long id) {
        TLRPC.User user = getMessagesController().getUser(id);
        return user != null ? UserObject.getUserName(user) : String.valueOf(id);
    }

    private void applyTheme(String[] theme) {
        try {
            File file = new File(ApplicationLoader.applicationContext.getCacheDir(), theme[0]);
            try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(theme[0]); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            Theme.ThemeInfo info = Theme.applyThemeFile(file, theme[1], null, false);
            if (info != null && parentLayout != null) {
                AndroidUtilities.runOnUIThread(() -> parentLayout.rebuildAllFragmentViews(true, true), 50);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
