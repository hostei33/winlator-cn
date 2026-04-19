package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
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
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.StorageInfoDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xenvironment.RootFS;

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
                    Uri uri = result.getData().getData();
                    if (uri != null) restoreContainer(uri);
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
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
            return true;
        }
        else return super.onOptionsItemSelected(menuItem);
    }

    private void backupContainer(Container container) {
        ContentDialog.confirm(getContext(), R.string.do_you_want_to_backup_this_container, () -> {
            preloaderDialog.show(R.string.backing_up_container);
            Handler handler = new Handler();
            Executors.newSingleThreadExecutor().execute(() -> {
                String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                File destFile = new File(AppUtils.DIRECTORY_DOWNLOADS, container.getName()+"-"+timestamp+"-backup"+WHP_EXTENSION);
                TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, container.getRootDir(), destFile, MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);
                handler.post(() -> {
                    preloaderDialog.close();
                    if (destFile.isFile()) {
                        AppUtils.showToast(getContext(), getContext().getString(R.string.backup_saved_to)+" "+destFile.getPath());
                    } else {
                        AppUtils.showToast(getContext(), R.string.unable_to_backup_container);
                    }
                });
            });
        });
    }

    private void restoreContainer(Uri uri) {
        String fileName = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) fileName = cursor.getString(idx);
                }
            }
        }
        if (fileName == null) {
            String path = uri.getPath();
            if (path != null) fileName = path.substring(path.lastIndexOf('/') + 1);
        }

        if (fileName == null || !fileName.toLowerCase().endsWith(WHP_EXTENSION)) {
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

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, getContext(), uri, tempDir);
            if (success) {
                File[] topEntries = tempDir.listFiles();
                if (topEntries != null && topEntries.length == 1 && topEntries[0].isDirectory() && topEntries[0].getName().startsWith(RootFS.USER+"-")) {
                    success = topEntries[0].renameTo(containerDir);
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
