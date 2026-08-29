package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.StorageInfoDialog;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.AppUtils;
import com.winlator.core.DownloadProgressDialog;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.UnitUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.xenvironment.RootFS;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

public class ContainersFragment extends Fragment {
    private static final String WHP_EXTENSION = ".whp";
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private DownloadProgressDialog progressDialog;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    ArrayList<String> filePaths = result.getData().getStringArrayListExtra(ImageFilePickerActivity.EXTRA_SELECTED_FILES);
                    if (filePaths != null && !filePaths.isEmpty()) {
                        File file = new File(filePaths.get(0));
                        if (file.isFile()) confirmRestoreContainer(file);
                    }
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        progressDialog = new DownloadProgressDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadContainersList();
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.containers);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.containers_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        Context context = recyclerView.getContext();
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        DividerItemDecoration itemDecoration = new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL);
        itemDecoration.setDrawable(ContextCompat.getDrawable(context, R.drawable.list_item_divider));
        recyclerView.addItemDecoration(itemDecoration);
        return frameLayout;
    }

    private void loadContainersList() {
        ArrayList<Container> containers = manager.getContainers();
        recyclerView.setAdapter(new ContainersAdapter(containers));
        if (containers.isEmpty()) emptyTextView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.containers_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.menu_item_add) {
            if (!RootFS.find(getContext()).isValid()) return false;
            FragmentManager fragmentManager = getParentFragmentManager();
            fragmentManager.beginTransaction()
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                .commit();
            return true;
        }
        else if (menuItem.getItemId() == R.id.icon_action_bar_re) {
            if (!RootFS.find(getContext()).isValid()) return false;
            Intent intent = new Intent(getContext(), ImageFilePickerActivity.class);
            intent.putExtra(ImageFilePickerActivity.EXTRA_MODE, ImageFilePickerActivity.MODE_FILE);
            intent.putExtra(ImageFilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
            intent.putExtra(ImageFilePickerActivity.EXTRA_FILE_FILTER, "whp");
            filePickerLauncher.launch(intent);
            return true;
        }
        else return super.onOptionsItemSelected(menuItem);
    }

    private static final String PROFILES_BACKUP_DIR = ".winlator-profiles";
    private static final String PROFILES_BACKUP_MAP = "profiles-map.txt";
    private static final String COMPONENTS_BACKUP_DIR = ".winlator-components";
    private static final String COMPONENTS_BACKUP_MAP = "components-map.txt";

    // 备份前：把容器/快捷方式绑定的虚拟按键配置复制进容器内目录，并生成易读的映射记录
    private void stageProfilesIntoContainer(Container container) {
        Context context = getContext();
        if (context == null) return;
        File backupDir = new File(container.getRootDir(), PROFILES_BACKUP_DIR);
        File mapFile = new File(backupDir, PROFILES_BACKUP_MAP);
        FileUtils.delete(backupDir);
        if (!backupDir.mkdirs()) return;

        File profilesDir = new File(context.getFilesDir(), "profiles");
        StringBuilder map = new StringBuilder();
        map.append("# 虚拟按键配置备份映射\n");
        map.append("# 格式: profileId=备份文件名;引用者1,引用者2,...\n");
        map.append("# 引用者为容器内相对路径：容器自身用 [container]，快捷方式用 Desktop/xxx.desktop\n");
        map.append("# 恢复时据此把容器/快捷方式中的引用改写到恢复后的 profileId\n\n");

        boolean bound = false;
        java.util.HashSet<String> stagedIds = new java.util.HashSet<>();
        java.util.HashMap<String, StringBuilder> refsByProfile = new java.util.HashMap<>();

        String containerProfile = container.getExtra("controlsProfile");
        if (!containerProfile.isEmpty() && stagedIds.add(containerProfile)) {
            File src = new File(profilesDir, "controls-"+containerProfile+".icp");
            if (src.isFile() && FileUtils.copy(src, new File(backupDir, src.getName()))) {
                refsByProfile.put(containerProfile, new StringBuilder("[container]"));
                bound = true;
            }
        }

        // 快捷方式主目录：rootDir/.wine/drive_c/users/xuser/Desktop
        File shortcutsDir = new File(container.getUserDir(), "Desktop");
        File[] shortcutFiles = shortcutsDir.listFiles();
        if (shortcutFiles != null) {
            for (File shortcutFile : shortcutFiles) {
                if (!shortcutFile.isFile() || !shortcutFile.getName().endsWith(".desktop")) continue;
                String profileId = getShortcutExtra(shortcutFile, "controlsProfile");
                if (profileId == null || profileId.isEmpty()) continue;
                if (stagedIds.add(profileId)) {
                    File src = new File(profilesDir, "controls-"+profileId+".icp");
                    if (src.isFile() && FileUtils.copy(src, new File(backupDir, src.getName()))) {
                        refsByProfile.put(profileId, new StringBuilder());
                        bound = true;
                    }
                }
                StringBuilder refs = refsByProfile.get(profileId);
                if (refs != null) {
                    if (refs.length() > 0) refs.append(',');
                    refs.append("Desktop/").append(shortcutFile.getName());
                }
            }
        }

        if (bound) {
            for (java.util.Map.Entry<String, StringBuilder> entry : refsByProfile.entrySet()) {
                map.append(entry.getKey()).append('=').append("controls-").append(entry.getKey()).append(".icp;")
                   .append(entry.getValue()).append('\n');
            }
            FileUtils.writeString(mapFile, map.toString());
        }
        else FileUtils.delete(backupDir);
    }

    // 导出前:把容器引用的非内置组件(Box64/DXVK/VKD3D/Turnip)复制进容器内目录,并生成映射记录
    private void stageComponentsIntoContainer(Container container) {
        Context context = getContext();
        if (context == null) return;
        File backupDir = new File(container.getRootDir(), COMPONENTS_BACKUP_DIR);
        File mapFile = new File(backupDir, COMPONENTS_BACKUP_MAP);
        FileUtils.delete(backupDir);
        if (!backupDir.mkdirs()) return;

        StringBuilder map = new StringBuilder();
        map.append("# 非内置组件备份映射\n");
        map.append("# 格式: type=identifier\n");
        map.append("# 恢复时把组件放回 installed_components/<type>/ 目录(tzst 文件或目录)\n\n");

        java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> components = new java.util.LinkedHashMap<>();

        // Box64:容器配置 + 快捷方式覆盖
        addComponentRef(components, "box64", container.getBox64Version());

        // DXVK / VKD3D:dxwrapperConfig 的 KeyValueSet
        addComponentRefsFromDxwrapper(components, container.getDXWrapper(), container.getDXWrapperConfig());

        // Turnip:graphicsDriverConfig 的 KeyValueSet
        addComponentRefsFromGraphicsDriver(components, container.getGraphicsDriver(), container.getGraphicsDriverConfig());

        // Vortek:非内置 Adrenotools 驱动(目录组件)
        addComponentRefsFromVortek(components, container.getGraphicsDriver(), container.getGraphicsDriverConfig());

        // 快捷方式可能覆盖容器配置(.desktop 文件 [Extra Data] 段)
        File shortcutsDir = new File(container.getUserDir(), "Desktop");
        File[] shortcutFiles = shortcutsDir.listFiles();
        if (shortcutFiles != null) {
            for (File shortcutFile : shortcutFiles) {
                if (!shortcutFile.isFile() || !shortcutFile.getName().endsWith(".desktop")) continue;

                String box64Version = getShortcutExtra(shortcutFile, "box64Version");
                if (box64Version != null && !box64Version.isEmpty()) addComponentRef(components, "box64", box64Version);

                String dxwrapper = getShortcutExtra(shortcutFile, "dxwrapper");
                String dxwrapperConfig = getShortcutExtra(shortcutFile, "dxwrapperConfig");
                if (dxwrapper != null || dxwrapperConfig != null) {
                    addComponentRefsFromDxwrapper(components,
                        dxwrapper != null && !dxwrapper.isEmpty() ? dxwrapper : container.getDXWrapper(),
                        dxwrapperConfig != null && !dxwrapperConfig.isEmpty() ? dxwrapperConfig : container.getDXWrapperConfig());
                }

                String graphicsDriver = getShortcutExtra(shortcutFile, "graphicsDriver");
                String graphicsDriverConfig = getShortcutExtra(shortcutFile, "graphicsDriverConfig");
                if (graphicsDriver != null || graphicsDriverConfig != null) {
                    String effectiveGraphicsDriver = graphicsDriver != null && !graphicsDriver.isEmpty() ? graphicsDriver : container.getGraphicsDriver();
                    String effectiveGraphicsDriverConfig = graphicsDriverConfig != null && !graphicsDriverConfig.isEmpty() ? graphicsDriverConfig : container.getGraphicsDriverConfig();
                    addComponentRefsFromGraphicsDriver(components, effectiveGraphicsDriver, effectiveGraphicsDriverConfig);
                    addComponentRefsFromVortek(components, effectiveGraphicsDriver, effectiveGraphicsDriverConfig);
                }
            }
        }

        boolean bound = false;
        for (java.util.Map.Entry<String, java.util.LinkedHashSet<String>> entry : components.entrySet()) {
            String type = entry.getKey();
            GeneralComponents.Type componentType = GeneralComponents.Type.valueOf(type.toUpperCase(java.util.Locale.ENGLISH));

            for (String identifier : entry.getValue()) {
                if (componentType == GeneralComponents.Type.ADRENOTOOLS_DRIVER) {
                    // 目录组件:整体复制目录
                    File src = new File(GeneralComponents.getComponentDir(componentType, context), identifier);
                    if (src.isDirectory() && FileUtils.copy(src, new File(backupDir, src.getName()))) {
                        map.append(type).append('=').append(identifier).append('\n');
                        bound = true;
                    }
                }
                else {
                    File src = new File(GeneralComponents.getComponentDir(componentType, context), type+"-"+identifier+".tzst");
                    if (src.isFile() && FileUtils.copy(src, new File(backupDir, src.getName()))) {
                        map.append(type).append('=').append(identifier).append('\n');
                        bound = true;
                    }
                }
            }
        }

        if (bound) FileUtils.writeString(mapFile, map.toString());
        else FileUtils.delete(backupDir);
    }

    private void addComponentRef(java.util.Map<String, java.util.LinkedHashSet<String>> components, String type, String identifier) {
        if (identifier == null || identifier.isEmpty()) return;
        if (GeneralComponents.isBuiltinComponent(GeneralComponents.Type.valueOf(type.toUpperCase(java.util.Locale.ENGLISH)), identifier)) return;
        java.util.LinkedHashSet<String> identifiers = components.get(type);
        if (identifiers == null) {
            identifiers = new java.util.LinkedHashSet<>();
            components.put(type, identifiers);
        }
        identifiers.add(identifier);
    }

    // 从 dxwrapperConfig 提取 DXVK/WineD3D/VKD3D 组件引用
    private void addComponentRefsFromDxwrapper(java.util.Map<String, java.util.LinkedHashSet<String>> components, String dxwrapper, String dxwrapperConfig) {
        if (dxwrapperConfig == null || dxwrapperConfig.isEmpty()) return;
        KeyValueSet[] configs = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
        if (configs.length > 0) {
            // configs[0] 是 WINED3D/DXVK 共用段，按 dxwrapper 类型记入对应组件
            String version = configs[0].get("version");
            if (DXWrappers.WINED3D.equals(dxwrapper)) addComponentRef(components, "wined3d", version);
            else addComponentRef(components, "dxvk", version);
        }
        if (configs.length > 1) addComponentRef(components, "vkd3d", configs[1].get("version"));
    }

    // 从 graphicsDriverConfig 提取 Turnip 组件引用(仅当 Vulkan 驱动为 turnip 时)
    private void addComponentRefsFromGraphicsDriver(java.util.Map<String, java.util.LinkedHashSet<String>> components, String graphicsDriver, String graphicsDriverConfig) {
        if (graphicsDriverConfig == null || graphicsDriverConfig.isEmpty()) return;
        String[] identifiers = GraphicsDrivers.parseIdentifiers(graphicsDriver);
        if (identifiers.length == 0 || !identifiers[0].equals(GraphicsDrivers.TURNIP)) return;
        KeyValueSet[] configs = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
        if (configs.length > 0) addComponentRef(components, "turnip", configs[0].get("version"));
    }

    // 从 graphicsDriverConfig 提取非内置 Adrenotools 驱动(vortek 使用)
    private void addComponentRefsFromVortek(java.util.Map<String, java.util.LinkedHashSet<String>> components, String graphicsDriver, String graphicsDriverConfig) {
        if (graphicsDriverConfig == null || graphicsDriverConfig.isEmpty()) return;
        KeyValueSet[] configs = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
        if (configs.length == 0) return;
        String adrenotoolsDriver = configs[0].get("adrenotoolsDriver");
        if (adrenotoolsDriver != null && !adrenotoolsDriver.isEmpty() && !adrenotoolsDriver.equals("System")) {
            addComponentRef(components, "adrenotools_driver", adrenotoolsDriver);
        }
    }

    // 解析 .desktop 文件 [Extra Data] 段的字段值
    private String getShortcutExtra(File shortcutFile, String key) {
        if (!shortcutFile.isFile() || !shortcutFile.getName().endsWith(".desktop")) return null;
        try {
            String section = "";
            for (String line : FileUtils.readLines(shortcutFile, true)) {
                if (line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    int end = line.indexOf("]");
                    if (end == -1) continue;
                    section = line.substring(1, end);
                }
                else if (section.equals("Extra Data")) {
                    int index = line.indexOf("=");
                    if (index == -1) continue;
                    String k = line.substring(0, index).trim();
                    if (k.equals(key)) {
                        return line.substring(index+1).trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // 恢复后:把容器内备份的非内置组件放回 installed_components 目录
    private void restoreComponents(File containerDir) {
        File backupDir = new File(containerDir, COMPONENTS_BACKUP_DIR);
        File mapFile = new File(backupDir, COMPONENTS_BACKUP_MAP);
        if (!mapFile.isFile()) return;

        Context context = getContext();
        if (context == null) return;

        for (String line : FileUtils.readLines(mapFile, true)) {
            if (line.startsWith("#") || !line.contains("=")) continue;
            String type = line.substring(0, line.indexOf("=")).trim();
            String identifier = line.substring(line.indexOf("=")+1).trim();
            if (type.isEmpty() || identifier.isEmpty() || identifier.contains("/") || identifier.contains("..")) continue;

            try {
                GeneralComponents.Type componentType = GeneralComponents.Type.valueOf(type.toUpperCase(java.util.Locale.ENGLISH));
                File componentDir = GeneralComponents.getComponentDir(componentType, context);

                if (componentType == GeneralComponents.Type.ADRENOTOOLS_DRIVER) {
                    // 目录组件:整体复制目录回 installed_components
                    File src = new File(backupDir, identifier);
                    File dst = new File(componentDir, identifier);
                    if (dst.isDirectory()) FileUtils.delete(dst);
                    if (src.isDirectory() && FileUtils.copy(src, dst)) {
                        Log.d("RestoreComponents", "还原组件目录: "+type+"-"+identifier);
                    }
                }
                else {
                    File src = new File(backupDir, type+"-"+identifier+".tzst");
                    File dst = new File(componentDir, src.getName());
                    if (src.isFile() && FileUtils.copy(src, dst)) {
                        Log.d("RestoreComponents", "还原组件: "+type+"-"+identifier);
                    }
                }
            }
            catch (Exception e) {
                Log.e("RestoreComponents", "还原组件失败: "+type+"-"+identifier, e);
            }
        }

        FileUtils.delete(backupDir);
    }

    private void backupContainer(Container container) {
        final ContentDialog dialog = new ContentDialog(getContext(), R.layout.export_container_dialog);
        dialog.setCancelable(true);

        final EditText etName = dialog.findViewById(R.id.ETName);
        etName.setText(container.getName());
        final com.winlator.widget.SeekBar sbLevel = dialog.findViewById(R.id.SBCompressionLevel);
        sbLevel.setValue(MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);
        final EditText etAuthor = dialog.findViewById(R.id.ETAuthor);
        final EditText etMobileNote = dialog.findViewById(R.id.ETMobileNote);

        // 模拟器版本(只读):自动读取当前应用版本
        final TextView tvEmulatorVersion = dialog.findViewById(R.id.TVEmulatorVersion);
        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            tvEmulatorVersion.setText(pInfo.versionName + " (" + pInfo.versionCode + ")");
        }
        catch (PackageManager.NameNotFoundException e) {
            tvEmulatorVersion.setText(R.string.unknown);
        }

        dialog.findViewById(R.id.BTCancel).setOnClickListener((v) -> dialog.dismiss());
        dialog.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) name = container.getName();
            // 备份时修改名称:同步更新源容器 .container 内的容器名
            if (!name.equals(container.getName())) {
                renameContainerName(container, name);
                container.setName(name);
                loadContainersList();
            }
            int level = (int)sbLevel.getValue();
            String author = etAuthor.getText().toString().trim();
            String mobileNote = etMobileNote.getText().toString().trim();
            dialog.dismiss();
            exportContainer(container, name, level, author, mobileNote);
        });

        // 横屏矮屏下限制内容区最大高度,保证窗口整体在屏幕内、底部按钮可见,内容超出可滚动
        View svExport = dialog.findViewById(R.id.SVExport);
        if (svExport != null) {
            int maxHeight = (int)(AppUtils.getScreenHeight() * 0.5f);
            if (maxHeight < (int)UnitUtils.dpToPx(300)) {
                svExport.getLayoutParams().height = maxHeight;
            }
        }
        dialog.show();
    }

    private void exportContainer(Container container, String name, int level, String author, String mobileNote) {
        // 固定文件名:容器名-v版本号.whp(不再使用时间戳)
        String safeName = name.replaceAll("[\\\\/:*?\"<>|]", "_").replace("..", "_");
        final String versionName;
        String versionNameTmp = "";
        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            versionNameTmp = pInfo.versionName;
        }
        catch (PackageManager.NameNotFoundException ignored) {}
        versionName = versionNameTmp;
        final File destFile = new File(AppUtils.DIRECTORY_DOWNLOADS, safeName+"-v"+versionName+WHP_EXTENSION);

        // 同名文件存在时提醒用户:确认则覆盖固定文件名,取消则回退为时间戳文件名继续导出
        if (destFile.isFile()) {
            ContentDialog dialog = new ContentDialog(getContext());
            dialog.setCancelable(false);
            dialog.setMessage(getContext().getString(R.string.backup_file_exists, destFile.getName()), R.drawable.content_dialog_type_confirm);
            dialog.setOnConfirmCallback(() -> {
                progressDialog.show(R.string.backing_up_container);
                doExportContainer(container, name, level, author, mobileNote, destFile);
            });
            dialog.setOnCancelCallback(() -> {
                String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                File timestampFile = new File(AppUtils.DIRECTORY_DOWNLOADS, safeName+"-"+timestamp+"-backup"+WHP_EXTENSION);
                progressDialog.show(R.string.backing_up_container);
                doExportContainer(container, name, level, author, mobileNote, timestampFile);
            });
            dialog.show();
            return;
        }
        progressDialog.show(R.string.backing_up_container);
        doExportContainer(container, name, level, author, mobileNote, destFile);
    }

    private void doExportContainer(Container container, String name, int level, String author, String mobileNote, File destFile) {
        Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            stageProfilesIntoContainer(container);
            stageComponentsIntoContainer(container);
            final long[] lastUpdate = {0};
            // skippable frame 元数据:解码器自动跳过,不影响解压;携带导出描述信息(压缩等级不写入元数据)
            byte[] meta = null;
            try {
                JSONObject metaObj = new JSONObject();
                metaObj.put("name", name);
                if (!author.isEmpty()) metaObj.put("author", author);
                if (!mobileNote.isEmpty()) metaObj.put("mobileNote", mobileNote);
                PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
                metaObj.put("emulatorVersion", pInfo.versionName);
                meta = metaObj.toString().getBytes("UTF-8");
            }
            catch (Exception e) {
                Log.e("ExportContainer", "构建导出元数据失败", e);
            }
            TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, container.getRootDir(), destFile, level, (done, total) -> {
                if (total <= 0) return;
                int percent = (int)(done * 100 / total);
                if (percent - lastUpdate[0] >= 1 || percent == 100) {
                    lastUpdate[0] = percent;
                    final int p = percent;
                    handler.post(() -> progressDialog.setProgress(p));
                }
            }, meta);
            handler.post(() -> {
                progressDialog.close();
                FileUtils.delete(new File(container.getRootDir(), PROFILES_BACKUP_DIR));
                FileUtils.delete(new File(container.getRootDir(), COMPONENTS_BACKUP_DIR));
                if (destFile.isFile()) {
                    AppUtils.showToast(getContext(), getContext().getString(R.string.backup_saved_to)+" "+destFile.getPath());
                } else {
                    AppUtils.showToast(getContext(), R.string.unable_to_backup_container);
                }
            });
        });
    }

    // 恢复前确认对话框:显示备份包信息,用户确认后才恢复
    private void confirmRestoreContainer(File file) {
        Context context = getContext();
        if (context == null) return;

        JSONObject meta = parseBackupMeta(file);
        String message;
        if (meta != null) {
            StringBuilder sb = new StringBuilder();
            String metaName = meta.optString("name", "");
            if (!metaName.isEmpty()) sb.append(context.getString(R.string.meta_name, metaName)).append('\n');
            String metaAuthor = meta.optString("author", "");
            if (!metaAuthor.isEmpty()) sb.append(context.getString(R.string.meta_author, metaAuthor)).append('\n');
            String metaVersion = meta.optString("emulatorVersion", "");
            if (!metaVersion.isEmpty()) sb.append(context.getString(R.string.meta_emulator_version, metaVersion)).append('\n');
            String metaMobileNote = meta.optString("mobileNote", "");
            if (!metaMobileNote.isEmpty()) sb.append(context.getString(R.string.meta_mobile_note, metaMobileNote)).append('\n');
            message = sb.toString();
            if (message.isEmpty()) message = context.getString(R.string.confirm_restore_container);
        }
        else {
            // 旧版备份包无元数据,仅提示文件名
            message = context.getString(R.string.confirm_restore_container_file, file.getName());
        }

        final String confirmMessage = message;
        ContentDialog dialog = new ContentDialog(context);
        dialog.setCancelable(false);
        dialog.setMessage(confirmMessage, R.drawable.content_dialog_type_confirm);
        dialog.setOnConfirmCallback(() -> restoreContainer(file));
        dialog.show();
    }

    // 读取备份包头部的 skippable frame 元数据(JSON),无元数据或解析失败返回 null
    private JSONObject parseBackupMeta(File file) {
        byte[] meta = TarCompressorUtils.readMeta(file);
        if (meta == null) return null;
        try {
            return new JSONObject(new String(meta, "UTF-8"));
        }
        catch (Exception e) {
            return null;
        }
    }

    // 备份时修改容器名:同步写入源容器 .container 配置的 name 字段
    private void renameContainerName(Container container, String newName) {
        try {
            File configFile = container.getConfigFile();
            JSONObject containerData = new JSONObject(FileUtils.readString(configFile));
            containerData.put("name", newName);
            FileUtils.writeString(configFile, containerData.toString());
            Log.d("ExportContainer", "容器名称已修改: "+newName);
        }
        catch (Exception e) {
            Log.e("ExportContainer", "容器名称修改失败", e);
        }
    }

    // 恢复后:若备份包元数据含名称,则写入恢复出的容器 .container 配置,使恢复后的容器名与备份时设置一致
    private void setContainerNameFromMeta(File containerDir, File backupFile) {
        JSONObject meta = parseBackupMeta(backupFile);
        if (meta == null) return;
        String metaName = meta.optString("name", "");
        if (metaName.isEmpty()) return;
        try {
            File configFile = new File(containerDir, ".container");
            JSONObject containerData = new JSONObject(FileUtils.readString(configFile));
            containerData.put("name", metaName);
            FileUtils.writeString(configFile, containerData.toString());
            Log.d("RestoreContainer", "容器名称已写入: "+metaName);
        }
        catch (Exception e) {
            Log.e("RestoreContainer", "容器名称写入失败", e);
        }
    }

    private void restoreContainer(File file) {
        if (file == null || !file.isFile() || !file.getName().toLowerCase().endsWith(WHP_EXTENSION)) {
            AppUtils.showToast(getContext(), getString(R.string.invalid_restore_file));
            return;
        }

        progressDialog.show(R.string.restoring_container);
        int id = manager.getNextContainerId();
        File homeDir = new File(RootFS.find(getContext()).getRootDir(), "home");
        File containerDir = new File(homeDir, RootFS.USER+"-"+id);
        Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            File tempDir = new File(getContext().getCacheDir(), "restore-temp");
            FileUtils.delete(tempDir);
            if (!tempDir.mkdirs()) {
                handler.post(() -> {
                    progressDialog.close();
                    AppUtils.showToast(getContext(), R.string.unable_to_restore_container);
                });
                return;
            }

            // 预扫描解压总大小,用于显示恢复进度(rootfs 安装同款方式)
            final long contentLength = TarCompressorUtils.getContentLength(TarCompressorUtils.Type.ZSTD, file, tempDir);
            final long[] totalSizeRef = {0};
            final long[] lastUpdate = {0};
            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, file, tempDir, (dest, size) -> {
                if (size > 0 && contentLength > 0) {
                    long totalSize = totalSizeRef[0] += size;
                    int percent = (int)(totalSize * 100 / contentLength);
                    if (percent - lastUpdate[0] >= 1 || percent == 100) {
                        lastUpdate[0] = percent;
                        final int p = percent;
                        handler.post(() -> progressDialog.setProgress(p));
                    }
                }
                return dest;
            });
            if (success) {
                File[] topEntries = tempDir.listFiles();
                if (topEntries != null && topEntries.length == 1 && topEntries[0].isDirectory() && topEntries[0].getName().startsWith(RootFS.USER+"-")) {
                    success = topEntries[0].renameTo(containerDir);
                    if (success) {
                        success = restoreBoundProfiles(containerDir, id);
                        if (success) {
                            restoreComponents(containerDir);
                            setContainerNameFromMeta(containerDir, file);
                        }
                    }
                } else {
                    success = false;
                }
            }
            FileUtils.delete(tempDir);
            if (!success) FileUtils.delete(containerDir);
            final boolean finalSuccess = success;
            handler.post(() -> {
                if (finalSuccess) {
                    manager = new ContainerManager(getContext());
                    loadContainersList();
                    progressDialog.close();
                } else {
                    progressDialog.close();
                    AppUtils.showToast(getContext(), R.string.unable_to_restore_container);
                }
            });
        });
    }

    // 恢复后：把容器内备份的虚拟按键配置导入 filesDir/profiles，并按映射记录重写引用 ID
    // 返回是否全部成功；profile 文件缺失时跳过（不视为失败）
    private boolean restoreBoundProfiles(File containerDir, int containerId) {
        File backupDir = new File(containerDir, PROFILES_BACKUP_DIR);
        File mapFile = new File(backupDir, PROFILES_BACKUP_MAP);
        if (!mapFile.isFile()) return true;

        Context context = getContext();
        if (context == null) return true;
        File profilesDir = InputControlsManager.getProfilesDir(context);
        InputControlsManager manager = new InputControlsManager(context);
        manager.getProfiles(true); // 触发加载，初始化 maxProfileId

        // 旧 id -> 新 id（可能不变）
        java.util.HashMap<String, String> idMap = new java.util.HashMap<>();
        // profileId -> 引用者列表（"Desktop/xxx.desktop" 或 "[container]"）
        java.util.HashMap<String, java.util.ArrayList<String>> refsByProfile = new java.util.HashMap<>();
        // 本次将写入的目标文件名集合，冲突分配时避免撞上
        java.util.HashSet<String> plannedTargets = new java.util.HashSet<>();

        // 本机已有 profile 索引：name + (去 id 规范化内容) -> id，避免对每个备份 profile 反复全量扫描
        java.util.HashMap<String, String> localProfileIndex = buildLocalProfileIndex(profilesDir);

        for (String line : FileUtils.readLines(mapFile, true)) {
            if (line.startsWith("#") || !line.contains("=")) continue;
            String value = line.substring(line.indexOf("=")+1).trim();
            String fileName = value.contains(";") ? value.substring(0, value.indexOf(";")).trim() : value;
            if (!fileName.isEmpty() && !fileName.contains("/") && !fileName.contains("..")) {
                plannedTargets.add(fileName);
            }
        }

        for (String line : FileUtils.readLines(mapFile, true)) {
            if (line.startsWith("#") || !line.contains("=")) continue;
            String oldId = line.substring(0, line.indexOf("=")).trim();
            String value = line.substring(line.indexOf("=")+1).trim();
            String fileName = value.contains(";") ? value.substring(0, value.indexOf(";")).trim() : value;
            // 防御：只允许纯文件名（防止路径穿越）
            if (oldId.isEmpty() || fileName.isEmpty() || fileName.contains("/") || fileName.contains("..")) continue;

            // 引用者列表（兼容旧格式：无分号则无引用者信息）
            java.util.ArrayList<String> refs = new java.util.ArrayList<>();
            if (value.contains(";")) {
                String refsPart = value.substring(value.indexOf(";")+1).trim();
                if (!refsPart.isEmpty()) {
                    for (String ref : refsPart.split(",")) {
                        String r = ref.trim();
                        if (!r.isEmpty()) refs.add(r);
                    }
                }
            }
            refsByProfile.put(oldId, refs);

            File src = new File(backupDir, fileName);
            if (!src.isFile()) continue;

            // 去重：本机已存在内容完全一致（忽略 id）的 profile 时直接复用其 id，不再新增
            String existingId = findEquivalentProfile(localProfileIndex, src);
            if (existingId != null) {
                idMap.put(oldId, existingId);
                Log.d("RestoreProfiles", "复用已存在 profile: 备份 id="+oldId+" -> 本机 id="+existingId);
                continue;
            }

            File targetFile = new File(profilesDir, fileName);
            if (targetFile.isFile()) {
                // ID 冲突：从现有最大 id+1 开始找新 ID（与 importProfile 的 ++maxProfileId 逻辑一致）
                int maxId = 0;
                for (ControlsProfile profile : manager.getProfiles(true)) maxId = Math.max(maxId, profile.id);
                int newId = -1;
                for (int i = maxId + 1; i < Integer.MAX_VALUE; i++) {
                    File candidate = new File(profilesDir, "controls-"+i+".icp");
                    if (!candidate.isFile() && !plannedTargets.contains("controls-"+i+".icp")) {
                        newId = i;
                        break;
                    }
                }
                if (newId == -1) continue;

                JSONObject data;
                try {
                    data = new JSONObject(FileUtils.readString(src));
                    data.put("id", newId);
                }
                catch (Exception e) {
                    continue;
                }
                targetFile = new File(profilesDir, "controls-"+newId+".icp");
                FileUtils.writeString(targetFile, data.toString());
                plannedTargets.add(targetFile.getName());
                idMap.put(oldId, String.valueOf(newId));
                indexProfile(localProfileIndex, targetFile, String.valueOf(newId));
            } else if (FileUtils.copy(src, targetFile)) {
                idMap.put(oldId, oldId);
                indexProfile(localProfileIndex, targetFile, oldId);
                Log.d("RestoreProfiles", "导入 profile 无冲突: id="+oldId);
            }
        }
        Log.d("RestoreProfiles", "idMap: "+idMap.toString());

        // 重写容器自身配置的 controlsProfile
        Container restoredContainer = new Container(containerId);
        restoredContainer.setRootDir(containerDir);
        try {
            JSONObject containerData = new JSONObject(FileUtils.readString(restoredContainer.getConfigFile()));
            JSONObject extraData = containerData.optJSONObject("extraData");
            if (extraData != null && extraData.has("controlsProfile")) {
                String oldProfile = extraData.getString("controlsProfile");
                if (idMap.containsKey(oldProfile)) {
                    extraData.put("controlsProfile", idMap.get(oldProfile));
                    containerData.put("extraData", extraData);
                    FileUtils.writeString(restoredContainer.getConfigFile(), containerData.toString());
                    Log.d("RestoreProfiles", "容器配置已重写: "+oldProfile+" -> "+idMap.get(oldProfile));
                } else {
                    Log.d("RestoreProfiles", "容器引用 "+oldProfile+" 不在 idMap，跳过");
                }
            } else {
                Log.d("RestoreProfiles", "容器 .container 无 extraData.controlsProfile");
            }
        }
        catch (Exception e) {
            Log.e("RestoreProfiles", "容器配置重写失败", e);
        }

        // 重写快捷方式文件的 controlsProfile：优先按 map 引用者精确定位，无引用者信息则扫描 Desktop 目录兜底
        File desktopDir = new File(containerDir, ".wine/drive_c/users/"+RootFS.USER+"/Desktop");
        java.util.HashSet<File> rewritten = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, java.util.ArrayList<String>> entry : refsByProfile.entrySet()) {
            String oldProfile = entry.getKey();
            if (!idMap.containsKey(oldProfile)) continue;
            String newProfile = idMap.get(oldProfile);
            for (String ref : entry.getValue()) {
                if (ref.equals("[container]")) continue;
                if (ref.startsWith("Desktop/")) {
                    File shortcutFile = new File(desktopDir, ref.substring("Desktop/".length()));
                    if (rewriteShortcutProfile(restoredContainer, shortcutFile, oldProfile, newProfile)) {
                        rewritten.add(shortcutFile);
                    }
                }
            }
        }
        // 兜底：旧格式备份（无引用者信息）扫描 Desktop 目录
        File[] shortcutFiles = desktopDir.listFiles();
        if (shortcutFiles != null) {
            for (File shortcutFile : shortcutFiles) {
                if (!shortcutFile.isFile() || !shortcutFile.getName().endsWith(".desktop") || rewritten.contains(shortcutFile)) continue;
                String oldProfile = getShortcutExtra(shortcutFile, "controlsProfile");
                if (oldProfile == null || !idMap.containsKey(oldProfile)) continue;
                rewriteShortcutProfile(restoredContainer, shortcutFile, oldProfile, idMap.get(oldProfile));
            }
        }

        FileUtils.delete(backupDir);
        return true;
    }

    // 一次性构建本机 profile 索引：name + (去 id 规范化内容) -> id，供去重时 O(1) 查找
    private static java.util.HashMap<String, String> buildLocalProfileIndex(File profilesDir) {
        java.util.HashMap<String, String> index = new java.util.HashMap<>();
        File[] files = profilesDir.listFiles();
        if (files == null) return index;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".icp")) continue;
            JSONObject data;
            try {
                data = new JSONObject(FileUtils.readString(file));
            }
            catch (Exception e) {
                continue;
            }
            int id = data.optInt("id", -1);
            String content = getContentWithoutId(data);
            // 缺失 name 的 profile 不参与去重（与旧逻辑一致）
            if (id < 0 || content == null || !data.has("name")) continue;
            // name 是内容比对的一部分，并入 key 做预筛，避免同内容不同名的 profile 相互覆盖
            index.put(data.optString("name") + "\u0000" + content, String.valueOf(id));
        }
        return index;
    }

    // 在索引中查找与备份 profile 内容完全一致（忽略 id）的已有配置，返回其 id，未找到返回 null
    private static String findEquivalentProfile(java.util.HashMap<String, String> localProfileIndex, File backupProfileFile) {
        JSONObject backupData;
        try {
            backupData = new JSONObject(FileUtils.readString(backupProfileFile));
        }
        catch (Exception e) {
            return null;
        }
        // 缺失 name 的 profile 不参与去重（与旧逻辑一致）
        if (!backupData.has("name")) return null;
        String backupContent = getContentWithoutId(backupData);
        if (backupContent == null) return null;
        return localProfileIndex.get(backupData.optString("name") + "\u0000" + backupContent);
    }

    // 将单个 profile 文件解析后写入索引（供循环内新导入的 profile 即时生效），异常时静默跳过
    private static void indexProfile(java.util.HashMap<String, String> localProfileIndex, File profileFile, String id) {
        try {
            JSONObject data = new JSONObject(FileUtils.readString(profileFile));
            if (!data.has("name")) return;
            String content = getContentWithoutId(data);
            if (content != null) localProfileIndex.put(data.optString("name") + "\u0000" + content, id);
        }
        catch (Exception e) {
            // 解析失败不参与去重即可
        }
    }

    // 返回去掉 id 字段后的规范化 JSON 字符串，用于比对两份 profile 除 id 外是否完全一致
    private static String getContentWithoutId(JSONObject profileData) {
        if (profileData == null) return null;
        try {
            JSONObject copy = new JSONObject(profileData.toString());
            copy.remove("id");
            return canonicalize(copy).toString();
        }
        catch (JSONException e) {
            return null;
        }
    }

    // 递归排序 JSON 的键，使字段顺序差异不影响比对
    // 数组顺序保持原样：元素与绑定槽位有前后语义，重排会把不同布局误判为相同
    // Android 的 JSONObject 内部是 LinkedHashMap，toString() 保留插入顺序，故必须排序
    private static Object canonicalize(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            JSONArray names = object.names();
            if (names == null) return new JSONObject();

            ArrayList<String> keys = new ArrayList<>();
            for (int i = 0; i < names.length(); i++) keys.add(names.getString(i));
            Collections.sort(keys);

            JSONObject result = new JSONObject();
            for (String key : keys) result.put(key, canonicalize(object.get(key)));
            return result;
        }
        else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            JSONArray result = new JSONArray();
            for (int i = 0; i < array.length(); i++) result.put(canonicalize(array.get(i)));
            return result;
        }
        return value;
    }

    // 重写单个快捷方式文件的 controlsProfile 引用，成功返回 true
    private boolean rewriteShortcutProfile(Container restoredContainer, File shortcutFile, String oldProfile, String newProfile) {
        if (oldProfile.equals(newProfile)) {
            Log.d("RestoreProfiles", "快捷方式 id 未变，跳过: "+shortcutFile.getName()+" ("+oldProfile+")");
            return false;
        }
        try {
            Shortcut shortcut = new Shortcut(restoredContainer, shortcutFile);
            shortcut.putExtra("controlsProfile", newProfile);
            shortcut.saveData();
            Log.d("RestoreProfiles", "快捷方式已重写: "+shortcutFile.getName()+" "+oldProfile+" -> "+newProfile);
            return true;
        }
        catch (Exception e) {
            Log.e("RestoreProfiles", "快捷方式重写失败: "+shortcutFile.getName(), e);
            return false;
        }
    }

    private class ContainersAdapter extends RecyclerView.Adapter<ContainersAdapter.ViewHolder> {
        private final List<Container> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton;
            private final ImageView menuButton;
            private final ImageView imageView;
            private final TextView title;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.runButton = view.findViewById(R.id.BTRun);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ContainersAdapter(List<Container> data) {
            this.data = data;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.container_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Container item = data.get(position);
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(item.getName());
            holder.runButton.setOnClickListener((view) -> runContainer(item));
            holder.menuButton.setOnClickListener((view) -> showListItemMenu(view, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, Container container) {
            MainActivity activity = (MainActivity)getActivity();
            PopupMenu listItemMenu = new PopupMenu(activity, anchorView);
            listItemMenu.inflate(R.menu.container_popup_menu);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                switch (menuItem.getItemId()) {
                    case R.id.menu_item_file_manager:
                        activity.showFragment(new ContainerFileManagerFragment(container.id));
                        break;
                    case R.id.menu_item_edit:
                        activity.showFragment(new ContainerDetailFragment(container.id));
                        break;
                    case R.id.menu_item_duplicate:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_duplicate_this_container, () -> {
                            progressDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, () -> {
                                progressDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.menu_item_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            progressDialog.show(R.string.removing_container);
                            manager.removeContainerAsync(container, () -> {
                                progressDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.menu_item_backup:
                        backupContainer(container);
                        break;
                    case R.id.menu_item_info:
                        (new StorageInfoDialog(activity, container)).show();
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        private void runContainer(Container container) {
            Activity activity = getActivity();
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            activity.startActivity(intent);
        }
    }
}
