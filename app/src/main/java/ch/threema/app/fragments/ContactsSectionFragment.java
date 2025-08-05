/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2013-2025 Threema GmbH
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

package ch.threema.app.fragments;

import static android.view.MenuItem.SHOW_AS_ACTION_ALWAYS;
import static android.view.MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW;
import static android.view.MenuItem.SHOW_AS_ACTION_NEVER;
import static ch.threema.app.asynctasks.ContactSyncPolicy.EXCLUDE;
import static ch.threema.app.asynctasks.ContactSyncPolicy.INCLUDE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.util.Pair;
import androidx.core.view.MenuItemCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import org.slf4j.Logger;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import ch.threema.app.AppConstants;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.activities.AddContactActivity;
import ch.threema.app.activities.ComposeMessageActivity;
import ch.threema.app.contactdetails.ContactDetailActivity;
import ch.threema.app.activities.ThreemaActivity;
import ch.threema.app.adapters.ContactListAdapter;
import ch.threema.app.asynctasks.AndroidContactLinkPolicy;
import ch.threema.app.asynctasks.ContactSyncPolicy;
import ch.threema.app.asynctasks.DeleteContactServices;
import ch.threema.app.asynctasks.DialogMarkContactAsDeletedBackgroundTask;
import ch.threema.app.asynctasks.EmptyOrDeleteConversationsAsyncTask;
import ch.threema.app.dialogs.BottomSheetAbstractDialog;
import ch.threema.app.dialogs.BottomSheetGridDialog;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.dialogs.SelectorDialog;
import ch.threema.app.dialogs.TextWithCheckboxDialog;
import ch.threema.app.dialogs.ThreemaDialogFragment;
import ch.threema.app.emojis.EmojiTextView;
import ch.threema.app.listeners.ContactListener;
import ch.threema.app.listeners.ContactSettingsListener;
import ch.threema.app.listeners.ConversationListener;
import ch.threema.app.listeners.PreferenceListener;
import ch.threema.app.listeners.SynchronizeContactsListener;
import ch.threema.app.managers.ListenerManager;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.routines.SynchronizeContactsRoutine;
import ch.threema.app.services.AvatarCacheService;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.ContactExportImportService;
import ch.threema.app.services.LockAppService;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.services.SynchronizeContactsService;
import ch.threema.app.services.UserService;
import ch.threema.app.ui.BottomSheetItem;
import ch.threema.app.ui.EmptyView;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.LockingSwipeRefreshLayout;
import ch.threema.app.ui.ResumePauseHandler;
import ch.threema.app.ui.SelectorDialogItem;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.BitmapUtil;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.EditTextUtil;
import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.IntentDataUtil;
import ch.threema.app.utils.MimeUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.ShareUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.app.utils.executor.BackgroundExecutor;
import ch.threema.app.workers.ContactUpdateWorker;
import ch.threema.app.workers.WorkSyncWorker;
import ch.threema.base.ThreemaException;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.domain.models.Contact;
import ch.threema.domain.models.VerificationLevel;
import ch.threema.domain.taskmanager.TriggerSource;
import ch.threema.localcrypto.MasterKeyLockedException;
import ch.threema.storage.models.ContactModel;

/**
 * This is one of the tabs in the home screen. It shows the contact list.
 */
