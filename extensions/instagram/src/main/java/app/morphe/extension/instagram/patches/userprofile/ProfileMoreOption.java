/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.app.Dialog;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.entity.ProfileInfo;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.crimera.ObjectBrowser;
import app.morphe.extension.instagram.settings.ActivityHook;
import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.entity.InstagramButton;
import app.morphe.extension.instagram.entity.InstagramButtonStyleEnum;
import app.morphe.extension.shared.ui.Dim;

import com.instagram.igds.components.button.IgdsButton;

public class ProfileMoreOption {
    private static boolean DEBUG;

    static {
        DEBUG = Pref.pikoDebug();
    }

    public static void moreOptionsDailogueBox(Context context, UserData userData) {
        try {
            InstagramDialogBox dialog = new InstagramDialogBox(context);

            ArrayList<String> options = new ArrayList<>();
            options.add(str("piko_view_profile_picture"));
            options.add(str("piko_download_profile_picture"));
            options.add(str("piko_copy_username"));
            options.add(str("piko_copy_full_name"));
            options.add(str("piko_copy_user_id"));
            options.add(str("piko_copy_profile_link"));
            options.add(str("piko_share_this_profile"));
            options.add(str("piko_copy_bio"));
            options.add(str("piko_copy_links_from_bio"));
            options.add(str(FavoritePages.isFavorite(userData.getUsername())
                    ? "piko_remove_from_favorite_pages"
                    : "piko_add_to_favorite_pages"));
            options.add(str("piko_favorite_pages"));
            if (DEBUG) options.add(str("piko_debug"));

            CharSequence[] items = options.toArray(new CharSequence[0]);

            dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    try {
                        // Doing like this because options are dynamic.
                        String selectedOption = options.get(which);
                        boolean toCopy = false;
                        String text = null;

                        if (selectedOption.equals(str("piko_copy_username"))) {
                            text = userData.getUsername();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_full_name"))) {
                            text = userData.getFullName();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_user_id"))) {
                            text = userData.getUserId();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_bio"))) {
                            text = userData.getBio();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_profile_link"))) {
                            text = userData.getProfileLink();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_share_this_profile"))) {
                            PikoUtils.shareText(userData.getProfileLink());

                        } else if (selectedOption.equals(str("piko_copy_links_from_bio"))) {
                            // Links live in the bio as plain text; the profile object
                            // carries no separate list of them.
                            String links = linksFrom(userData.getBio());
                            if (links == null) {
                                Utils.showToastShort(str("piko_no_links_in_bio"));
                            } else {
                                text = links;
                                toCopy = true;
                            }

                        } else if (selectedOption.equals(str("piko_add_to_favorite_pages"))
                                || selectedOption.equals(str("piko_remove_from_favorite_pages"))) {
                            FavoritePages.toggle(userData.getUsername());

                        } else if (selectedOption.equals(str("piko_favorite_pages"))) {
                            FavoritePages.showList(context);

                        } else if (selectedOption.equals(str("piko_view_profile_picture"))) {
                            ActivityHook.handleUrlIntent(false, userData.getProfilePictureUrl());

                        } else if (selectedOption.equals(str("piko_download_profile_picture"))) {
                            String url = userData.getProfilePictureUrl();
                            String username = userData.getUsername();
                            String downloadFilename = username+"_dp.jpg";
                            String subFolder = DownloadUtils.getSubfolderName(username);
                            DownloadUtils.downloadMediaUrl(context, url, subFolder, downloadFilename);
                            toCopy = false;

                        } else if (selectedOption.equals(str("piko_debug"))) {
                            ObjectBrowser.browseObject(context, userData.getObject());

                        }

                        if (toCopy && text != null && text.length() > 0) {
                            Utils.setClipboard(text);
                            Utils.showToastShort(str("piko_copied"));
                        }
                    } catch (Exception e) {
                        Logger.printException(() -> "Error at moreOptionsDailogueBox onclick", e);
                        Utils.showToastShort(e.getMessage());
                    }
                }
            });
            dialog.setTitle(str("piko_more_profile_options"));
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            Dialog dlg = dialog.getDialog();
            dlg.show();
        } catch (Exception e) {
            Logger.printException(() -> "Error at moreOptionsDailogueBox", e);
            Utils.showToastShort(e.getMessage());
        }
    }

    /**
     * Every link written in a bio, one per line. Instagram stores the bio as plain
     * text, so a URL there is only ever a URL by how it reads — matched loosely on
     * purpose, since most bios write "example.com" without a scheme.
     */
    private static String linksFrom(String bio) {
        if (bio == null || bio.isEmpty()) return null;
        Matcher matcher = BIO_LINK.matcher(bio);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String link = matcher.group();
            if (out.indexOf(link) >= 0) continue; // the same link twice reads as noise
            if (out.length() > 0) out.append('\n');
            out.append(link);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static final Pattern BIO_LINK = Pattern.compile(
            "(?:https?://)?(?:[\\w-]+\\.)+[a-z]{2,}(?:/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE);

    /** The theme's own primary text colour, so the glyph reads on light and dark alike. */
    private static int themeTextColor(Context context) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            if (value.resourceId != 0) return context.getColor(value.resourceId);
            if (value.data != 0) return value.data;
        }
        return 0xFFFFFFFF;
    }

    /** Marks our icon so neither hook adds a second one to the same screen. */
    private static final String ICON_TAG = "piko_profile_options_icon";

    /**
     * The options entry point: a compact glyph, sized by padding so the touch target
     * stays comfortable, coloured from the theme so it reads on light and dark alike.
     */
    public static TextView createOptionsIcon(Context context, UserData userData) {
        TextView icon = new TextView(context);
        icon.setTag(ICON_TAG);
        icon.setText("\u2630");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        icon.setTextColor(themeTextColor(context));
        icon.setContentDescription(str("piko_more_profile_options"));
        icon.setPadding(Dim.dp8, Dim.dp2, Dim.dp8, Dim.dp2);
        icon.setOnClickListener(v -> moreOptionsDailogueBox(context, userData));
        return icon;
    }

    /** True when this screen already carries the icon, wherever it was placed. */
    public static boolean alreadyPlaced(View anyViewOnScreen) {
        return anyViewOnScreen != null && findTagged(anyViewOnScreen.getRootView()) != null;
    }

    private static View findTagged(View view) {
        if (view == null) return null;
        if (ICON_TAG.equals(view.getTag())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findTagged(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    public static void addProfileMoreOptionsButton(ViewGroup viewGroup, ProfileInfo profileInfo) {
        try {
            UserData userData = profileInfo.getUserData();

            Context context = viewGroup.getContext();

            // The preferred home for the icon is the follow-status row, placed there
            // by FriendshipStatusIndicator as it binds the badge. Which of the two
            // hooks runs first is not ours to decide, so this one waits a frame and
            // only steps in when that row never got it — the badge is hidden on your
            // own profile, and off entirely when the indicator setting is.
            viewGroup.post(() -> {
                if (alreadyPlaced(viewGroup)) return;
                TextView icon = createOptionsIcon(context, userData);
                viewGroup.addView(icon);
                icon.bringToFront();
                viewGroup.requestLayout();
                viewGroup.invalidate();
            });
        } catch (Exception e) {
            Logger.printException(() -> "Failed to add profile more button: ", e);
        }
    }
}

