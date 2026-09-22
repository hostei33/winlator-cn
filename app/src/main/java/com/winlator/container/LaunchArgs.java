package com.winlator.container;

import android.content.Context;

import com.winlator.box64.Box64PresetManager;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LaunchPathResolver;
import com.winlator.xserver.ScreenInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

public class LaunchArgs {
    public static final String EXTRA_EXTERNAL_LAUNCH = "external_launch";
    public static final String EXTRA_LAUNCH_OVERRIDES = "launch_overrides";
    public static final String EXTRA_LAUNCH_ID = "launch_id";

    public static final Set<String> SESSION_ONLY_KEYS = new HashSet<>(Arrays.asList(
        "execArgs", "forceFullscreen", "toggleFullscreen"
    ));

    private static final Set<String> WHITELIST = new HashSet<>(Arrays.asList(
        "screenSize", "screenOrientation", "swapResolution",
        "graphicsDriver", "graphicsDriverConfig",
        "dxwrapper", "dxwrapperConfig",
        "audioDriver", "audioDriverConfig",
        "wincomponents", "envVars", "execArgs", "lcAll", "tz",
        "box64Version", "box64Preset", "drives",
        "controlsProfile", "dinputMapperType",
        "forceFullscreen", "toggleFullscreen"
    ));

    private final JSONObject overrides;
    private final Shortcut shortcut;
    private final boolean hasExecPath;

    public LaunchArgs(JSONObject overrides, Shortcut shortcut, boolean hasExecPath) {
        this.overrides = overrides;
        this.shortcut = shortcut;
        this.hasExecPath = hasExecPath;
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        if (overrides != null && overrides.has(name)) return overrides.optString(name, fallback);
        if (shortcut != null) return shortcut.getExtra(name, fallback);
        return fallback;
    }

    public String getOverride(String name, String fallback) {
        return overrides != null && overrides.has(name) ? overrides.optString(name, fallback) : fallback;
    }

    public boolean hasExecutable() {
        return shortcut != null || hasExecPath;
    }

    public static class ValidationResult {
        public final JSONObject overrides = new JSONObject();
        public final ArrayList<String> warnings = new ArrayList<>();
        public final ArrayList<String> errors = new ArrayList<>();

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    public static ValidationResult validate(Context context, String json) {
        ValidationResult result = new ValidationResult();
        if (json == null || json.trim().isEmpty()) return result;

        JSONObject input;
        try {
            input = new JSONObject(json);
        }
        catch (JSONException e) {
            result.errors.add("overrides");
            return result;
        }

        Iterator<String> keys = input.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!WHITELIST.contains(key)) {
                result.warnings.add(key);
                continue;
            }

            String value;
            try {
                value = normalize(context, key, input.optString(key, ""));
            }
            catch (Exception e) {
                value = null;
            }

            if (value == null) {
                result.errors.add(key);
            }
            else {
                try {
                    result.overrides.put(key, value);
                }
                catch (JSONException e) {
                    result.errors.add(key);
                }
            }
        }
        return result;
    }

    private static String normalize(Context context, String key, String value) {
        switch (key) {
            case "screenSize": {
                ScreenInfo screenInfo = new ScreenInfo(value);
                return screenInfo.width >= ScreenInfo.MIN_WIDTH && screenInfo.height >= ScreenInfo.MIN_HEIGHT ? value : null;
            }
            case "screenOrientation":
                return value.equals("landscape") || value.equals("portrait") ? value : null;
            case "swapResolution":
                return bool(value, "true", "false");
            case "graphicsDriver": {
                if (value.isEmpty()) return null;
                for (String identifier : value.split(",")) {
                    if (!GraphicsDrivers.isVulkanDriver(identifier) && !GraphicsDrivers.isOpenGLDriver(identifier)) return null;
                }
                return value;
            }
            case "graphicsDriverConfig":
            case "dxwrapperConfig":
            case "audioDriverConfig":
                return value;
            case "dxwrapper":
                return DXWrappers.parseIdentifier(value).equals(value) ? value : null;
            case "audioDriver":
                return value.equals(AudioDrivers.ALSA) || value.equals(AudioDrivers.PULSEAUDIO) ? value : null;
            case "envVars":
                if (value.isEmpty()) return value;
                for (String token : value.split(" ")) {
                    if (token.isEmpty() || token.indexOf('=') <= 0) return null;
                }
                return value;
            case "lcAll":
                return value.matches("^[A-Za-z][A-Za-z0-9_.@-]{0,63}$") ? value : null;
            case "tz":
                return value.length() <= 64 && value.matches("^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,3}$") ? value : null;
            case "wincomponents":
                if (value.isEmpty()) return value;
                for (String token : value.split(",")) {
                    if (token.isEmpty() || token.indexOf('=') <= 0) return null;
                }
                return value;
            case "drives": {
                if (value.isEmpty()) return null;
                Set<String> letters = new HashSet<>();
                int count = 0;
                for (Drive drive : Container.drivesIterator(value)) {
                    String letter = drive.letter.toUpperCase(Locale.ENGLISH);
                    if (LaunchPathResolver.isReservedDriveLetter(letter)) return null;
                    if (drive.path.isEmpty() || !drive.path.startsWith("/") || drive.path.contains("..")) return null;
                    if (!letters.add(letter)) return null;
                    count++;
                }
                return count > 0 && count <= Container.MAX_DRIVE_LETTERS ? value : null;
            }
            case "box64Version":
                return GeneralComponents.isBuiltinComponent(GeneralComponents.Type.BOX64, value) ||
                       GeneralComponents.getInstalledComponentNames(GeneralComponents.Type.BOX64, context).contains(value) ? value : null;
            case "box64Preset":
                return Box64PresetManager.getPreset(context, value) != null ? value : null;
            case "controlsProfile":
                try {
                    return String.valueOf(Integer.parseInt(value));
                }
                catch (NumberFormatException e) {
                    return null;
                }
            case "dinputMapperType":
                return bool(value, "1", "0");
            case "forceFullscreen":
            case "toggleFullscreen":
                return bool(value, "1", "0");
            case "execArgs":
                return value;
        }
        return null;
    }

    private static String bool(String value, String trueValue, String falseValue) {
        if (value.equals("true") || value.equals("1")) return trueValue;
        if (value.equals("false") || value.equals("0")) return falseValue;
        return null;
    }
}