public class ContactsSectionFragment
    extends MainFragment
    implements
    SwipeRefreshLayout.OnRefreshListener,
    ListView.OnItemClickListener,
    ContactListAdapter.AvatarListener,
    SelectorDialog.SelectorDialogClickListener,
    BottomSheetAbstractDialog.BottomSheetDialogCallback,
    TextWithCheckboxDialog.TextWithCheckboxDialogClickListener,
    GenericAlertDialog.DialogClickListener {
    private static final Logger logger = LoggingUtil.getThreemaLogger("ContactsSectionFragment");

    private static final int PERMISSION_REQUEST_REFRESH_CONTACTS = 1;
    private static final String DIALOG_TAG_SHARE_WITH = "wsw";
    private static final String DIALOG_TAG_RECENTLY_ADDED_SELECTOR = "ras";
    private static final String DIALOG_TAG_REALLY_DELETE_CONTACTS = "rdc";
    private static final String DIALOG_TAG_REPORT_SPAM = "spam";
    
    private static final int REQUEST_CODE_IMPORT_CONTACTS = 1001;

    private static final String RUN_ON_ACTIVE_SHOW_LOADING = "show_loading";
    private static final String RUN_ON_ACTIVE_HIDE_LOADING = "hide_loading";
    private static final String RUN_ON_ACTIVE_UPDATE_LIST = "update_list";
    private static final String RUN_ON_ACTIVE_REFRESH_LIST = "refresh_list";
    private static final String RUN_ON_ACTIVE_REFRESH_PULL_TO_REFRESH = "pull_to_refresh";

    private static final String BUNDLE_FILTER_QUERY_C = "BundleFilterC";
    private static final String BUNDLE_SELECTED_TAB = "tabpos";

    private static final int TAB_ALL_CONTACTS = 0;
    private static final int TAB_WORK_ONLY = 1;

    private static final int SELECTOR_TAG_CHAT = 0;
    private static final int SELECTOR_TAG_SHOW_CONTACT = 1;
    private static final int SELECTOR_TAG_REPORT_SPAM = 2;
    private static final int SELECTOR_TAG_BLOCK = 3;
    private static final int SELECTOR_TAG_DELETE = 4;

    private ResumePauseHandler resumePauseHandler;
    private ListView listView;
    private MaterialButton contactsCounterButton;
    private LockingSwipeRefreshLayout swipeRefreshLayout;
    private ServiceManager serviceManager;
    private SearchView searchView;
    private MenuItem searchMenuItem;
    private ContactListAdapter contactListAdapter;
    private ActionMode actionMode = null;
    private ExtendedFloatingActionButton floatingButtonView;
    private EmojiTextView stickyInitialView;
    private FrameLayout stickyInitialLayout;
    private TabLayout workTabLayout;

    private SynchronizeContactsService synchronizeContactsService;
    private ContactService contactService;
    private ContactExportImportService contactExportImportService;
    @Nullable
    private PreferenceService preferenceService;
    private LockAppService lockAppService;

    private final BackgroundExecutor backgroundExecutor = new BackgroundExecutor();

    private String filterQuery;
    @SuppressLint("StaticFieldLeak")
    private final TabLayout.OnTabSelectedListener onTabSelectedListener = new TabLayout.OnTabSelectedListener() {
        @Override
        public void onTabSelected(TabLayout.Tab tab) {
            if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                return;
            }

            if (actionMode != null) {
                actionMode.finish();
            }

            new FetchContactsTask(contactService, false, tab.getPosition(), true) {
                @Override
                protected void onPostExecute(Pair<List<ContactModel>, FetchResults> result) {
                    final List<ContactModel> contactModels = result.first;

                    if (contactModels != null && contactListAdapter != null) {
                        contactListAdapter.updateData(contactModels);
                        if (!TestUtil.isEmptyOrNull(filterQuery)) {
                            contactListAdapter.getFilter().filter(filterQuery);
                        }
                    }
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        }

        @Override
        public void onTabUnselected(TabLayout.Tab tab) {
        }

        @Override
        public void onTabReselected(TabLayout.Tab tab) {
        }
    };

    /**
     * Simple POJO to hold the number of contacts that were added in the last 24h / 30d.
     */
    private static class FetchResults {
        int last24h = 0;
        int last30d = 0;
        int workCount = 0;
    }

    // Contacts changed receiver
    private final BroadcastReceiver contactsChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (resumePauseHandler != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_REFRESH_LIST, runIfActiveUpdateList);
            }
        }
    };

    private void startSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(true);
        }
    }

    private void stopSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    private final ResumePauseHandler.RunIfActive runIfActiveShowLoading = () -> {
        // do nothing
    };

    private final ResumePauseHandler.RunIfActive runIfActiveClearCacheAndRefresh = new ResumePauseHandler.RunIfActive() {
        @Override
        public void runOnUiThread() {
            if (synchronizeContactsService != null && !synchronizeContactsService.isSynchronizationInProgress()) {
                stopSwipeRefresh();

                if (serviceManager != null) {
                    AvatarCacheService avatarCacheService = serviceManager.getAvatarCacheService();
                    //clear the cache
                    avatarCacheService.clear();
                }
                updateList();
            }
        }
    };

    private final ResumePauseHandler.RunIfActive runIfActiveUpdateList = new ResumePauseHandler.RunIfActive() {
        @Override
        public void runOnUiThread() {
            if (synchronizeContactsService == null || !synchronizeContactsService.isSynchronizationInProgress()) {
                updateList();
            }
        }
    };

    private final ResumePauseHandler.RunIfActive runIfActiveUpdatePullToRefresh = new ResumePauseHandler.RunIfActive() {
        @Override
        public void runOnUiThread() {
            if (TestUtil.required(swipeRefreshLayout, preferenceService)) {
                swipeRefreshLayout.setEnabled(true);
            }
        }
    };

    private final ResumePauseHandler.RunIfActive runIfActiveCreateList = () -> createListAdapter(null);

    private final SynchronizeContactsListener synchronizeContactsListener = new SynchronizeContactsListener() {
        @Override
        public void onStarted(SynchronizeContactsRoutine startedRoutine) {
            //only show loading on "full sync"
            if (resumePauseHandler != null && swipeRefreshLayout != null && startedRoutine.isFullSync()) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_SHOW_LOADING, runIfActiveShowLoading);
            }
        }

        @Override
        public void onFinished(SynchronizeContactsRoutine finishedRoutine) {
            if (resumePauseHandler != null && swipeRefreshLayout != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_HIDE_LOADING, runIfActiveClearCacheAndRefresh);
            }
        }

        @Override
        public void onError(SynchronizeContactsRoutine finishedRoutine) {
            if (resumePauseHandler != null && swipeRefreshLayout != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_HIDE_LOADING, runIfActiveClearCacheAndRefresh);
            }
        }
    };

    private final ContactSettingsListener contactSettingsListener = new ContactSettingsListener() {
        @Override
        public void onSortingChanged() {
            if (resumePauseHandler != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_REFRESH_LIST, runIfActiveCreateList);
            }
        }

        @Override
        public void onNameFormatChanged() {
            if (resumePauseHandler != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_REFRESH_LIST, runIfActiveUpdateList);
            }
        }

        @Override
        public void onAvatarSettingChanged() {
            if (resumePauseHandler != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_REFRESH_LIST, runIfActiveUpdateList);
            }
        }

        @Override
        public void onInactiveContactsSettingChanged() {
            if (resumePauseHandler != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_REFRESH_LIST, runIfActiveUpdateList);
            }
        }

        @Override
        public void onNotificationSettingChanged(String uid) {

        }
    };

    private final ContactListener contactListener = new ContactListener() {
        @Override
        public void onModified(final @NonNull String identity) {
            if (resumePauseHandler != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_UPDATE_LIST, runIfActiveUpdateList);
            }
        }

        @Override
        public void onAvatarChanged(final @NonNull String identity) {
            this.onModified(identity);
        }

        @Override
        public void onNew(final @NonNull String identity) {
            this.onModified(identity);
        }

        @Override
        public void onRemoved(@NonNull String identity) {
            RuntimeUtil.runOnUiThread(() -> {
                if (searchView != null && searchMenuItem != null && searchMenuItem.isActionViewExpanded()) {
                    filterQuery = null;
                    searchMenuItem.collapseActionView();
                }
            });

            if (resumePauseHandler != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_UPDATE_LIST, runIfActiveUpdateList);
            }
        }
    };

    private final PreferenceListener preferenceListener = new PreferenceListener() {
        @Override
        public void onChanged(String key, Object value) {
            if (isAdded() && !isDetached()) {
                if (preferenceService != null && preferenceService.getContactSyncPolicySetting().preferenceKey.equals(key)) {
                    if (resumePauseHandler != null) {
                        resumePauseHandler.runOnActive(RUN_ON_ACTIVE_REFRESH_PULL_TO_REFRESH, runIfActiveUpdatePullToRefresh);
                    }
                    updateEmptyView();
                }
            }
        }
    };

    /**
     * An AsyncTask that fetches contacts and add counts in the background.
     * <p>
     * (and maybe other code) to separate files, to simplify this 1500+-LOC class.
     */
    private static class FetchContactsTask extends AsyncTask<Void, Void, Pair<List<ContactModel>, FetchResults>> {
        ContactService contactService;
        boolean isOnLaunch, forceWork;
        int selectedTab;
        private static final Logger logger = LoggingUtil.getThreemaLogger("ContactsSectionFragment");

        FetchContactsTask(ContactService contactService, boolean isOnLaunch, int selectedTab, boolean forceWork) {
            this.contactService = contactService;
            this.isOnLaunch = isOnLaunch;
            this.selectedTab = selectedTab;
            this.forceWork = forceWork;
        }

        @Override
        protected Pair<List<ContactModel>, FetchResults> doInBackground(Void... voids) {
            logger.info("FetchContactsTask: Starting doInBackground - selectedTab: " + selectedTab + ", forceWork: " + forceWork);
            List<ContactModel> allContacts = null;

            // Count new contacts
            final FetchResults results = new FetchResults();

            if (ConfigUtils.isWorkBuild()) {
                results.workCount = contactService.countIsWork();
                logger.info("FetchContactsTask: Work build - workCount: " + results.workCount);
                if (selectedTab == TAB_WORK_ONLY) {
                    if (results.workCount > 0 || forceWork) {
                        allContacts = contactService.getAllDisplayedWork(ContactService.ContactSelection.INCLUDE_INVALID);
                        logger.info("FetchContactsTask: Retrieved work contacts: " + (allContacts != null ? allContacts.size() : 0));
                    }
                }
            }

            if (allContacts == null) {
                logger.info("FetchContactsTask: Calling contactService.getAllDisplayed(INCLUDE_INVALID)");
                allContacts = contactService.getAllDisplayed(ContactService.ContactSelection.INCLUDE_INVALID);
                logger.info("FetchContactsTask: Retrieved all displayed contacts: " + (allContacts != null ? allContacts.size() : 0));
                
                // Also get ALL contacts for comparison
                List<ContactModel> allContactsInDB = contactService.getAll();
                logger.info("FetchContactsTask: Total contacts in DB (getAll()): " + (allContactsInDB != null ? allContactsInDB.size() : 0));
                
                if (allContacts != null && allContacts.size() > 0) {
                    logger.info("FetchContactsTask: Contact identities from getAllDisplayed: " + 
                        allContacts.stream().map(c -> c.getIdentity() + "(" + c.getFirstName() + " " + c.getLastName() + ")").collect(java.util.stream.Collectors.joining(", ")));
                }
                
                if (allContactsInDB != null && allContactsInDB.size() > 0) {
                    logger.info("FetchContactsTask: ALL contact identities from getAll(): " + 
                        allContactsInDB.stream().map(c -> c.getIdentity() + "(" + c.getFirstName() + " " + c.getLastName() + ")" + 
                        " [hidden=" + c.isHidden() + ", state=" + c.getState() + "]").collect(java.util.stream.Collectors.joining(", ")));
                }
            }

            if (!ConfigUtils.isWorkBuild()) {
                long now = System.currentTimeMillis();
                long delta24h = 1000L * 3600 * 24;
                long delta30d = delta24h * 30;
                for (ContactModel contact : allContacts) {
                    final Date dateCreated = contact.getDateCreated();
                    if (dateCreated == null) {
                        continue;
                    }
                    if (now - dateCreated.getTime() < delta24h) {
                        results.last24h += 1;
                    }
                    if (now - dateCreated.getTime() < delta30d) {
                        results.last30d += 1;
                    }
                }
            }
            return new Pair<>(allContacts, results);
        }
    }

    @Override
    public void onResume() {
        logger.debug("onResume");
        if (this.resumePauseHandler != null) {
            this.resumePauseHandler.onResume();
        }

        if (this.swipeRefreshLayout != null) {
            this.swipeRefreshLayout.setEnabled(this.listView != null && this.listView.getFirstVisiblePosition() == 0);
            stopSwipeRefresh();
        }
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        logger.debug("onPause");

        if (this.resumePauseHandler != null) {
            this.resumePauseHandler.onPause();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logger.debug("onCreate");

        setRetainInstance(true);
        setHasOptionsMenu(true);

        setupListeners();

        this.resumePauseHandler = ResumePauseHandler.getByActivity(this, this.getActivity());

        this.resumePauseHandler.runOnActive(RUN_ON_ACTIVE_REFRESH_PULL_TO_REFRESH, runIfActiveUpdatePullToRefresh);
    }

    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        logger.debug("onAttach");
    }

    @Override
    public void onDestroy() {
        logger.debug("onDestroy");

        removeListeners();

        if (this.resumePauseHandler != null) {
            this.resumePauseHandler.onDestroy(this);
        }

        super.onDestroy();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        logger.debug("onHiddenChanged: " + hidden);
        if (hidden) {
            if (actionMode != null) {
                actionMode.finish();
            }

            if (this.searchView != null && this.searchView.isShown() && this.searchMenuItem != null) {
                this.searchMenuItem.collapseActionView();
            }
            if (this.resumePauseHandler != null) {
                this.resumePauseHandler.onPause();
            }
        } else {
            if (this.resumePauseHandler != null) {
                this.resumePauseHandler.onResume();
            }
        }
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        super.onPrepareOptionsMenu(menu);

        // move search item to popup if the lock item is visible
        if (this.searchMenuItem != null) {
            if (lockAppService != null && lockAppService.isLockingEnabled()) {
                this.searchMenuItem.setShowAsAction(SHOW_AS_ACTION_NEVER | SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            } else {
                this.searchMenuItem.setShowAsAction(SHOW_AS_ACTION_ALWAYS | SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, @NonNull MenuInflater inflater) {
        logger.debug("onCreateOptionsMenu");
        searchMenuItem = menu.findItem(R.id.menu_search_contacts);

        if (searchMenuItem == null) {
            inflater.inflate(R.menu.fragment_contacts, menu);

            if (getActivity() != null && this.isAdded()) {
                this.searchMenuItem = menu.findItem(R.id.menu_search_contacts);
                this.searchView = (SearchView) searchMenuItem.getActionView();

                if (this.searchView != null) {
                    if (!TestUtil.isEmptyOrNull(filterQuery)) {
                        // restore filter
                        MenuItemCompat.expandActionView(searchMenuItem);
                        this.searchView.post(() -> {
                            searchView.setQuery(filterQuery, true);
                            searchView.clearFocus();
                        });
                    }
                    this.searchView.setQueryHint(getString(R.string.hint_filter_list));
                    this.searchView.setOnQueryTextListener(queryTextListener);
                }
            }
        }
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        logger.info("onOptionsItemSelected: itemId=" + item.getItemId() + ", title=" + item.getTitle());
        
        if (item.getItemId() == R.id.menu_export_contacts) {
            logger.info("Export contacts menu item selected");
            exportContacts();
            return true;
        } else if (item.getItemId() == R.id.menu_import_contacts) {
            logger.info("Import contacts menu item selected");
            importContacts();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    final SearchView.OnQueryTextListener queryTextListener = new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextChange(String query) {
            if (contactListAdapter != null) {
                filterQuery = query;
                contactListAdapter.getFilter().filter(query);
            }
            return true;
        }

        @Override
        public boolean onQueryTextSubmit(String query) {
            return true;
        }
    };

    private int getDesiredWorkTab(boolean isOnFirstLaunch, Bundle savedInstanceState) {
        if (ConfigUtils.isWorkBuild()) {
            if (!isOnFirstLaunch) {
                if (savedInstanceState != null) {
                    return savedInstanceState.getInt(BUNDLE_SELECTED_TAB, TAB_ALL_CONTACTS);
                } else if (workTabLayout != null) {
                    return workTabLayout.getSelectedTabPosition();
                }
            }
        }
        return TAB_ALL_CONTACTS;
    }

    @SuppressLint("StaticFieldLeak")
    protected void createListAdapter(final Bundle savedInstanceState) {
        if (getActivity() == null) {
            return;
        }

        if (!this.requiredInstances()) {
            return;
        }

        final int[] desiredTabPosition = {getDesiredWorkTab(savedInstanceState == null, savedInstanceState)};

        new FetchContactsTask(contactService, savedInstanceState == null, desiredTabPosition[0], false) {
            @Override
            protected void onPostExecute(Pair<List<ContactModel>, FetchResults> result) {
                final List<ContactModel> contactModels = result.first;
                final FetchResults counts = result.second;
                if (contactModels != null) {
                    updateContactsCounter(contactModels.size(), counts);
                    if (!contactModels.isEmpty()) {
                        ((EmptyView) listView.getEmptyView()).setup(R.string.no_matching_contacts);
                    }

                    if (isAdded() && getContext() != null) {
                        contactListAdapter = new ContactListAdapter(
                            getContext(),
                            contactModels,
                            contactService,
                            serviceManager.getPreferenceService(),
                            serviceManager.getBlockedIdentitiesService(),
                            ContactsSectionFragment.this,
                            Glide.with(getContext())
                        );
                        listView.setAdapter(contactListAdapter);
                    }

                    if (ConfigUtils.isWorkBuild()) {
                        if (savedInstanceState == null && desiredTabPosition[0] == TAB_WORK_ONLY && counts.workCount == 0) {
                            // fix selected tab as there is now work contact
                            desiredTabPosition[0] = TAB_ALL_CONTACTS;
                        }

                        if (desiredTabPosition[0] != workTabLayout.getSelectedTabPosition()) {
                            workTabLayout.removeOnTabSelectedListener(onTabSelectedListener);
                            workTabLayout.selectTab(workTabLayout.getTabAt(selectedTab));
                            workTabLayout.addOnTabSelectedListener(onTabSelectedListener);
                        }
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
    }

    @SuppressLint("StaticFieldLeak")
    private void updateList() {
        logger.info("ContactsSectionFragment: updateList() called");
        if (!this.requiredInstances()) {
            logger.error("could not instantiate required objects");
            return;
        }

        int desiredTab = getDesiredWorkTab(false, null);
        logger.info("ContactsSectionFragment: updateList() - desiredTab: " + desiredTab);

        if (contactListAdapter != null) {
            logger.info("ContactsSectionFragment: Starting FetchContactsTask for updateList");
            new FetchContactsTask(contactService, false, desiredTab, false) {
                @Override
                protected void onPostExecute(Pair<List<ContactModel>, FetchResults> result) {
                    final List<ContactModel> contactModels = result.first;
                    final FetchResults counts = result.second;

                    logger.info("ContactsSectionFragment: FetchContactsTask completed - " + 
                        (contactModels != null ? contactModels.size() : 0) + " contacts fetched");
                    
                    if (contactModels != null) {
                        logger.info("ContactsSectionFragment: Contact list from DB: " + 
                            contactModels.stream().map(c -> c.getIdentity() + "(" + c.getFirstName() + " " + c.getLastName() + ")").collect(java.util.stream.Collectors.joining(", ")));
                    }

                    if (contactModels != null && contactListAdapter != null && isAdded()) {
                        updateContactsCounter(contactModels.size(), counts);
                        logger.info("ContactsSectionFragment: Calling contactListAdapter.updateData() with " + contactModels.size() + " contacts");
                        contactListAdapter.updateData(contactModels);
                        logger.info("ContactsSectionFragment: contactListAdapter.updateData() completed");
                    } else {
                        logger.warn("ContactsSectionFragment: Cannot update adapter - contactModels: " + 
                            (contactModels != null ? "not null" : "null") + 
                            ", contactListAdapter: " + (contactListAdapter != null ? "not null" : "null") + 
                            ", isAdded: " + isAdded());
                    }
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        } else {
            logger.warn("ContactsSectionFragment: contactListAdapter is null, cannot update list");
        }
    }

    private void updateContactsCounter(int numContacts, @Nullable FetchResults counts) {
        if (getActivity() != null && listView != null && isAdded()) {
            if (contactsCounterButton != null) {
                if (counts != null) {
                    ListenerManager.contactCountListener.handle(listener -> listener.onNewContactsCountUpdated(counts.last24h));
                }
                if (numContacts > 1) {
                    final StringBuilder builder = new StringBuilder();
                    builder.append(ConfigUtils.getSafeQuantityString(getContext(), R.plurals.contacts_counter_label, numContacts, numContacts));
                    if (counts != null) {
                        builder.append(" (+").append(counts.last30d).append(" / ").append(getString(R.string.thirty_days_abbrev)).append(")");
                    }
                    contactsCounterButton.setText(builder.toString());
                    contactsCounterButton.setVisibility(View.VISIBLE);
                } else {
                    contactsCounterButton.setVisibility(View.GONE);
                }
            }
            if (ConfigUtils.isWorkBuild() && counts != null) {
                if (counts.workCount > 0) {
                    showWorkTabs();
                } else {
                    hideWorkTabs();
                }
            }
        }
    }

    private void showWorkTabs() {
        if (workTabLayout != null && listView != null) {

            ViewExtensionsKt.applyDeviceInsetsAsPadding(
                (View) workTabLayout.getParent(),
                InsetSides.horizontal()
            );

            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) listView.getLayoutParams();
            ((ViewGroup) workTabLayout.getParent()).setVisibility(View.VISIBLE);
            layoutParams.topMargin = getResources().getDimensionPixelSize(R.dimen.header_contact_section_work_height);
            listView.setLayoutParams(layoutParams);
            setStickyInitialLayoutTopMargin(layoutParams.topMargin);
        }
    }

    private void hideWorkTabs() {
        if (workTabLayout != null && listView != null) {
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) listView.getLayoutParams();
            if (workTabLayout.getSelectedTabPosition() != 0) {
                workTabLayout.selectTab(workTabLayout.getTabAt(0));
            }
            ((ViewGroup) workTabLayout.getParent()).setVisibility(View.GONE);
            layoutParams.topMargin = 0;
            listView.setLayoutParams(layoutParams);

            setStickyInitialLayoutTopMargin(layoutParams.topMargin);
        }
    }

    private void setStickyInitialLayoutTopMargin(int margin) {
        if (stickyInitialLayout != null) {
            ViewGroup.MarginLayoutParams stickyInitialLayoutLayoutParams = (ViewGroup.MarginLayoutParams) stickyInitialLayout.getLayoutParams();
            stickyInitialLayoutLayoutParams.topMargin = margin;
            stickyInitialLayout.setLayoutParams(stickyInitialLayoutLayoutParams);
        }
    }

    final protected boolean requiredInstances() {
        if (!this.checkInstances()) {
            this.instantiate();
        }
        return this.checkInstances();
    }

    protected boolean checkInstances() {
        return TestUtil.required(
            this.serviceManager,
            this.contactListener,
            this.preferenceService,
            this.synchronizeContactsService,
            this.lockAppService,
            this.contactExportImportService);
    }

    protected void instantiate() {
        this.serviceManager = ThreemaApplication.getServiceManager();

        if (this.serviceManager != null) {
            try {
                this.contactService = this.serviceManager.getContactService();
                this.preferenceService = this.serviceManager.getPreferenceService();
                this.synchronizeContactsService = this.serviceManager.getSynchronizeContactsService();
                this.lockAppService = this.serviceManager.getLockAppService();
                this.contactExportImportService = new ContactExportImportService(getContext(), this.contactService, this.serviceManager.getDatabaseService());
            } catch (MasterKeyLockedException e) {
                logger.debug("Master Key locked!");
            }
        }
    }

    private void onFABClicked(View v) {
        logger.info("FAB clicked");
        Intent intent = new Intent(getActivity(), AddContactActivity.class);
        intent.putExtra(AddContactActivity.EXTRA_ADD_BY_ID, true);
        startActivity(intent);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View headerView, fragmentView = getView();

        logger.debug("onCreateView");
        if (fragmentView == null) {
            fragmentView = inflater.inflate(R.layout.fragment_contacts, container, false);

            if (!this.requiredInstances()) {
                logger.error("could not instantiate required objects");
            }

            listView = fragmentView.findViewById(android.R.id.list);
            ViewExtensionsKt.applyDeviceInsetsAsPadding(
                listView,
                InsetSides.horizontal(),
                new SpacingValues(null, null, R.dimen.grid_unit_x10, null)
            );
            listView.setOnItemClickListener(this);
            listView.setDividerHeight(0);
            listView.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE_MODAL);
            listView.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
                MenuItem shareItem;

                @Override
                public void onItemCheckedStateChanged(android.view.ActionMode mode, int position, long id, boolean checked) {
                    if (shareItem != null) {
                        final int count = listView.getCheckedItemCount();
                        if (count > 0) {
                            mode.setTitle(Integer.toString(count));
                            shareItem.setVisible(count == 1);
                        }
                    }
                }

                @Override
                public boolean onCreateActionMode(android.view.ActionMode mode, Menu menu) {
                    mode.getMenuInflater().inflate(R.menu.action_contacts_section, menu);
                    actionMode = mode;

                    ConfigUtils.tintMenuIcons(menu, ConfigUtils.getColorFromAttribute(getContext(), R.attr.colorPrimary));

                    return true;
                }

                @Override
                public boolean onPrepareActionMode(android.view.ActionMode mode, Menu menu) {
                    shareItem = menu.findItem(R.id.menu_contacts_share);
                    mode.setTitle(Integer.toString(listView.getCheckedItemCount()));

                    return true;
                }

                @Override
                public boolean onActionItemClicked(android.view.ActionMode mode, MenuItem item) {
                    int id = item.getItemId();
                    if (id == R.id.menu_contacts_remove) {
                        deleteContacts(contactListAdapter.getCheckedItems());
                        return true;
                    } else if (id == R.id.menu_contacts_share) {
                        HashSet<ContactModel> contactModels = contactListAdapter.getCheckedItems();
                        if (contactModels.size() == 1) {
                            ShareUtil.shareContact(getActivity(), contactModels.iterator().next());
                        }
                        return true;
                    }
                    return false;
                }

                @Override
                public void onDestroyActionMode(android.view.ActionMode mode) {
                    actionMode = null;
                }
            });

            this.stickyInitialView = fragmentView.findViewById(R.id.initial_sticky);
            this.stickyInitialLayout = fragmentView.findViewById(R.id.initial_sticky_layout);
            this.stickyInitialLayout.setVisibility(View.GONE);

            if (!ConfigUtils.isWorkBuild()) {
                headerView = View.inflate(getActivity(), R.layout.header_contact_section, null);
                listView.addHeaderView(headerView, null, false);

                View footerView = View.inflate(getActivity(), R.layout.footer_contact_section, null);
                ViewExtensionsKt.applyDeviceInsetsAsPadding(
                    footerView,
                    InsetSides.horizontal(),
                    new SpacingValues(R.dimen.grid_unit_x1, null, R.dimen.grid_unit_x1_5, null)
                );
                this.contactsCounterButton = footerView.findViewById(R.id.contact_counter_text);
                listView.addFooterView(footerView, null, false);

                final RelativeLayout shareContainer = headerView.findViewById(R.id.share_container);
                shareContainer.setOnClickListener(v -> shareInvite());

                ViewExtensionsKt.applyDeviceInsetsAsPadding(
                    shareContainer,
                    InsetSides.horizontal(),
                    SpacingValues.symmetric(R.dimen.listitem_contacts_margin_top_bottom, R.dimen.listitem_contacts_margin_left_right)
                );
            } else {
                workTabLayout = fragmentView.findViewById(R.id.work_contacts_tab_layout);
                workTabLayout.addOnTabSelectedListener(onTabSelectedListener);
                showWorkTabs();
            }

            this.swipeRefreshLayout = fragmentView.findViewById(R.id.swipe_container);
            this.swipeRefreshLayout.setOnRefreshListener(this);
            this.swipeRefreshLayout.setDistanceToTriggerSync(getResources().getConfiguration().screenHeightDp / 3);
            this.swipeRefreshLayout.setColorSchemeResources(R.color.md_theme_light_primary);
            this.swipeRefreshLayout.setSize(SwipeRefreshLayout.LARGE);

            this.floatingButtonView = fragmentView.findViewById(R.id.floating);
            this.floatingButtonView.setOnClickListener(this::onFABClicked);
            ViewExtensionsKt.applyDeviceInsetsAsMargin(
                this.floatingButtonView,
                InsetSides.horizontal(),
                SpacingValues.all(R.dimen.floating_button_margin)
            );
        }
        return fragmentView;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        logger.debug("onViewCreated");

        if (getActivity() != null && listView != null) {
            EmptyView emptyView = new EmptyView(getActivity());
            ((ViewGroup) listView.getParent()).addView(emptyView);
            listView.setEmptyView(emptyView);
            updateEmptyView();
            listView.setOnScrollListener(new AbsListView.OnScrollListener() {
                private int previousFirstVisibleItem = -1;

                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    if (swipeRefreshLayout != null) {
                        if (view != null && view.getChildCount() == 0) {
                            swipeRefreshLayout.setEnabled(true);
                        } else if (view != null && view.getChildCount() > 0) {
                            swipeRefreshLayout.setEnabled(firstVisibleItem == 0 && view.getChildAt(0).getTop() == 0);
                        } else {
                            swipeRefreshLayout.setEnabled(false);
                        }
                    }

                    if (view != null) {
                        if (contactListAdapter != null) {
                            int direction = 0;

                            if (floatingButtonView != null) {
                                if (firstVisibleItem == 0) {
                                    floatingButtonView.extend();
                                } else {
                                    floatingButtonView.shrink();
                                }
                            }

                            int headerCount = listView.getHeaderViewsCount();
                            firstVisibleItem -= headerCount;

                            if (firstVisibleItem != previousFirstVisibleItem) {
                                if (previousFirstVisibleItem != -1 && firstVisibleItem != -1) {
                                    if (previousFirstVisibleItem < firstVisibleItem) {
                                        // Scroll Down
                                        direction = 1;
                                    }
                                    if (previousFirstVisibleItem > firstVisibleItem) {
                                        // Scroll Up
                                        direction = -1;
                                    }


                                    stickyInitialView.setText(contactListAdapter.getInitial(firstVisibleItem));

                                    String currentInitial = contactListAdapter.getInitial(firstVisibleItem);
                                    String previousInitial = contactListAdapter.getInitial(previousFirstVisibleItem);
                                    String nextInitial = "";

                                    if (ContactListAdapter.RECENTLY_ADDED_SIGN.equals(currentInitial)) {
                                        stickyInitialLayout.setVisibility(View.GONE);
                                    } else {
                                        if (direction == 1 && firstVisibleItem < contactListAdapter.getCount()) {
                                            nextInitial = contactListAdapter.getInitial(firstVisibleItem + 1);
                                        } else if (direction == -1 && firstVisibleItem > 0) {
                                            nextInitial = contactListAdapter.getInitial(firstVisibleItem - 1);
                                        }

                                        if (direction == 1) {
                                            stickyInitialLayout.setVisibility(nextInitial.equals(currentInitial) ? View.VISIBLE : View.GONE);
                                        } else {
                                            stickyInitialLayout.setVisibility(previousInitial.equals(currentInitial) ? View.VISIBLE : View.GONE);
                                        }
                                    }
                                } else {
                                    stickyInitialLayout.setVisibility(View.GONE);
                                }
                            }
                            previousFirstVisibleItem = firstVisibleItem;
                        }
                    }
                }
            });
        }

        if (savedInstanceState != null) {
            if (TestUtil.isEmptyOrNull(this.filterQuery)) {
                this.filterQuery = savedInstanceState.getString(BUNDLE_FILTER_QUERY_C);
            }
        }

        // fill adapter with data
        createListAdapter(savedInstanceState);

        // register a receiver that will receive info about changed contacts from contact sync
        IntentFilter filter = new IntentFilter();
        filter.addAction(IntentDataUtil.ACTION_CONTACTS_CHANGED);
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(contactsChangedReceiver, filter);
    }

    private void updateEmptyView() {
        if (listView == null) {
            return;
        }
        EmptyView emptyView = (EmptyView) listView.getEmptyView();
        emptyView.setup(
            preferenceService != null && preferenceService.isSyncContacts()
                ? R.string.no_contacts_sync_on
                : R.string.no_contacts
        );
    }

    @Override
    public void onDestroyView() {
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(contactsChangedReceiver);

        searchView = null;
        searchMenuItem = null;
        contactListAdapter = null;

        super.onDestroyView();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        logger.info("ContactsSectionFragment: onActivityResult called - requestCode: " + requestCode + ", resultCode: " + resultCode);
        
        if (requestCode == REQUEST_CODE_IMPORT_CONTACTS) {
            logger.info("ContactsSectionFragment: Import contacts result received");
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                logger.info("ContactsSectionFragment: File selected for import: " + (uri != null ? uri.toString() : "null"));
                if (uri != null) {
                    processContactImport(uri);
                } else {
                    logger.warn("ContactsSectionFragment: No file URI received");
                    Toast.makeText(getActivity(), "No file selected", Toast.LENGTH_SHORT).show();
                }
            } else {
                logger.info("ContactsSectionFragment: Import cancelled or failed - resultCode: " + resultCode);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRefresh() {
        if (actionMode != null) {
            actionMode.finish();
        }

        startSwipeRefresh();

        new Handler(Looper.getMainLooper()).postDelayed(this::stopSwipeRefresh, 2000);

        try {
            ContactUpdateWorker.performOneTimeSync(requireContext());
        } catch (IllegalStateException ignored) {
        }

        if (this.preferenceService.isSyncContacts() && ConfigUtils.requestContactPermissions(getActivity(), this, PERMISSION_REQUEST_REFRESH_CONTACTS)) {
            if (this.synchronizeContactsService != null) {
                // we force a contact sync even if the grace time has not yet been reached
                preferenceService.setTimeOfLastContactSync(0L);
                synchronizeContactsService.instantiateSynchronizationAndRun();
            }
        }

        if (ConfigUtils.isWorkBuild()) {
            try {
                WorkSyncWorker.Companion.performOneTimeWorkSync(
                    ThreemaApplication.getAppContext(),
                    true,
                    "WorkContactSync"
                );
            } catch (IllegalStateException e) {
                logger.error("Unable to schedule work sync one time work", e);
            }
        }
    }

    private void openConversationForIdentity(@Nullable View v, String identity) {
        // Close keyboard if search view is expanded
        if (searchView != null && !searchView.isIconified()) {
            EditTextUtil.hideSoftKeyboard(searchView);
        }

        Intent intent = new Intent(getActivity(), ComposeMessageActivity.class);
        intent.putExtra(AppConstants.INTENT_DATA_CONTACT, identity);
        intent.putExtra(AppConstants.INTENT_DATA_EDITFOCUS, Boolean.TRUE);

        getActivity().startActivityForResult(intent, ThreemaActivity.ACTIVITY_ID_COMPOSE_MESSAGE);
    }

    private void openContact(@Nullable View view, String identity) {
        Intent intent = new Intent(getActivity(), ContactDetailActivity.class);
        intent.putExtra(AppConstants.INTENT_DATA_CONTACT, identity);

        getActivity().startActivityForResult(intent, ThreemaActivity.ACTIVITY_ID_CONTACT_DETAIL);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        logger.info("saveInstance");

        if (!TestUtil.isEmptyOrNull(filterQuery)) {
            outState.putString(BUNDLE_FILTER_QUERY_C, filterQuery);
        }
        if (ConfigUtils.isWorkBuild() && workTabLayout != null) {
            outState.putInt(BUNDLE_SELECTED_TAB, workTabLayout.getSelectedTabPosition());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public boolean onBackPressed() {
        if (actionMode != null) {
            actionMode.finish();
            return true;
        }
        if (this.searchView != null && this.searchView.isShown() && this.searchMenuItem != null) {
            MenuItemCompat.collapseActionView(this.searchMenuItem);
            return true;
        }
        return false;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
        ContactModel contactModel = contactListAdapter.getClickedItem(v);

        if (contactModel != null) {
            String identity = contactModel.getIdentity();
            if (identity != null) {
                logger.info("Contact clicked, opening conversation");
                openConversationForIdentity(v, identity);
            }
        }
    }

    @Override
    public void onAvatarClick(View view, int position) {
        if (contactListAdapter == null) {
            return;
        }

        View listItemView = (View) view.getParent();

        if (contactListAdapter.getCheckedItemCount() > 0) {
            // forward click on avatar to relevant list item
            position += listView.getHeaderViewsCount();
            listView.setItemChecked(position, !listView.isItemChecked(position));
            return;
        }

        final @Nullable ContactModel clickedContactModel = contactListAdapter.getClickedItem(listItemView);
        if (clickedContactModel != null) {
            logger.info("Contact avatar clicked");
            openContact(view, clickedContactModel.getIdentity());
        }
    }

    @Override
    public boolean onAvatarLongClick(View view, int position) {
        return true;
    }

    @Override
    public void onRecentlyAddedClick(ContactModel contactModel) {
        String contactName = NameUtil.getDisplayNameOrNickname(contactModel, true);

        ArrayList<SelectorDialogItem> items = new ArrayList<>();
        ArrayList<Integer> tags = new ArrayList<>();

        items.add(new SelectorDialogItem(getString(R.string.chat_with, contactName), R.drawable.ic_chat_bubble));
        tags.add(SELECTOR_TAG_CHAT);

        items.add(new SelectorDialogItem(getString(R.string.show_contact), R.drawable.ic_person_outline));
        tags.add(SELECTOR_TAG_SHOW_CONTACT);

        if (!ConfigUtils.isOnPremBuild()) {
            if (
                !contactModel.isLinkedToAndroidContact() &&
                    TestUtil.isEmptyOrNull(contactModel.getFirstName()) &&
                    TestUtil.isEmptyOrNull(contactModel.getLastName()) &&
                    contactModel.verificationLevel == VerificationLevel.UNVERIFIED
            ) {
                MessageReceiver messageReceiver = contactService.createReceiver(contactModel);
                if (messageReceiver.getMessagesCount() > 0) {
                    items.add(new SelectorDialogItem(getString(R.string.spam_report), R.drawable.ic_outline_report_24));
                    tags.add(SELECTOR_TAG_REPORT_SPAM);
                }
            }
        }

        if (serviceManager.getBlockedIdentitiesService().isBlocked(contactModel.getIdentity())) {
            items.add(new SelectorDialogItem(getString(R.string.unblock_contact), R.drawable.ic_block));
        } else {
            items.add(new SelectorDialogItem(getString(R.string.block_contact), R.drawable.ic_block));
        }
        tags.add(SELECTOR_TAG_BLOCK);

        items.add(new SelectorDialogItem(getString(R.string.delete_contact_action), R.drawable.ic_delete_outline));
        tags.add(SELECTOR_TAG_DELETE);

        SelectorDialog selectorDialog = SelectorDialog.newInstance(getString(R.string.last_added_contact), items, tags, getString(R.string.cancel));
        selectorDialog.setData(contactModel);
        selectorDialog.setTargetFragment(this, 0);
        selectorDialog.show(getParentFragmentManager(), DIALOG_TAG_RECENTLY_ADDED_SELECTOR);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_REFRESH_CONTACTS:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    this.onRefresh();
                } else if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                    ConfigUtils.showPermissionRationale(getContext(), getView(), R.string.permission_contacts_sync_required);
                }
        }
    }

    private void setupListeners() {
        logger.debug("setup listeners");

        //set listeners
        ListenerManager.contactListeners.add(this.contactListener);
        ListenerManager.contactSettingsListeners.add(this.contactSettingsListener);
        ListenerManager.synchronizeContactsListeners.add(this.synchronizeContactsListener);
        ListenerManager.preferenceListeners.add(this.preferenceListener);
    }

    private void removeListeners() {
        logger.debug("remove listeners");

        ListenerManager.contactListeners.remove(this.contactListener);
        ListenerManager.contactSettingsListeners.remove(this.contactSettingsListener);
        ListenerManager.synchronizeContactsListeners.remove(this.synchronizeContactsListener);
        ListenerManager.preferenceListeners.remove(this.preferenceListener);
    }

    private boolean showExcludeFromContactSync(Set<ContactModel> contacts) {
        if (preferenceService == null || !preferenceService.isSyncContacts()) {
            return false;
        }

        for (ContactModel contactModel : contacts) {
            if (contactModel.isLinkedToAndroidContact()) {
                return true;
            }
        }

        return false;
    }

    @SuppressLint("StringFormatInvalid")
    private void deleteContacts(@NonNull Set<ContactModel> contacts) {
        int contactsSelectedToDelete = contacts.size();
        final String deleteContactTitle = getString(contactsSelectedToDelete > 1 ? R.string.delete_multiple_contact_action : R.string.delete_contact_action);
        final String message = String.format(ConfigUtils.getSafeQuantityString(ThreemaApplication.getAppContext(), R.plurals.really_delete_contacts_message, contactsSelectedToDelete, contactsSelectedToDelete), contactListAdapter.getCheckedItemCount());

        ThreemaDialogFragment dialog;
        if (showExcludeFromContactSync(contacts)) {
            dialog = TextWithCheckboxDialog.newInstance(
                deleteContactTitle,
                R.drawable.ic_contact,
                message,
                R.string.exclude_contact,
                R.string.ok,
                R.string.cancel);
        } else {
            logger.info("Showing delete contact dialog");
            dialog = GenericAlertDialog.newInstance(
                deleteContactTitle,
                message,
                R.string.ok,
                R.string.cancel);
        }
        dialog.setTargetFragment(this, 0);
        dialog.setData(contacts);
        dialog.show(getFragmentManager(), DIALOG_TAG_REALLY_DELETE_CONTACTS);
    }

    private void reallyDeleteContacts(@NonNull Set<ContactModel> contactModels, boolean excludeFromSync) throws ThreemaException {
        Set<String> identities = contactModels.stream()
            .map(Contact::getIdentity)
            .collect(Collectors.toSet());

        ContactSyncPolicy syncPolicy = excludeFromSync ? EXCLUDE : INCLUDE;

        DialogMarkContactAsDeletedBackgroundTask task = getDialogDeleteContactBackgroundTask(
            identities, syncPolicy
        );

        backgroundExecutor.execute(task);
    }

    @NonNull
    private DialogMarkContactAsDeletedBackgroundTask getDialogDeleteContactBackgroundTask(
        @NonNull Set<String> identities,
        @NonNull ContactSyncPolicy syncPolicy
    ) throws ThreemaException {
        DeleteContactServices deleteServices = new DeleteContactServices(
            serviceManager.getUserService(),
            contactService,
            serviceManager.getConversationService(),
            serviceManager.getRingtoneService(),
            serviceManager.getConversationCategoryService(),
            serviceManager.getProfilePicRecipientsService(),
            serviceManager.getWallpaperService(),
            serviceManager.getFileService(),
            serviceManager.getExcludedSyncIdentitiesService(),
            serviceManager.getDHSessionStore(),
            serviceManager.getNotificationService(),
            serviceManager.getDatabaseService()
        );

        return new DialogMarkContactAsDeletedBackgroundTask(
            getParentFragmentManager(),
            new WeakReference<>(getContext()),
            identities,
            serviceManager.getModelRepositories().getContacts(),
            deleteServices,
            syncPolicy,
            AndroidContactLinkPolicy.REMOVE_LINK
        ) {
            @Override
            protected void onFinished() {
                if (actionMode != null) {
                    actionMode.finish();
                }
            }
        };
    }

    @Override
    public void onSelected(String tag, String data) {
        if (!TestUtil.isEmptyOrNull(tag)) {
            sendInvite(tag);
        }
    }

    public void shareInvite() {
        final PackageManager packageManager = getContext().getPackageManager();
        if (packageManager == null) return;

        Intent messageIntent = new Intent(Intent.ACTION_SEND);
        messageIntent.setType(MimeUtil.MIME_TYPE_TEXT);
        @SuppressLint({"WrongConstant", "InlinedApi"}) final List<ResolveInfo> messageApps = packageManager.queryIntentActivities(messageIntent, PackageManager.MATCH_ALL);

        if (!messageApps.isEmpty()) {
            ArrayList<BottomSheetItem> items = new ArrayList<>();

            for (int i = 0; i < messageApps.size(); i++) {
                ResolveInfo resolveInfo = messageApps.get(i);
                if (resolveInfo != null) {
                    CharSequence label = resolveInfo.loadLabel(packageManager);
                    Drawable icon = resolveInfo.loadIcon(packageManager);

                    if (label != null && icon != null) {
                        Bitmap bitmap = BitmapUtil.getBitmapFromVectorDrawable(icon, null);
                        if (bitmap != null) {
                            items.add(new BottomSheetItem(bitmap, label.toString(), messageApps.get(i).activityInfo.packageName));
                        }
                    }
                }
            }

            BottomSheetGridDialog dialog = BottomSheetGridDialog.newInstance(R.string.invite_via, items);
            dialog.setTargetFragment(this, 0);
            dialog.show(getParentFragmentManager(), DIALOG_TAG_SHARE_WITH);
        }
    }

    private void sendInvite(String packageName) {
        // is this an SMS app? if it holds the SEND_SMS permission, it most probably is.
        boolean isShortMessage = ConfigUtils.checkManifestPermission(getContext(), packageName, "android.permission.SEND_SMS");

        if (packageName.contains("twitter")) {
            isShortMessage = true;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(MimeUtil.MIME_TYPE_TEXT);
        intent.setPackage(packageName);

        UserService userService = ThreemaApplication.getServiceManager().getUserService();

        if (isShortMessage) {
            /* short version */
            String messageBody = String.format(getString(R.string.invite_sms_body), getString(R.string.app_name), userService.getIdentity());
            intent.putExtra(Intent.EXTRA_TEXT, messageBody);
        } else {
            /* long version */
            String messageBody = String.format(getString(R.string.invite_email_body), getString(R.string.app_name), userService.getIdentity());
            intent.putExtra(Intent.EXTRA_SUBJECT, getResources().getString(R.string.invite_email_subject));
            intent.putExtra(Intent.EXTRA_TEXT, messageBody);
        }

        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), R.string.no_activity_for_mime_type, Toast.LENGTH_LONG).show();
            logger.error("Exception", e);
        }
    }

    public void onLogoClicked() {
        logger.info("Logo clicked, scrolling to top");
        if (this.listView != null) {
            // this stops the fling
            this.listView.smoothScrollBy(0, 0);
            this.listView.setSelection(0);
        }
    }

    /* selector dialog callbacks */

    @Override
    public void onClick(String tag, int which, Object data) {
        if (data == null) {
            return;
        }

        ContactModel contactModel = (ContactModel) data;

        switch (which) {
            case SELECTOR_TAG_CHAT:
                openConversationForIdentity(null, contactModel.getIdentity());
                break;
            case SELECTOR_TAG_SHOW_CONTACT:
                openContact(null, contactModel.getIdentity());
                break;
            case SELECTOR_TAG_REPORT_SPAM:
                logger.info("Showing report for spam dialog");
                TextWithCheckboxDialog sdialog = TextWithCheckboxDialog.newInstance(requireContext().getString(R.string.spam_report_dialog_title, NameUtil.getDisplayNameOrNickname(contactModel, true)), R.string.spam_report_dialog_explain,
                    R.string.spam_report_dialog_block_checkbox, R.string.spam_report_short, R.string.cancel);
                sdialog.setData(contactModel);
                sdialog.setTargetFragment(this, 0);
                sdialog.show(getParentFragmentManager(), DIALOG_TAG_REPORT_SPAM);
                break;
            case SELECTOR_TAG_BLOCK:
                logger.info("Block contact clicked");
                serviceManager.getBlockedIdentitiesService().toggleBlocked(contactModel.getIdentity(), getContext());
                break;
            case SELECTOR_TAG_DELETE:
                logger.info("Delete contact clicked");
                deleteContacts(Set.of(contactModel));
                break;
        }
    }

    /* callback from TextWithCheckboxDialog */
    @Override
    public void onYes(String tag, Object data, boolean checked) {
        switch (tag) {
            case DIALOG_TAG_REALLY_DELETE_CONTACTS:
                try {
                    logger.info("Contact deletion confirmed");
                    reallyDeleteContacts((Set<ContactModel>) data, checked);
                } catch (ThreemaException e) {
                    logger.error("Could not delete contacts", e);
                }
                break;
            case DIALOG_TAG_REPORT_SPAM:
                logger.info("Reporting contact for spam confirmed");
                ContactModel contactModel = (ContactModel) data;

                contactService.reportSpam(contactModel.getIdentity(),
                    unused -> {
                        if (isAdded()) {
                            Toast.makeText(getContext(), R.string.spam_successfully_reported, Toast.LENGTH_LONG).show();
                        }

                        final String spammerIdentity = contactModel.getIdentity();
                        if (checked) {
                            serviceManager.getBlockedIdentitiesService()
                                .blockIdentity(spammerIdentity, null);
                            serviceManager.getExcludedSyncIdentitiesService()
                                .excludeFromSync(spammerIdentity, TriggerSource.LOCAL);

                            try {
                                new EmptyOrDeleteConversationsAsyncTask(
                                    EmptyOrDeleteConversationsAsyncTask.Mode.DELETE,
                                    new MessageReceiver[]{contactService.createReceiver(contactModel)},
                                    serviceManager.getConversationService(),
                                    serviceManager.getDistributionListService(),
                                    serviceManager.getModelRepositories().getGroups(),
                                    serviceManager.getGroupFlowDispatcher(),
                                    serviceManager.getUserService().getIdentity(),
                                    null,
                                    null,
                                    () -> {
                                        ListenerManager.conversationListeners.handle(ConversationListener::onModifiedAll);
                                        ListenerManager.contactListeners.handle(listener -> listener.onModified(spammerIdentity));
                                    }).execute();
                            } catch (Exception e) {
                                logger.error("Unable to empty chat", e);
                            }
                        } else {
                            ListenerManager.contactListeners.handle(listener -> listener.onModified(spammerIdentity));
                        }
                    },
                    message -> {
                        if (isAdded()) {
                            Toast.makeText(getContext(), requireContext().getString(R.string.spam_error_reporting, message), Toast.LENGTH_LONG).show();
                        }
                    }
                );
                break;
            default:
                break;
        }
    }

    /**
     * Callbacks from GenericAlertDialog
     */
    @Override
    public void onYes(String tag, Object data) {
        switch (tag) {
            case DIALOG_TAG_REALLY_DELETE_CONTACTS:
                try {
                    reallyDeleteContacts((Set<ContactModel>) data, false);
                } catch (ThreemaException e) {
                    logger.error("Could not delete contacts", e);
                }
                break;
            default:
                break;
        }
    }

    private void exportContacts() {
        if (!requiredInstances()) {
            return;
        }

        try {
            String exportPath = contactExportImportService.exportContacts();
            if (exportPath != null) {
                File exportFile = new File(exportPath);
                Toast.makeText(getContext(), 
                    getString(R.string.export_contacts_success, exportFile.getName()), 
                    Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getContext(), 
                    getString(R.string.export_contacts_failed), 
                    Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            logger.error("Failed to export contacts", e);
            Toast.makeText(getContext(), 
                getString(R.string.export_contacts_error, e.getMessage()), 
                Toast.LENGTH_LONG).show();
        }
    }

    private void importContacts() {
        logger.info("importContacts() called");
        
        if (!requiredInstances()) {
            logger.warn("importContacts: requiredInstances() returned false");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        logger.info("Starting file picker for contact import");
        
        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT_CONTACTS);
            logger.info("File picker started successfully");
        } catch (android.content.ActivityNotFoundException e) {
            logger.error("No app found to handle file picker", e);
            Toast.makeText(getContext(), 
                getString(R.string.import_contacts_no_app), 
                Toast.LENGTH_SHORT).show();
        }
    }

    private void processContactImport(Uri uri) {
        logger.info("ContactsSectionFragment: processContactImport called with URI: " + uri.toString());
        
        try {
            String filePath = FileUtil.getRealPathFromURI(getActivity(), uri);
            logger.info("ContactsSectionFragment: Resolved file path: " + filePath);
            
            if (filePath != null) {
                logger.info("ContactsSectionFragment: Starting contact import from file: " + filePath);
                ContactExportImportService.ImportResult result = contactExportImportService.importContacts(filePath);
                logger.info("ContactsSectionFragment: Import completed - success: " + result.successCount + ", failed: " + result.failedCount);
                
                String message;
                if (result.isSuccess() && result.failedCount == 0) {
                    message = getString(R.string.import_contacts_success, result.successCount);
                } else if (result.isSuccess() || result.isPartialSuccess()) {
                    // Some contacts were imported but some failed
                    if (result.failedCount > 0) {
                        String baseMessage = getString(R.string.import_contacts_partial, 
                            result.successCount, result.totalContacts, result.failedCount);
                        if (result.errorMessage != null && result.errorMessage.contains("Failed to create")) {
                            message = baseMessage + "\n\n" + result.errorMessage;
                        } else {
                            message = baseMessage + (result.errorMessage != null ? "\n\n" + result.errorMessage : "");
                        }
                    } else {
                        message = getString(R.string.import_contacts_success, result.successCount);
                    }
                } else {
                    message = getString(R.string.import_contacts_failed, 
                        result.errorMessage != null ? result.errorMessage : "Unknown error");
                }
                
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                
                // Refresh the contact list if any contacts were imported
                if (result.successCount > 0) {
                    logger.info("ContactsSectionFragment: Refreshing contact list after successful import");
                    refreshContactListAfterImport();
                }
                
            } else {
                logger.error("ContactsSectionFragment: Could not resolve file path from URI: " + uri);
                Toast.makeText(getActivity(), "Could not access the selected file", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            logger.error("ContactsSectionFragment: Exception during contact import", e);
            Toast.makeText(getActivity(), "Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshContactListAfterImport() {
        logger.info("refreshContactListAfterImport() called");
        
        try {
            // Method 1: Force update through resume pause handler
            logger.info("Attempting to refresh via resumePauseHandler.runOnActive");
            if (resumePauseHandler != null) {
                resumePauseHandler.runOnActive(RUN_ON_ACTIVE_UPDATE_LIST, runIfActiveUpdateList);
                logger.info("Scheduled refresh via resumePauseHandler");
            } else {
                logger.warn("resumePauseHandler is null, cannot use runOnActive");
            }
            
            // Method 2: Direct call to updateList
            logger.info("Attempting direct updateList() call");
            updateList();
            
            // Method 3: Notify adapter directly if it exists
            if (contactListAdapter != null) {
                logger.info("Attempting to notify contactListAdapter directly");
                RuntimeUtil.runOnUiThread(() -> {
                    logger.info("Running on UI thread - notifying adapter of data change");
                    contactListAdapter.notifyDataSetChanged();
                });
            } else {
                logger.warn("contactListAdapter is null, cannot notify directly");
            }
            
            // Method 4: Send broadcast to trigger refresh
            logger.info("Sending contacts changed broadcast");
            Intent refreshIntent = new Intent(IntentDataUtil.ACTION_CONTACTS_CHANGED);
            LocalBroadcastManager.getInstance(getContext()).sendBroadcast(refreshIntent);
            logger.info("Broadcast sent successfully");
            
        } catch (Exception e) {
            logger.error("Exception during contact list refresh", e);
        }
        
        logger.info("refreshContactListAfterImport() completed");
    }
}
