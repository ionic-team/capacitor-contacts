package com.capacitorjs.plugins.contacts

import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal

/** The single data kind a `pickContactField` call lets the user pick. */
enum class ContactPickableField(val key: String, val contentUri: Uri) {
    PHONE("phone", Phone.CONTENT_URI),
    EMAIL("email", Email.CONTENT_URI),
    ADDRESS("address", StructuredPostal.CONTENT_URI);

    companion object {
        fun fromKey(key: String?): ContactPickableField? = values().firstOrNull { it.key == key }
    }
}
