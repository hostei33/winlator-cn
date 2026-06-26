package com.winlator.widget;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.EnvVars;
import com.winlator.core.UnitUtils;

import java.util.Arrays;

public class EnvVarsView extends FrameLayout {
    public static final String[][] knownEnvVars = {
        {"LC_ALL", "SELECT", "zh_CN.utf8", "zh_TW.utf8", "en_US.utf8", "ja_JP.utf8", "sr_RS.utf8", "ru_RU.utf8"},
        {"TZ", "SELECT", "Asia/Shanghai", "Asia/Tokyo", "Europe/Berlin", "America/New_York"},
        {"MESA_GL_VERSION_OVERRIDE", "SELECT", "4.6COMPAT", "3.3COMPAT", "4.5", "4.6", "3.3", "3.2", "2.1", "auto"},
        {"ZINK_DESCRIPTORS", "SELECT", "auto", "lazy", "cached", "notemplates"},
        {"ZINK_DEBUG", "SELECT_MULTIPLE", "nir", "spirv", "tgsi", "validation", "sync", "compact", "noreorder"},
        {"ZINK_CONTEXT_THREADED", "CHECKBOX", "0", "1"},
        {"WINEESYNC", "CHECKBOX", "0", "1"},
        {"WINEDLLOVERRIDES", "TEXT"},
        {"TU_DEBUG", "SELECT_MULTIPLE", "startup", "nir", "nofsdt", "nobin", "sysmem", "gmem", "forcebin", "layout", "noubwc", "nomultipos", "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw", "push_consts_per_stage", "rast_order", "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "noconform", "rd"},
        {"DXVK_HUD", "SELECT_MULTIPLE", "devinfo", "fps", "frametimes", "submissions", "drawcalls", "pipelines", "descriptors", "memory", "gpuload", "version", "api", "cs", "compiler", "samplers"},
        {"DXVK_LOG_LEVEL", "SELECT", "none", "error", "warn", "info", "debug"},
        {"GST_DEBUG", "SELECT", "0", "1", "2", "3", "4", "5"},
        {"DXVK_ASYNC", "CHECKBOX", "0", "1"},
        {"GALLIUM_HUD", "TEXT"},
        {"MESA_SHADER_CACHE_DISABLE", "CHECKBOX", "false", "true"},
        {"mesa_glthread", "CHECKBOX", "false", "true"},
        {"MESA_SHADER_CACHE_MAX_SIZE", "SELECT", "512MB", "1024MB", "2048MB", "4096MB"},
        {"MESA_VK_WSI_DEBUG", "SELECT", "-sw", "sw"},
        {"LIBGL_ALWAYS_SOFTWARE", "CHECKBOX", "0", "1"},
        {"BOX64_MMAP32", "CHECKBOX", "0", "1"},
        {"DRAW_USE_LLVM", "CHECKBOX", "0", "1"},
        {"MANGOHUD", "CHECKBOX", "0", "1"},
        {"MANGOHUD_CONFIGFILE", "SELECT", "/data/user/0/com.winlator/files/rootfs/home/mangohud1.conf", "/data/user/0/com.winlator/files/rootfs/home/mangohud2.conf", "/data/user/0/com.winlator/files/rootfs/home/mangohud3.conf", "/data/user/0/com.winlator/files/rootfs/home/mangohud4.conf"},
        {"MESA_EXTENSION_MAX_YEAR", "SELECT", "2001", "2003", "2008", "2015", "2025"},
        {"PULSE_LATENCY_MSEC", "NUMBER"},
    };
    private final LinearLayout container;
    private final TextView emptyTextView;
    private final LayoutInflater inflater;

    private interface GetValueCallback {
        String call();
    }

    public EnvVarsView(Context context) {
        this(context, null);
    }

    public EnvVarsView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EnvVarsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public EnvVarsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        inflater = LayoutInflater.from(context);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(container);

        emptyTextView = new TextView(context);
        emptyTextView.setText(R.string.no_items_to_display);
        emptyTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyTextView.setGravity(Gravity.CENTER);
        int padding = (int)UnitUtils.dpToPx(16);
        emptyTextView.setPadding(padding, padding, padding, padding);
        addView(emptyTextView);
    }

    private String[] findKnownEnvVar(String name) {
        for (String[] values : knownEnvVars) if (values[0].equals(name)) return values;
        return null;
    }

    public String getEnvVars() {
        EnvVars envVars = new EnvVars();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            GetValueCallback getValueCallback = (GetValueCallback)child.getTag();
            String name = ((TextView)child.findViewById(R.id.TextView)).getText().toString();
            String value = getValueCallback.call().trim().replace(" ", "");
            if (!value.isEmpty()) envVars.put(name, value);
        }
        return envVars.toString();
    }

    public boolean containsName(String name) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            String text = ((TextView)child.findViewById(R.id.TextView)).getText().toString();
            if (name.equals(text)) return true;
        }
        return false;
    }

    public void add(String name, String value) {
        final Context context = getContext();
        final View itemView = inflater.inflate(R.layout.env_vars_list_item, container, false);
        ((TextView)itemView.findViewById(R.id.TextView)).setText(name);

        String[] knownEnvVar = findKnownEnvVar(name);
        GetValueCallback getValueCallback;

        String type = knownEnvVar != null ? knownEnvVar[1] : "TEXT";

        switch (type) {
            case "CHECKBOX":
                final ToggleButton toggleButton = itemView.findViewById(R.id.ToggleButton);
                toggleButton.setVisibility(VISIBLE);
                toggleButton.setChecked(value.equals("1") || value.equals("true"));
                getValueCallback = () -> toggleButton.isChecked() ? knownEnvVar[3] : knownEnvVar[2];
                break;
            case "SELECT":
                String[] items = Arrays.copyOfRange(knownEnvVar, 2, knownEnvVar.length);
                final Spinner spinner = itemView.findViewById(R.id.Spinner);
                spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items));
                AppUtils.setSpinnerSelectionFromValue(spinner, value);
                spinner.setVisibility(VISIBLE);
                getValueCallback = () -> spinner.getSelectedItem().toString();
                break;
            case "SELECT_MULTIPLE":
                final MultiSelectionComboBox comboBox = itemView.findViewById(R.id.MultiSelectionComboBox);
                comboBox.setItems(Arrays.copyOfRange(knownEnvVar, 2, knownEnvVar.length));
                comboBox.setSelectedItems(value.split(","));
                comboBox.setVisibility(VISIBLE);
                getValueCallback = comboBox::getSelectedItemsAsString;
                break;
            case "TEXT":
            case "NUMBER":
            default:
                final EditText editText = itemView.findViewById(R.id.EditText);
                editText.setVisibility(VISIBLE);
                editText.setText(value);
                if (type.equals("NUMBER")) editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                getValueCallback = () -> editText.getText().toString();
                break;
        }

        itemView.setTag(getValueCallback);
        itemView.findViewById(R.id.BTRemove).setOnClickListener((v) -> {
            container.removeView(itemView);
            if (container.getChildCount() == 0) emptyTextView.setVisibility(View.VISIBLE);
        });
        container.addView(itemView);
        emptyTextView.setVisibility(View.GONE);
    }

    public void setEnvVars(EnvVars envVars) {
        container.removeAllViews();
        for (String name : envVars) add(name, envVars.get(name));
    }
}
