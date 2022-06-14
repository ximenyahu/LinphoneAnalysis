/*
 * Copyright (c) 2010-2021 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.contact

import android.content.ContentUris
import android.database.Cursor
import android.database.StaleDataException
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Patterns
import androidx.lifecycle.lifecycleScope
import androidx.loader.app.LoaderManager
import androidx.loader.content.CursorLoader
import androidx.loader.content.Loader
import java.lang.Exception
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.*
import org.linphone.core.tools.Log
import org.linphone.utils.PhoneNumberUtils

class ContactLoader : LoaderManager.LoaderCallbacks<Cursor> {
    companion object {
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.LOOKUP_KEY,
            "data1", // Company, Phone or SIP Address
            "data2", // ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.SipAddress.TYPE
            "data3", // ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, ContactsContract.CommonDataKinds.Phone.LABEL, ContactsContract.CommonDataKinds.SipAddress.LABEL
            "data4"
        )
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
        Log.i("[Contacts Loader] Loader created")
        coreContext.contactsManager.fetchInProgress.value = true

        val selection = if (corePreferences.fetchContactsFromDefaultDirectory) {
            ContactsContract.Data.IN_DEFAULT_DIRECTORY + " == 1"
        } else {
            null
        }

        return CursorLoader(
            coreContext.context,
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            null,
            ContactsContract.Data.CONTACT_ID + " ASC"
        )
    }

    override fun onLoadFinished(loader: Loader<Cursor>, cursor: Cursor?) {
        if (cursor == null) {
            Log.e("[Contacts Loader] Cursor is null!")
            return
        }
        Log.i("[Contacts Loader] Load finished, found ${cursor.count} entries in cursor")

        val core = coreContext.core
        val linphoneMime = loader.context.getString(R.string.linphone_address_mime_type)

        if (core.globalState == GlobalState.Shutdown || core.globalState == GlobalState.Off) {
            Log.w("[Contacts Loader] Core is being stopped or already destroyed, abort")
            return
        }

        coreContext.lifecycleScope.launch {
            val friends = HashMap<String, Friend>()

            withContext(Dispatchers.IO) {
                try {
                    // Cursor can be null now that we are on a different dispatcher according to Crashlytics
                    val friendsPhoneNumbers = arrayListOf<String>()
                    val friendsAddresses = arrayListOf<Address>()
                    var previousId = ""
                    while (cursor != null && !cursor.isClosed && cursor.moveToNext()) {
                        try {
                            val id: String =
                                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID))
                            val displayName: String? =
                                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME_PRIMARY))
                            val mime: String? =
                                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE))
                            val data1: String? =
                                cursor.getString(cursor.getColumnIndexOrThrow("data1"))
                            val data2: String? =
                                cursor.getString(cursor.getColumnIndexOrThrow("data2"))
                            val data3: String? =
                                cursor.getString(cursor.getColumnIndexOrThrow("data3"))
                            val data4: String? =
                                cursor.getString(cursor.getColumnIndexOrThrow("data4"))
                            val starred =
                                cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)) == 1
                            val lookupKey =
                                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))

                            if (previousId.isEmpty() || previousId != id) {
                                friendsPhoneNumbers.clear()
                                friendsAddresses.clear()
                                previousId = id
                            }

                            val friend = friends[id] ?: core.createFriend()
                            friend.refKey = id
                            if (friend.name.isNullOrEmpty()) {
                                friend.name = displayName
                                friend.photo = Uri.withAppendedPath(
                                    ContentUris.withAppendedId(
                                        ContactsContract.Contacts.CONTENT_URI,
                                        id.toLong()
                                    ),
                                    ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
                                ).toString()
                                friend.starred = starred
                                friend.nativeUri =
                                    "${ContactsContract.Contacts.CONTENT_LOOKUP_URI}/$lookupKey"

                                // Disable short term presence
                                friend.isSubscribesEnabled = false
                                friend.incSubscribePolicy = SubscribePolicy.SPDeny
                            }

                            when (mime) {
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                                    val label = PhoneNumberUtils.addressBookLabelTypeToVcardParamString(
                                        data2?.toInt() ?: ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM,
                                        data3
                                    )

                                    val number =
                                        if (corePreferences.preferNormalizedPhoneNumbersFromAddressBook ||
                                            data1.isNullOrEmpty() ||
                                            !Patterns.PHONE.matcher(data1).matches()
                                        ) {
                                            data4 ?: data1
                                        } else {
                                            data1
                                        }

                                    if (number != null) {
                                        if (
                                            friendsPhoneNumbers.find {
                                                PhoneNumberUtils.arePhoneNumberWeakEqual(it, number)
                                            } == null
                                        ) {
                                            val phoneNumber = Factory.instance()
                                                .createFriendPhoneNumber(number, label)
                                            friend.addPhoneNumberWithLabel(phoneNumber)
                                            friendsPhoneNumbers.add(number)
                                        }
                                    }
                                }
                                linphoneMime, ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE -> {
                                    if (data1 == null) continue
                                    val address = core.interpretUrl(data1) ?: continue
                                    if (
                                        friendsAddresses.find {
                                            it.weakEqual(address)
                                        } == null
                                    ) {
                                        friend.addAddress(address)
                                        friendsAddresses.add(address)
                                    }
                                }
                                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                                    if (data1 == null) continue
                                    friend.organization = data1
                                }
                                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                                    if (data2 == null && data3 == null) continue
                                    val vCard = friend.vcard
                                    vCard?.givenName = data2
                                    vCard?.familyName = data3
                                }
                            }

                            friends[id] = friend
                        } catch (e: Exception) {
                            Log.e("[Contacts Loader] Exception: $e")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (core.globalState == GlobalState.Shutdown || core.globalState == GlobalState.Off) {
                            Log.w("[Contacts Loader] Core is being stopped or already destroyed, abort")
                        } else {
                            Log.i("[Contacts Loader] ${friends.size} friends created")
                            val contactId = coreContext.contactsManager.contactIdToWatchFor
                            if (contactId.isNotEmpty()) {
                                val friend = friends[contactId]
                                Log.i("[Contacts Loader] Manager was asked to monitor contact id $contactId")
                                if (friend != null) {
                                    Log.i("[Contacts Loader] Found new contact matching id $contactId, notifying listeners")
                                    coreContext.contactsManager.notifyListeners(friend)
                                }
                            }

                            val fl = core.defaultFriendList ?: core.createFriendList()
                            for (friend in fl.friends) {
                                fl.removeFriend(friend)
                            }

                            if (fl != core.defaultFriendList) core.addFriendList(fl)

                            val friendsList = friends.values
                            for (friend in friendsList) {
                                fl.addLocalFriend(friend)
                            }
                            Log.i("[Contacts Loader] Friends added")

                            fl.updateSubscriptions()
                            Log.i("[Contacts Loader] Subscription(s) updated")

                            coreContext.contactsManager.fetchFinished()
                        }
                    }
                } catch (sde: StaleDataException) {
                    Log.e("[Contacts Loader] State Data Exception: $sde")
                } catch (ise: IllegalStateException) {
                    Log.e("[Contacts Loader] Illegal State Exception: $ise")
                } catch (e: Exception) {
                    Log.e("[Contacts Loader] Exception: $e")
                } finally {
                    cancel()
                }
            }
        }
    }

    override fun onLoaderReset(loader: Loader<Cursor>) {
        Log.i("[Contacts Loader] Loader reset")
    }
}
