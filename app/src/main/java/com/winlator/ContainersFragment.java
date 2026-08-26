package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ZipUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.xenvironment.RootFS;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class ContainersFragment extends Fragment {
    private static final String WHP_EXTENSION = ".whp";
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    ArrayList<String> filePaths = result.getData().getStringArrayListExtra(ImageFilePickerActivity.EXTRA_SELECTED_FILES);
                    if (filePaths != null && !filePaths.isEmpty()) {
                        File file = new File(filePaths.get(0));
                        if (file.isFile()) restoreContainer(file);
                    }
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
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
                String profileId = getShortcutControlsProfile(shortcutFile);
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

    // 解析 .desktop 文件 [Extra Data] 段的 controlsProfile
    private String getShortcutControlsProfile(File shortcutFile) {
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
                    String key = line.substring(0, index).trim();
                    if (key.equals("controlsProfile")) {
                        return line.substring(index+1).trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void backupContainer(Container container) {
        ContentDialog.confirm(getContext(), R.string.do_you_want_to_backup_this_container, () -> {
            preloaderDialog.show(R.string.backing_up_container);
            Handler handler = new Handler();
            Executors.newSingleThreadExecutor().execute(() -> {
                stageProfilesIntoContainer(container);
                String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                File destFile = new File(AppUtils.DIRECTORY_DOWNLOADS, container.getName()+"-"+timestamp+"-backup"+WHP_EXTENSION);
                final long[] lastUpdate = {0};
                ZipUtils.compress(container.getRootDir(), destFile, MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL, (done, total) -> {
                    if (total <= 0) return;
                    int percent = (int)(done * 100 / total);
                    if (percent - lastUpdate[0] >= 1 || percent == 100) {
                        lastUpdate[0] = percent;
                        handler.post(() -> preloaderDialog.setText(getString(R.string.backing_up_container)+" "+percent+"%"));
                    }
                });
                handler.post(() -> {
                    preloaderDialog.close();
                    FileUtils.delete(new File(container.getRootDir(), PROFILES_BACKUP_DIR));
                    if (destFile.isFile()) {
                        AppUtils.showToast(getContext(), getContext().getString(R.string.backup_saved_to)+" "+destFile.getPath());
                    } else {
                        AppUtils.showToast(getContext(), R.string.unable_to_backup_container);
                    }
                });
            });
        });
    }

    private void restoreContainer(File file) {
        if (file == null || !file.isFile() || !file.getName().toLowerCase().endsWith(WHP_EXTENSION)) {
            AppUtils.showToast(getContext(), getString(R.string.invalid_restore_file));
            return;
        }

        preloaderDialog.show(R.string.restoring_container);
        int id = manager.getNextContainerId();
        File homeDir = new File(RootFS.find(getContext()).getRootDir(), "home");
        File containerDir = new File(homeDir, RootFS.USER+"-"+id);
        Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            File tempDir = new File(getContext().getCacheDir(), "restore-temp");
            FileUtils.delete(tempDir);
            if (!tempDir.mkdirs()) {
                handler.post(() -> {
                    preloaderDialog.close();
                    AppUtils.showToast(getContext(), R.string.unable_to_restore_container);
                });
                return;
            }

            final long[] lastUpdate = {0};
            boolean success = ZipUtils.extract(file, tempDir, (done, total) -> {
                if (total <= 0) return;
                int percent = (int)(done * 100 / total);
                if (percent - lastUpdate[0] >= 1 || percent == 100) {
                    lastUpdate[0] = percent;
                    handler.post(() -> preloaderDialog.setText(getString(R.string.restoring_container)+" "+percent+"%"));
                }
            });
            if (success) {
                File[] topEntries = tempDir.listFiles();
                if (topEntries != null && topEntries.length == 1 && topEntries[0].isDirectory() && topEntries[0].getName().startsWith(RootFS.USER+"-")) {
                    success = topEntries[0].renameTo(containerDir);
                    if (success) success = restoreBoundProfiles(containerDir, id);
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
                    preloaderDialog.close();
                } else {
                    preloaderDialog.close();
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
            } else if (FileUtils.copy(src, targetFile)) {
                idMap.put(oldId, oldId);
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
                String oldProfile = getShortcutControlsProfile(shortcutFile);
                if (oldProfile == null || !idMap.containsKey(oldProfile)) continue;
                rewriteShortcutProfile(restoredContainer, shortcutFile, oldProfile, idMap.get(oldProfile));
            }
        }

        FileUtils.delete(backupDir);
        return true;
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
                            preloaderDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.menu_item_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            preloaderDialog.show(R.string.removing_container);
                            manager.removeContainerAsync(container, () -> {
                                preloaderDialog.close();
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
