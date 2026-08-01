/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * A list of accounts worth coming back to, kept in the app's own preferences.
 *
 * <p>Instagram's own way back to a profile is the search box, which means typing a
 * handle you have to remember. This stores the handles you mark and opens them
 * again from one dialog — the profile menu when you are on a page, the settings
 * screen when you are not.
 */
public class FavoritePages {

    /** Handles, lowercased, in whatever order the set store returns them. */
    private static Set<String> stored() {
        Set<String> saved = SharedPref.getSetPref(Settings.FAVORITE_PAGES);
        return saved == null ? new LinkedHashSet<>() : new LinkedHashSet<>(saved);
    }

    private static void save(Set<String> pages) {
        SharedPref.setSetPref(Settings.FAVORITE_PAGES.key, pages);
    }

    private static String normalise(String username) {
        if (username == null) return "";
        String handle = username.trim();
        if (handle.startsWith("@")) handle = handle.substring(1);
        return handle.toLowerCase();
    }

    public static boolean isFavorite(String username) {
        String handle = normalise(username);
        return !handle.isEmpty() && stored().contains(handle);
    }

    /** Adds or removes the page. Returns true when it is a favorite afterwards. */
    public static boolean toggle(String username) {
        String handle = normalise(username);
        if (handle.isEmpty()) return false;

        Set<String> pages = stored();
        boolean added = pages.contains(handle) ? !pages.remove(handle) : pages.add(handle);
        save(pages);
        Utils.showToastShort(str(added ? "piko_added_to_favorite_pages" : "piko_removed_from_favorite_pages"));
        return added;
    }

    /** Sorted, so the dialog reads the same way twice. */
    public static List<String> list() {
        return new ArrayList<>(new TreeSet<>(stored()));
    }

    /**
     * The list itself: tapping a handle opens that profile inside Instagram rather
     * than in a browser, which is the whole point of keeping it here.
     */
    public static void showList(Context context) {
        try {
            List<String> pages = list();
            if (pages.isEmpty()) {
                Utils.showToastShort(str("piko_no_favorite_pages"));
                return;
            }

            InstagramDialogBox dialog = new InstagramDialogBox(context);
            CharSequence[] items = new CharSequence[pages.size()];
            for (int i = 0; i < pages.size(); i++) items[i] = "@" + pages.get(i);

            dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    PikoUtils.openUrl("https://www.instagram.com/" + pages.get(which) + "/", true);
                }
            });

            dialog.setTitle(str("piko_favorite_pages"));
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            Dialog dlg = dialog.getDialog();
            dlg.show();
        } catch (Exception e) {
            Logger.printException(() -> "Error at showList", e);
            Utils.showToastShort(e.getMessage());
        }
    }
}
