package com.winlator.core;

import android.content.Context;

import com.winlator.R;

public final class ScreenSize {
    public static final String NATIVE = "native";

    private ScreenSize() {}

    public static String normalize(Context context, String value) {
        if (value.equalsIgnoreCase(NATIVE)) return NATIVE;
        if (value.matches("[0-9]+x[0-9]+")) return value;
        return LocaleHelper.matchesSupportedLocaleString(context, R.string.native_resolution, value) ? NATIVE : value;
    }
}
