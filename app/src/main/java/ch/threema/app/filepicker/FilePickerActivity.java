/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2015-2025 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.filepicker;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import org.slf4j.Logger;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ch.threema.app.R;
import ch.threema.app.activities.ThreemaToolbarActivity;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.StorageUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.utils.LoggingUtil;

import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

public class FilePickerActivity extends ThreemaToolbarActivity implements ListView.OnItemClickListener {
    private static final Logger logger = LoggingUtil.getThreemaLogger("FilePickerActivity");

    private static final int PERMISSION_STORAGE = 1;

    public static final String INTENT_DATA_DEFAULT_PATH = "defpath";

    private String currentFolder;
    private FilePickerAdapter fileArrayListAdapter;
    private FileFilter fileFilter;
    private ListView listView;
    private ArrayList<String> extensions;
    private final ArrayList<String> rootPaths = new ArrayList<>(2);
    private ActionBar actionBar;
    private DrawerLayout drawerLayout;
    private Comparator<FileInfo> comparator;
    private int currentRoot = 0;

    @Override
    public int getLayoutResource() {
        return R.layout.activity_filepicker;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);
    }

    @Override
    protected void handleDeviceInsets() {
        super.handleDeviceInsets();
        ViewExtensionsKt.applyDeviceInsetsAsPadding(
            findViewById(android.R.id.list),
            InsetSides.lbr(),
            SpacingValues.bottom(R.dimen.grid_unit_x2)
        );
        ViewExtensionsKt.applyDeviceInsetsAsPadding(
            findViewById(R.id.nav_view),
            new InsetSides(true, false, true, true)
        );
    }

    @Override
    protected boolean initActivity(Bundle savedInstanceState) {
        boolean result = super.initActivity(savedInstanceState);

        if (getConnectionIndicator() != null) {
            getConnectionIndicator().setVisibility(View.INVISIBLE);
        }

        String defaultPath = null;

        actionBar = getSupportActionBar();

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            if (extras.getStringArrayList(Constants.KEY_FILTER_FILES_EXTENSIONS) != null) {
                extensions = extras
                    .getStringArrayList(Constants.KEY_FILTER_FILES_EXTENSIONS);
                fileFilter = pathname -> ((pathname.isDirectory()) ||
                    (pathname.getName().contains(".") &&
                        extensions.contains(pathname.getName().substring(pathname.getName().lastIndexOf(".")))));
            }

            defaultPath = extras.getString(INTENT_DATA_DEFAULT_PATH, null);
            if (defaultPath != null && !(new File(defaultPath)).exists()) {
                defaultPath = null;
            }
        }

        listView = findViewById(android.R.id.list);
        if (listView == null) {
            Toast.makeText(this, "Unable to inflate layout", Toast.LENGTH_LONG).show();
            finish();
            return false;
        }

        listView.setOnItemClickListener(this);
        listView.setDivider(getResources().getDrawable(R.drawable.divider_listview));
        listView.setDividerHeight(getResources().getDimensionPixelSize(R.dimen.list_divider_height));

        if (getRootPaths() == 0) {
            Toast.makeText(this, "No storage found", Toast.LENGTH_LONG).show();
            finish();
            return false;
        }

        drawerLayout = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this,
            drawerLayout, getToolbar(), R.string.open_navdrawer, R.string.close);
        toggle.setDrawerIndicatorEnabled(true);
        toggle.setDrawerSlideAnimationEnabled(true);
        toggle.syncState();
        drawerLayout.addDrawerListener(toggle);


        if (defaultPath != null) {
            currentRoot = 0;
            currentFolder = defaultPath;
            for (int i = 0; i < rootPaths.size(); i++) {
                if (currentFolder.startsWith(rootPaths.get(i))) {
                    currentRoot = i;
                    break;
                }
            }

            // sort by date (most recent first)
            comparator = (f1, f2) -> f1.getLastModified() == f2.getLastModified()
                ? 0
                : f1.getLastModified() < f2.getLastModified() ? 1 : -1;
        } else {
            currentFolder = rootPaths.get(0);
            currentRoot = 0;
            // sort by filename
            comparator = new Comparator<FileInfo>() {
                @Override
                public int compare(FileInfo f1, FileInfo f2) {
                    return f1.getName().compareTo(f2.getName());
                }
            };
        }

        NavigationView navigationView = findViewById(R.id.nav_view);
        if (navigationView != null) {
            setupDrawerContent(navigationView);
        }

        setResult(RESULT_CANCELED);

        if (ConfigUtils.requestStoragePermissions(this, null, PERMISSION_STORAGE)) {
            scanFiles(currentFolder);
        }

        return result;
    }

    private int getRootPaths() {
        // Internal storage - should always be around
        rootPaths.addAll(Arrays.asList(StorageUtil.getStorageDirectories(this)));
        return rootPaths.size();
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
        return super.onKeyDown(keyCode, event);
    }

    private void scanFiles(String path) {
        File f = new File(path);
        File[] folders;
        if (fileFilter != null)
            folders = f.listFiles(fileFilter);
        else
            folders = f.listFiles();

        if (f.getName().equalsIgnoreCase(
            Environment.getExternalStorageDirectory().getName())) {
            actionBar.setTitle(R.string.internal_storage);
        } else {
            actionBar.setTitle(f.getName());
        }

        List<FileInfo> dirs = new ArrayList<FileInfo>();
        List<FileInfo> files = new ArrayList<FileInfo>();
        try {
            for (File file : folders) {
                if (file.isDirectory() && !file.isHidden()) {
                    dirs.add(new FileInfo(file.getName(),
                        Constants.FOLDER, file.getAbsolutePath(),
                        file.lastModified(),
                        true, false));
                } else {
                    if (!file.isHidden())
                        files.add(new FileInfo(file.getName(),
                            Formatter.formatFileSize(this, file.length()),
                            file.getAbsolutePath(),
                            file.lastModified(), false, false));
                }
            }
        } catch (Exception e) {
            logger.error("Exception", e);
        }
        Collections.sort(dirs);
        Collections.sort(files, comparator);
        dirs.addAll(files);

        String canonicalFilePath = null;
        try {
            canonicalFilePath = f.getCanonicalPath();
        } catch (IOException e) {
            logger.error("Exception", e);
        }

        if (!TestUtil.isEmptyOrNull(canonicalFilePath) && !isTop(canonicalFilePath)) {
            if (f.getParentFile() != null)
                dirs.add(0, new FileInfo("..",
                    Constants.PARENT_FOLDER, f.getParent(), 0,
                    false, true));
        }

        fileArrayListAdapter = new FilePickerAdapter(FilePickerActivity.this,
            R.layout.item_filepicker, dirs);

        listView.setAdapter(fileArrayListAdapter);
    }

    private boolean isTop(String path) {
        for (String rootPath : rootPaths) {
            File file = new File(rootPath);
            try {
                if (file.getCanonicalPath().equalsIgnoreCase(path)) {
                    return true;
                }
            } catch (IOException e) {
                logger.error("Exception", e);
            }
        }
        return false;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        FileInfo fileDescriptor = fileArrayListAdapter.getItem(position);
        if (fileDescriptor.isFolder() || fileDescriptor.isParent()) {
            currentFolder = fileDescriptor.getPath();
            scanFiles(currentFolder);
        } else {
            File fileSelected = new File(fileDescriptor.getPath());

            Intent intent = new Intent();
            intent.setData(Uri.fromFile(fileSelected));
            setResult(Activity.RESULT_OK, intent);
            finish();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (drawerLayout != null) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupDrawerContent(@NonNull final NavigationView navigationView) {
        Menu menu = navigationView.getMenu();
        if (rootPaths.size() > 1) {
            for (int i = 1; i < rootPaths.size(); i++) {
                File file = new File(rootPaths.get(i));
                MenuItem item = menu.add(R.id.main_group, Menu.NONE, i, file.getName()).setIcon(R.drawable.ic_sd_card_black_24dp);
                if (i == currentRoot) {
                    item.setChecked(true);
                }
            }
        }
        menu.setGroupCheckable(R.id.main_group, true, true);

        if (currentRoot == 0) {
            MenuItem menuItem = menu.findItem(R.id.internal_storage);
            menuItem.setChecked(true);
        }

        navigationView.setNavigationItemSelectedListener(
            menuItem -> {
                currentFolder = rootPaths.get(menuItem.getOrder());
                currentRoot = menuItem.getOrder();
                scanFiles(currentFolder);
                drawerLayout.closeDrawers();
                menuItem.setChecked(true);
                return true;
            });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_STORAGE) {
            if (grantResults.length >= 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanFiles(currentFolder);
            } else {
                finish();
            }
        }
    }
}
