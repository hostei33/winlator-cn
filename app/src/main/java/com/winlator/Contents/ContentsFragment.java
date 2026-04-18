package com.winlator.contents;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.content.res.TypedArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;      // 使用 AppCompat 版本支持深色模式
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.core.TarCompressorUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class ContentsFragment extends Fragment {
    private static final String[] FILE_TYPES = {"dxvk", "box64", "turnip", "virgl", "vkd3d", "wine"};
    private static final String WHP_EXTENSION = ".whp";
    private static final String TZSD_EXTENSION = ".tzst";
    private static final int CORNER_RADIUS_DP = 12;
    private static final int ELEVATION_DP = 4;

    private String baseFilesPath;
    private String currentStoragePath = "installed_components";
    private String currentInstallPath = "";

    private LinearLayout fileListContainer;
    private Spinner categorySpinner;
    private String currentCategory = FILE_TYPES[0];
    private String installCategory;
    private Uri selectedFileUri;

    private AlertDialog installProgressDialog;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    selectedFileUri = result.getData().getData();
                    validateSelectedFile();
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        baseFilesPath = requireContext().getFilesDir().getAbsolutePath();
        initDirectories();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle("组件管理");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissInstallProgressDialog();
    }

    private void initDirectories() {
        File imageFsDir = new File(requireContext().getFilesDir(), "imagefs");
        if (!imageFsDir.exists()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Files.createSymbolicLink(imageFsDir.toPath(), Paths.get("./rootfs"));
                    Log.d("Symlink", "符号链接创建成功");
                } catch (Exception e) {
                    Log.e("Symlink", "创建符号链接失败", e);
                }
            } else {
                Log.w("Symlink", "符号链接需要 Android 8+，跳过创建");
            }
        }

        File wineDir = new File(baseFilesPath, "rootfs/opt/installed-wine");
        if (!wineDir.exists() && wineDir.mkdirs()) {
            try {
                new ProcessBuilder("chmod", "-R", "771", wineDir.getAbsolutePath())
                        .start()
                        .waitFor();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorFromAttr(android.R.attr.colorBackground, Color.parseColor("#FAFAFA"), Color.parseColor("#121212")));
        int padding = dpToPx(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(requireContext());
        title.setText("选择附加类型");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dpToPx(12));
        root.addView(title);

        categorySpinner = new Spinner(requireContext());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, FILE_TYPES);
        categorySpinner.setAdapter(adapter);
        int cardBgColor = getColorFromAttr(android.R.attr.colorBackground, Color.parseColor("#FFFFFF"), Color.parseColor("#121212"));
        categorySpinner.setBackground(createRoundedBackground(cardBgColor, CORNER_RADIUS_DP));
        categorySpinner.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            categorySpinner.setElevation(dpToPx(ELEVATION_DP));
        }
        categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentCategory = FILE_TYPES[position];
                refreshFileList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(categorySpinner, createLayoutParams(16));

        // 滚动列表
        ScrollView scroll = new ScrollView(requireContext());
        fileListContainer = new LinearLayout(requireContext());
        fileListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(fileListContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 安装按钮
        Button installBtn = new Button(requireContext());
        installBtn.setText("选择安装文件");
        installBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        installBtn.setTextColor(Color.WHITE);
        installBtn.setTypeface(Typeface.DEFAULT_BOLD);
        installBtn.setAllCaps(false);
        int primaryColor = getColorFromAttr(android.R.attr.colorPrimary, Color.parseColor("#2196F3"), Color.parseColor("#2196F3"));
        installBtn.setBackground(createRoundedBackground(primaryColor, CORNER_RADIUS_DP));
        installBtn.setPadding(0, dpToPx(14), 0, dpToPx(14));
        installBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });
        root.addView(installBtn, createLayoutParams(24));

        refreshFileList();
        return root;
    }

    private int getColorFromAttr(int attr, int lightDefault, int darkDefault) {
        TypedArray ta = requireContext().getTheme().obtainStyledAttributes(new int[]{attr});
        int color = ta.getColor(0, 0);
        ta.recycle();
        if (color != 0) return color;

        int nightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES ? darkDefault : lightDefault;
    }

    private void validateSelectedFile() {
        if (selectedFileUri == null) {
            showToast("无效文件选择");
            return;
        }

        String fileName = extractFileName(selectedFileUri);
        if (fileName == null) {
            showToast("无效文件选择");
            return;
        }

        String detected = detectCategory(fileName);
        if (detected == null) {
            showToast("无法识别文件类别，请确保文件名包含 " + TextUtils.join(", ", FILE_TYPES));
            return;
        }
        if (!fileName.toLowerCase().endsWith(WHP_EXTENSION)) {
            showToast("请选择 .whp 文件");
            return;
        }

        installCategory = detected;
        showInstallDialog(fileName);
    }

    private String detectCategory(String fileName) {
        String lower = fileName.toLowerCase();
        for (String type : FILE_TYPES) {
            if (lower.contains(type.toLowerCase())) return type;
        }
        return null;
    }

    private void showInstallDialog(String fileName) {
        new AlertDialog.Builder(requireContext())
                .setTitle("安全提示")
                .setMessage("即将安装：" + fileName + "\n请确认文件来源可靠")
                .setPositiveButton("确认安装", (d, w) -> {
                    showInstallProgressDialog();
                    new Thread(() -> {
                        try {
                            performInstall(fileName);
                            requireActivity().runOnUiThread(() -> {
                                dismissInstallProgressDialog();
                                showToast("✔ 安装完成");
                                currentCategory = installCategory;
                                int pos = Arrays.asList(FILE_TYPES).indexOf(currentCategory);
                                if (pos >= 0) categorySpinner.setSelection(pos);
                                refreshFileList();
                            });
                        } catch (Exception e) {
                            requireActivity().runOnUiThread(() -> {
                                dismissInstallProgressDialog();
                                showToast("✘ 安装失败: " + e.getMessage());
                            });
                        }
                    }).start();
                })
                .setNegativeButton("取消操作", null)
                .show();
    }

    private void showInstallProgressDialog() {
        if (installProgressDialog != null && installProgressDialog.isShowing()) return;

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));
        layout.setGravity(Gravity.CENTER);

        ProgressBar progressBar = new ProgressBar(requireContext());
        progressBar.setIndeterminate(true);
        layout.addView(progressBar);

        TextView message = new TextView(requireContext());
        message.setText("正在安装，请稍候...");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        message.setPadding(0, dpToPx(16), 0, 0);
        layout.addView(message);

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setView(layout);
        builder.setCancelable(false);
        installProgressDialog = builder.create();
        installProgressDialog.show();
    }

    private void dismissInstallProgressDialog() {
        if (installProgressDialog != null && installProgressDialog.isShowing()) {
            installProgressDialog.dismiss();
            installProgressDialog = null;
        }
    }

    private void performInstall(String fileName) throws Exception {
        boolean isWine = installCategory.equalsIgnoreCase("Wine");
        String storagePath = isWine ? "rootfs/opt/installed-wine" : "installed_components";
        String installPath = isWine ? "" : installCategory;
        File targetDir = new File(baseFilesPath, storagePath + File.separator + installPath);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new Exception("目录创建失败");
        }

        String baseName = fileName.replaceAll("(?i)" + WHP_EXTENSION + "$", "");
        try (InputStream input = requireContext().getContentResolver().openInputStream(selectedFileUri)) {
            if (input == null) throw new Exception("无法读取文件");

            if (isWine) {
                TarCompressorUtils.Type type = detectCompressionType(selectedFileUri);
                if (type == null) throw new Exception("不支持的文件格式，请提供 XZ 或 ZSTD 压缩的 Wine 包");
                if (!TarCompressorUtils.extract(type, requireContext(), selectedFileUri, targetDir, null)) {
                    throw new Exception("解压失败");
                }
            } else {
                File outFile = new File(targetDir, baseName + TZSD_EXTENSION);
                try (OutputStream output = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = input.read(buffer)) != -1) output.write(buffer, 0, len);
                }
            }
        }
    }

    private TarCompressorUtils.Type detectCompressionType(Uri uri) throws IOException {
        byte[] magic = new byte[6];
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
            if (is == null || is.read(magic) < 4) return null;
            if ((magic[0] & 0xFF) == 0xFD && (magic[1] & 0xFF) == 0x37 &&
                (magic[2] & 0xFF) == 0x7A && (magic[3] & 0xFF) == 0x58 &&
                (magic[4] & 0xFF) == 0x5A && (magic[5] & 0xFF) == 0x00) {
                return TarCompressorUtils.Type.XZ;
            }
            if ((magic[0] & 0xFF) == 0x28 && (magic[1] & 0xFF) == 0xB5 &&
                (magic[2] & 0xFF) == 0x2F && (magic[3] & 0xFF) == 0xFD) {
                return TarCompressorUtils.Type.ZSTD;
            }
            return null;
        }
    }

    private void sanitizeWineFolderNames(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] folders = dir.listFiles(File::isDirectory);
        if (folders == null) return;

        for (File folder : folders) {
            String oldName = folder.getName();
            if (oldName.endsWith("-")) {
                String newName = oldName.substring(0, oldName.length() - 1);
                File newFolder = new File(folder.getParent(), newName);
                if (folder.renameTo(newFolder)) {
                    Log.d("WineFolder", "重命名文件夹: " + oldName + " -> " + newName);
                    String version = extractVersionFromName(oldName);
                    if (version != null) {
                        File oldPattern = new File(folder.getParent(), "container-pattern-" + version + ".tzst");
                        if (oldPattern.exists()) {
                            String newVersion = extractVersionFromName(newName);
                            if (newVersion != null) {
                                File newPattern = new File(folder.getParent(), "container-pattern-" + newVersion + ".tzst");
                                oldPattern.renameTo(newPattern);
                                Log.d("WineFolder", "重命名 pattern: " + oldPattern.getName() + " -> " + newPattern.getName());
                            }
                        }
                    }
                } else {
                    Log.e("WineFolder", "重命名失败: " + oldName);
                }
            }
        }
    }

    private String extractVersionFromName(String folderName) {
        int dash = folderName.indexOf('-');
        if (dash == -1) return null;
        String version = folderName.substring(dash + 1);
        if (version.endsWith("-")) version = version.substring(0, version.length() - 1);
        return version;
    }

    private void refreshFileList() {
        boolean isWine = currentCategory.equalsIgnoreCase("Wine");
        currentStoragePath = isWine ? "rootfs/opt/installed-wine" : "installed_components";
        currentInstallPath = isWine ? "" : currentCategory;

        fileListContainer.removeAllViews();
        File dir = new File(baseFilesPath, currentStoragePath + File.separator + currentInstallPath);
        if (!dir.exists() || !dir.isDirectory()) {
            showEmptyState();
            return;
        }

        if (isWine) {
            sanitizeWineFolderNames(dir);
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            showEmptyState();
            return;
        }

        boolean hasEntries = false;
        for (File f : files) {
            if (isWine) {
                if (f.isDirectory()) {
                    addFileEntry(f.getName());
                    hasEntries = true;
                }
            } else {
                String name = f.isFile() ? f.getName().replace(TZSD_EXTENSION, "") : f.getName();
                addFileEntry(name);
                hasEntries = true;
            }
        }
        if (!hasEntries) showEmptyState();
    }

    private void showEmptyState() {
        TextView empty = new TextView(requireContext());
        empty.setText("当前无已安装项目");
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dpToPx(32), 0, 0);
        fileListContainer.addView(empty);
    }

    private void addFileEntry(String name) {
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.HORIZONTAL);
        int cardBgColor = getColorFromAttr(android.R.attr.colorBackground, Color.parseColor("#FFFFFF"), Color.parseColor("#121212"));
        item.setBackground(createRoundedBackground(cardBgColor, CORNER_RADIUS_DP));
        item.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            item.setElevation(dpToPx(ELEVATION_DP / 2));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dpToPx(8));
        item.setLayoutParams(lp);

        TextView tv = new TextView(requireContext());
        tv.setText(name);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        item.addView(tv);

        item.setOnLongClickListener(v -> {
            showDeleteDialog(name);
            return true;
        });
        fileListContainer.addView(item);
    }

    private void showDeleteDialog(String name) {
        new AlertDialog.Builder(requireContext())
                .setTitle("确认")
                .setMessage("确定删除 " + name + " 吗？")
                .setPositiveButton("确定删除", (d, w) -> deleteFile(name))
                .setNegativeButton("取消操作", null)
                .show();
    }

    private void deleteFile(String name) {
        File target = new File(baseFilesPath, currentStoragePath + File.separator + currentInstallPath + File.separator + name);
        boolean success;
        if (currentCategory.equalsIgnoreCase("Wine")) {
            int dash = name.indexOf('-');
            if (dash == -1) {
                showToast("无法识别 Wine 版本");
                return;
            }
            String version = name.substring(dash + 1);
            if (version.endsWith("-")) version = version.substring(0, version.length() - 1);
            File pattern = new File(baseFilesPath, currentStoragePath + File.separator + currentInstallPath +
                    File.separator + "container-pattern-" + version + ".tzst");
            pattern.delete();
            success = deleteRecursive(target);
        } else {
            success = new File(target.getAbsolutePath() + TZSD_EXTENSION).delete();
        }
        if (success) {
            showToast("✔ 删除成功");
            refreshFileList();
        } else {
            showToast("✘ 删除失败");
        }
    }

    private boolean deleteRecursive(File file) {
        if (isSymbolicLink(file)) return file.delete();
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
    }

    private boolean isSymbolicLink(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return Files.isSymbolicLink(file.toPath());
        } else {
            try {
                return !file.getCanonicalPath().equals(file.getAbsolutePath());
            } catch (IOException e) {
                return false;
            }
        }
    }

    private GradientDrawable createRoundedBackground(int color, int radiusDP) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dpToPx(radiusDP));
        gd.setColor(color);
        return gd;
    }

    private LinearLayout.LayoutParams createLayoutParams(int marginDP) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dpToPx(marginDP), 0, dpToPx(marginDP));
        return lp;
    }

    private String extractFileName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    name = cursor.getString(idx);
                }
            }
        }
        if (name == null) {
            String path = uri.getPath();
            if (path != null) name = path.substring(path.lastIndexOf('/') + 1);
        }
        return name;
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                requireContext().getResources().getDisplayMetrics());
    }

    private void showToast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}