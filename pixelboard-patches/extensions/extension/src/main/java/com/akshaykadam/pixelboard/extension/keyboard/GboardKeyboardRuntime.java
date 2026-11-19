package com.akshaykadam.pixelboard.extension.keyboard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import com.akshaykadam.pixelboard.extension.settings.GboardPatchesSettings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class GboardKeyboardRuntime {
    private static final float BASE_GLOW_DP = 1.25f;
    private static final float PRESSED_GLOW_DP = 3.75f;
    private static final long HUE_CYCLE_MS = 5200L;

    private static final List<WeakReference<View>> GLOW_VIEWS =
            new ArrayList<WeakReference<View>>();
    private static ValueAnimator hueAnimator;

    private GboardKeyboardRuntime() {}

    /**
     * Sanitizes softkey labels at runtime.
     * Strips escaping backslashes from question marks (e.g. "\?123" -> "?123", "\\?123" -> "?123",
     * "\123؟" -> "123؟") that arise from AAPT/ARSCLib XML attribute escaping or on-device layout caches,
     * and recovers question mark keys corrupted into theme attribute placeholders (e.g. "?attr/" -> "?").
     */
    public static CharSequence sanitizeKeyLabel(CharSequence text) {
        if (text == null || text.length() == 0) {
            return text;
        }
        String s = text.toString();
        String result = s;
        if (result.contains("?attr/")) {
            result = result.replace("?attr/", "?");
        }
        if (result.indexOf('\\') != -1 && (result.contains("\\?") || result.contains("\\\u061f"))) {
            result = result.replaceAll("\\\\+(\\?|\\u061f)", "$1");
        }
        return result.equals(s) ? text : result;
    }

    /**
     * Applies a slow RGB border glow to a softkey label's nearest key surface.
     * A short stronger press glow is triggered by the key's touch event.
     */
    public static void applyRgbGlow(View labelView) {
        if (labelView == null) {
            return;
        }
        Context context = labelView.getContext();
        if (context == null) {
            return;
        }
        try {
            SharedPreferences preferences = GboardPatchesSettings.preferences(context);
            if (preferences == null || !preferences.getBoolean("rgb_key_glow_enabled", true)) {
                clearRgbGlows();
                return;
            }

            View keySurface = findKeySurface(labelView);
            if (keySurface == null) {
                keySurface = labelView;
            }

            GradientDrawable glow = getOrCreateGlow(keySurface);
            registerGlowView(keySurface);
            updateGlow(keySurface, glow, System.currentTimeMillis());
            ensureHueAnimator();
        } catch (Throwable ignored) {
        }
    }

    private static void clearRgbGlows() {
        if (hueAnimator != null) {
            hueAnimator.cancel();
            hueAnimator = null;
        }
        pruneReferences(GLOW_VIEWS);
        for (WeakReference<View> reference : GLOW_VIEWS) {
            View view = reference.get();
            if (view != null && view.getForeground() instanceof GradientDrawable) {
                GradientDrawable glow = (GradientDrawable) view.getForeground();
                glow.setStroke(0, Color.TRANSPARENT);
                glow.setAlpha(0);
                view.invalidate();
            }
        }
        GLOW_VIEWS.clear();
    }

    private static View findKeySurface(View labelView) {
        View current = labelView;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                if (group.getParent() instanceof ViewGroup
                        && group.getWidth() > 0
                        && group.getHeight() > 0) {
                    return group;
                }
            }
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }
        return labelView;
    }

    private static GradientDrawable getOrCreateGlow(View surface) {
        Object taggedGlow = surface.getTag(computeGlowTagId());
        if (taggedGlow instanceof GradientDrawable) {
            return (GradientDrawable) taggedGlow;
        }

        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.TRANSPARENT);
        drawable.setStroke(dp(surface, BASE_GLOW_DP), Color.argb(128, 80, 220, 255));

        surface.setForeground(drawable);
        surface.setTag(computeGlowTagId(), drawable);
        return drawable;
    }

    private static int computeGlowTagId() {
        return 0x5042474C;
    }

    private static void registerGlowView(View view) {
        pruneReferences(GLOW_VIEWS);
        for (WeakReference<View> reference : GLOW_VIEWS) {
            if (reference.get() == view) {
                return;
            }
        }
        GLOW_VIEWS.add(new WeakReference<View>(view));
    }

    private static void pruneReferences(List<WeakReference<View>> references) {
        Iterator<WeakReference<View>> iterator = references.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) {
                iterator.remove();
            }
        }
    }

    private static void ensureHueAnimator() {
        if (hueAnimator != null && hueAnimator.isStarted()) {
            return;
        }
        hueAnimator = ValueAnimator.ofFloat(0f, 1f);
        hueAnimator.setDuration(HUE_CYCLE_MS);
        hueAnimator.setRepeatCount(ValueAnimator.INFINITE);
        hueAnimator.setInterpolator(new LinearInterpolator());
        hueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                long now = System.currentTimeMillis();
                for (WeakReference<View> reference : GLOW_VIEWS) {
                    View view = reference.get();
                    if (view == null) {
                        continue;
                    }
                    updateGlow(view,
                            view.getForeground() instanceof GradientDrawable
                                    ? (GradientDrawable) view.getForeground()
                                    : null,
                            now);
                }
            }
        });
        hueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                hueAnimator = null;
            }
        });
        hueAnimator.start();
    }

    private static void updateGlow(View surface, GradientDrawable glow, long now) {
        if (glow == null) {
            return;
        }
        if (isPressed(surface)) {
            glow.setStroke(dp(surface, PRESSED_GLOW_DP),
                    glowColor(now, true));
            glow.setAlpha(255);
        } else {
            glow.setStroke(dp(surface, BASE_GLOW_DP),
                    glowColor(now, false));
            glow.setAlpha(160);
        }
        surface.invalidate();
    }

    private static boolean isPressed(View surface) {
        return surface.isPressed();
    }

    private static int glowColor(long now, boolean pressed) {
        float hue = ((now % HUE_CYCLE_MS) / (float) HUE_CYCLE_MS) * 360f;
        float saturation = pressed ? 0.95f : 0.82f;
        float value = pressed ? 1f : 0.86f;
        float[] hsv = new float[] {hue, saturation, value};
        return Color.HSVToColor(pressed ? 255 : 168, hsv);
    }

    private static int dp(View view, float dp) {
        float density = view.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(dp * density));
    }
}
