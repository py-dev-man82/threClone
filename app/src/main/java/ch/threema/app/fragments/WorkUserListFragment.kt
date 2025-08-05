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

package ch.threema.app.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.TextView
import ch.threema.app.R
import ch.threema.app.ThreemaApplication
import ch.threema.app.activities.DirectoryActivity
import ch.threema.app.adapters.UserListAdapter
import ch.threema.app.services.ContactService
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.TestUtil
import ch.threema.app.utils.logScreenVisibility
import ch.threema.base.utils.LoggingUtil
import ch.threema.domain.models.IdentityState
import ch.threema.storage.models.ContactModel
import com.bumptech.glide.Glide

private val logger = LoggingUtil.getThreemaLogger("WorkUserListFragment")

class WorkUserListFragment : RecipientListFragment() {
    init {
        logScreenVisibility(logger)
    }

    override fun isMultiSelectAllowed(): Boolean = multiSelect || multiSelectIdentity

    override fun getBundleName(): String = "WorkerUserListState"

    override fun getEmptyText(): Int = R.string.no_matching_work_contacts

    override fun getAddIcon(): Int = 0

    override fun getAddText(): Int = 0

    override fun getAddIntent(): Intent? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)

        if (ConfigUtils.isWorkRestricted() && !multiSelect && view != null && ConfigUtils.isWorkDirectoryEnabled()) {
            val listView = view.findViewById<ListView>(android.R.id.list)
            val workOrganizationName: String = preferenceService.workOrganization.getName()

            val header: RelativeLayout = layoutInflater.inflate(
                R.layout.item_user_list_directory_header,
                listView,
                false,
            ) as RelativeLayout
            header.findViewById<TextView>(R.id.name).text =
                if (TestUtil.isEmptyOrNull(workOrganizationName)) {
                    getString(R.string.work_directory_title)
                } else {
                    workOrganizationName
                }
            header.findViewById<ImageView>(R.id.avatar).setImageResource(R.drawable.ic_business)
            header.setOnClickListener { _ ->
                val intent = Intent(context, DirectoryActivity::class.java).apply {
                    putExtra(DirectoryActivity.EXTRA_ANIMATE_OUT, true)
                }
                startActivity(intent)
                if (getActivity() != null) {
                    requireActivity().overridePendingTransition(R.anim.slide_in_right_short, R.anim.slide_out_left_short)
                }
            }

            listView.addHeaderView(header, null, false)
        }
        return view
    }

    @SuppressLint("StaticFieldLeak")
    override fun createListAdapter(checkedItemPositions: ArrayList<Int>?) {
        @Suppress("DEPRECATION")
        object : AsyncTask<Void?, Void?, List<ContactModel>>() {
            @Deprecated("Deprecated in Java")
            override fun doInBackground(vararg params: Void?): List<ContactModel> =
                contactService.find(
                    object : ContactService.Filter {
                        override fun states(): Array<IdentityState> =
                            if (preferenceService.showInactiveContacts()) {
                                arrayOf(IdentityState.ACTIVE, IdentityState.INACTIVE)
                            } else {
                                arrayOf(IdentityState.ACTIVE)
                            }

                        override fun requiredFeature(): Long? = null

                        override fun fetchMissingFeatureLevel(): Boolean = false

                        override fun includeMyself(): Boolean = false

                        override fun includeHidden(): Boolean = false

                        override fun onlyWithReceiptSettings(): Boolean = false
                    },
                ).filter { contactModel ->
                    contactModel.isWorkVerified
                }

            @Deprecated("Deprecated in Java")
            override fun onPostExecute(contactModels: List<ContactModel>) {
                if (conversationCategoryService == null) {
                    logger.error("Conversation category service is null")
                    return
                }
                adapter = UserListAdapter(
                    activity,
                    contactModels,
                    null,
                    checkedItemPositions,
                    contactService,
                    blockedIdentitiesService,
                    conversationCategoryService,
                    preferenceService,
                    this@WorkUserListFragment,
                    Glide.with(ThreemaApplication.getAppContext()),
                    true,
                )
                setListAdapter(adapter)
                if (listInstanceState != null) {
                    if (isAdded && view != null && getActivity() != null) {
                        listView.onRestoreInstanceState(listInstanceState)
                    }
                    listInstanceState = null
                    restoreCheckedItems(checkedItemPositions)
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }
}
