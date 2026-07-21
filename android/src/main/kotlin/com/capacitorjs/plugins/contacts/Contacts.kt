package com.capacitorjs.plugins.contacts

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.util.Base64
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure business logic for reading, searching, saving and removing device
 * contacts via [ContactsContract]. Holds no Capacitor bridge types; the bridge
 * ([ContactsPlugin]) parses calls and delegates here.
 */
class Contacts(private val context: Context) {

    /** Marker value used by the legacy API to request every field. */
    private val allFields = "*"

    /**
     * The text columns `find` may match per MIME type. Restricting the
     * columns keeps the LIKE from string-matching numeric TYPE columns
     * (e.g. a filter of "2" matching every TYPE_MOBILE phone row).
     */
    private val searchableColumnsByMimeType = mapOf(
        StructuredName.CONTENT_ITEM_TYPE to listOf(
            StructuredName.DISPLAY_NAME, StructuredName.GIVEN_NAME, StructuredName.FAMILY_NAME,
            StructuredName.PREFIX, StructuredName.MIDDLE_NAME, StructuredName.SUFFIX
        ),
        Phone.CONTENT_ITEM_TYPE to listOf(Phone.NUMBER),
        Email.CONTENT_ITEM_TYPE to listOf(Email.ADDRESS),
        StructuredPostal.CONTENT_ITEM_TYPE to listOf(
            StructuredPostal.FORMATTED_ADDRESS, StructuredPostal.STREET, StructuredPostal.CITY,
            StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY
        ),
        Im.CONTENT_ITEM_TYPE to listOf(Im.DATA),
        Organization.CONTENT_ITEM_TYPE to listOf(
            Organization.COMPANY, Organization.TITLE, Organization.DEPARTMENT
        ),
        Website.CONTENT_ITEM_TYPE to listOf(Website.URL),
        Nickname.CONTENT_ITEM_TYPE to listOf(Nickname.NAME),
        Note.CONTENT_ITEM_TYPE to listOf(Note.NOTE),
        Event.CONTENT_ITEM_TYPE to listOf(Event.START_DATE)
    )

    /** Reads a string field, treating JSON null as absent (never "null"). */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    private fun JSONObject.stringOrEmpty(key: String): String = stringOrNull(key) ?: ""

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------

    /**
     * Searches the device contacts.
     *
     * @param fields fields to search against (`["*"]` for all).
     * @param filter case-insensitive substring to match; empty returns all.
     * @param multiple when false, at most one contact is returned.
     * @param desiredFields if non-empty, limits the fields populated on results.
     * @param hasPhoneNumber when true, only contacts with a phone number are kept.
     */
    fun search(
        fields: List<String>,
        filter: String,
        multiple: Boolean,
        desiredFields: List<String>,
        hasPhoneNumber: Boolean
    ): JSArray {
        val populate = desiredFields.toSet()
        val results = JSArray()
        for (contactId in collectMatchingContactIds(fields, filter, hasPhoneNumber)) {
            val contact = readContact(contactId, populate) ?: continue
            results.put(contact)
            if (!multiple) break
        }
        return results
    }

    /**
     * Returns the ids of all contacts matching [filter] on the requested
     * [fields], ordered by display name. Two provider queries total: one over
     * the Data table for field values, one over the Contacts table for
     * display-name matching, ordering and the [hasPhoneNumber] restriction.
     */
    private fun collectMatchingContactIds(
        fields: List<String>,
        filter: String,
        hasPhoneNumber: Boolean
    ): List<String> {
        val searchAll = fields.isEmpty() || fields.contains(allFields)
        val matchDisplayName = searchAll ||
            fields.any { it == "displayName" || it == "name" || it == "formatted" }
        val matchId = searchAll || fields.contains("id")
        val dataMatches = if (filter.isEmpty()) {
            emptySet()
        } else {
            queryDataMatches(fields, filter, searchAll, hasPhoneNumber)
        }

        val selection = if (hasPhoneNumber) "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1" else null
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            selection,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        ) ?: return emptyList()

        val needle = filter.lowercase(Locale.getDefault())
        val ids = ArrayList<String>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val matches = filter.isEmpty() ||
                    dataMatches.contains(id) ||
                    (matchId && id == filter) ||
                    (matchDisplayName &&
                        it.getString(1)?.lowercase(Locale.getDefault())?.contains(needle) == true)
                if (matches) ids.add(id)
            }
        }
        return ids
    }

    /** Single Data-table query for contacts whose field values contain [filter]. */
    private fun queryDataMatches(
        fields: List<String>,
        filter: String,
        searchAll: Boolean,
        hasPhoneNumber: Boolean
    ): Set<String> {
        val mimeTypes = if (searchAll) {
            searchableColumnsByMimeType.keys.toList()
        } else {
            fields.mapNotNull { fieldToMimeType(it) }.distinct()
        }
        if (mimeTypes.isEmpty()) return emptySet()

        // Escape LIKE wildcards so user input is matched literally.
        val escaped = filter.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val pattern = "%$escaped%"

        val clauses = StringBuilder("(")
        val args = ArrayList<String>()
        for (mime in mimeTypes) {
            val columns = searchableColumnsByMimeType[mime] ?: continue
            if (args.isNotEmpty()) clauses.append(" OR ")
            clauses.append("(${Data.MIMETYPE} = ? AND (")
            args.add(mime)
            clauses.append(columns.joinToString(" OR ") { "$it LIKE ? ESCAPE '\\'" })
            columns.forEach { _ -> args.add(pattern) }
            clauses.append("))")
        }
        if (args.isEmpty()) return emptySet()
        clauses.append(")")
        if (hasPhoneNumber) {
            clauses.append(" AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1")
        }

        val ids = HashSet<String>()
        context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID),
            clauses.toString(),
            args.toTypedArray(),
            null
        )?.use {
            while (it.moveToNext()) {
                it.getString(0)?.let(ids::add)
            }
        }
        return ids
    }

    // ---------------------------------------------------------------------
    // Read a single contact
    // ---------------------------------------------------------------------

    /** Returns the aggregated contact identified by [contactId], or null. */
    fun getContactById(contactId: String, desiredFields: Set<String> = emptySet()): JSObject? =
        readContact(contactId, desiredFields)

    /** Returns the aggregated contact backing the given raw-contact id, or null. */
    fun getContactByRawId(rawId: String, desiredFields: Set<String> = emptySet()): JSObject? {
        val cursor = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawId),
            null
        ) ?: return null
        cursor.use {
            if (it.moveToFirst()) {
                val contactId = it.getString(0) ?: return null
                return readContact(contactId, desiredFields)
            }
        }
        return null
    }

    private fun wants(field: String, desiredFields: Set<String>): Boolean =
        desiredFields.isEmpty() || desiredFields.contains(field)

    private fun readContact(contactId: String, desiredFields: Set<String>): JSObject? {
        val contact = JSObject()
        contact.put("id", contactId)
        if (desiredFields.isEmpty()) {
            rawIdForContact(contactId)?.let { contact.put("rawId", it) }
        }

        val phones = JSArray()
        val emails = JSArray()
        val addresses = JSArray()
        val ims = JSArray()
        val organizations = JSArray()
        val urls = JSArray()
        val photos = JSArray()
        val categories = JSArray()
        val name = JSObject()
        var hasName = false

        // Explicit projection: DATA1..DATA10 covers every field read below and
        // keeps the photo BLOB (DATA15) out of the cursor window.
        val cursor = context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(
                Data._ID, Data.MIMETYPE, Data.IS_SUPER_PRIMARY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5,
                Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10
            ),
            "${Data.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        ) ?: return null

        var fallbackDisplayName: String? = null
        cursor.use { c ->
            // Column indices are stable per cursor; resolve each name once.
            val colIdx = HashMap<String, Int>()
            fun col(nameOfColumn: String) = colIdx.getOrPut(nameOfColumn) { c.getColumnIndex(nameOfColumn) }
            fun str(nameOfColumn: String) = c.getString(col(nameOfColumn))
            fun int(nameOfColumn: String) = c.getInt(col(nameOfColumn))

            while (c.moveToNext()) {
                val mime = str(Data.MIMETYPE) ?: continue
                val dataId = str(Data._ID)
                val pref = int(Data.IS_SUPER_PRIMARY) == 1
                if (fallbackDisplayName == null) {
                    fallbackDisplayName = str(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                }
                when (mime) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        hasName = true
                        name.put("formatted", str(StructuredName.DISPLAY_NAME))
                        name.put("familyName", str(StructuredName.FAMILY_NAME))
                        name.put("givenName", str(StructuredName.GIVEN_NAME))
                        name.put("middleName", str(StructuredName.MIDDLE_NAME))
                        name.put("honorificPrefix", str(StructuredName.PREFIX))
                        name.put("honorificSuffix", str(StructuredName.SUFFIX))
                        contact.put("displayName", str(StructuredName.DISPLAY_NAME))
                    }

                    Phone.CONTENT_ITEM_TYPE -> phones.put(
                        field(
                            dataId,
                            str(Phone.NUMBER),
                            phoneLabel(int(Phone.TYPE), str(Phone.LABEL)),
                            pref
                        )
                    )

                    Email.CONTENT_ITEM_TYPE -> emails.put(
                        field(
                            dataId,
                            str(Email.ADDRESS),
                            emailLabel(int(Email.TYPE), str(Email.LABEL)),
                            pref
                        )
                    )

                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val address = JSObject()
                        address.put("id", dataId)
                        address.put("pref", pref)
                        address.put("type", addressLabel(int(StructuredPostal.TYPE), str(StructuredPostal.LABEL)))
                        address.put("formatted", str(StructuredPostal.FORMATTED_ADDRESS))
                        address.put("streetAddress", str(StructuredPostal.STREET))
                        address.put("locality", str(StructuredPostal.CITY))
                        address.put("region", str(StructuredPostal.REGION))
                        address.put("postalCode", str(StructuredPostal.POSTCODE))
                        address.put("country", str(StructuredPostal.COUNTRY))
                        addresses.put(address)
                    }

                    Im.CONTENT_ITEM_TYPE -> ims.put(
                        field(
                            dataId,
                            str(Im.DATA),
                            imLabel(int(Im.PROTOCOL), str(Im.CUSTOM_PROTOCOL)),
                            pref
                        )
                    )

                    Organization.CONTENT_ITEM_TYPE -> {
                        val org = JSObject()
                        org.put("id", dataId)
                        org.put("pref", pref)
                        org.put("type", organizationLabel(int(Organization.TYPE), str(Organization.LABEL)))
                        org.put("name", str(Organization.COMPANY))
                        org.put("department", str(Organization.DEPARTMENT))
                        org.put("title", str(Organization.TITLE))
                        organizations.put(org)
                    }

                    Website.CONTENT_ITEM_TYPE -> urls.put(
                        field(
                            dataId,
                            str(Website.URL),
                            websiteLabel(int(Website.TYPE), str(Website.LABEL)),
                            pref
                        )
                    )

                    Nickname.CONTENT_ITEM_TYPE ->
                        contact.put("nickname", str(Nickname.NAME))

                    Note.CONTENT_ITEM_TYPE ->
                        contact.put("note", str(Note.NOTE))

                    Event.CONTENT_ITEM_TYPE -> {
                        if (int(Event.TYPE) == Event.TYPE_BIRTHDAY) {
                            parseBirthday(str(Event.START_DATE))?.let { ms ->
                                contact.put("birthday", ms)
                            }
                        }
                    }

                    Photo.CONTENT_ITEM_TYPE -> {
                        if (wants("photos", desiredFields) && photoRowHasData(dataId)) {
                            val photoUri = Uri.withAppendedPath(
                                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId.toLong()),
                                ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
                            )
                            photos.put(field(dataId, photoUri.toString(), "url", pref))
                        }
                    }

                    GroupMembership.CONTENT_ITEM_TYPE -> {
                        if (wants("categories", desiredFields)) {
                            groupTitle(str(GroupMembership.GROUP_ROW_ID))?.let { title ->
                                categories.put(field(dataId, title, null, false))
                            }
                        }
                    }
                }
            }
        }

        if (!contact.has("displayName") && fallbackDisplayName != null) {
            contact.put("displayName", fallbackDisplayName)
        }
        if (hasName && wants("name", desiredFields)) contact.put("name", name)
        if (phones.length() > 0 && wants("phoneNumbers", desiredFields)) contact.put("phoneNumbers", phones)
        if (emails.length() > 0 && wants("emails", desiredFields)) contact.put("emails", emails)
        if (addresses.length() > 0 && wants("addresses", desiredFields)) contact.put("addresses", addresses)
        if (ims.length() > 0 && wants("ims", desiredFields)) contact.put("ims", ims)
        if (organizations.length() > 0 && wants("organizations", desiredFields)) contact.put("organizations", organizations)
        if (urls.length() > 0 && wants("urls", desiredFields)) contact.put("urls", urls)
        if (photos.length() > 0 && wants("photos", desiredFields)) contact.put("photos", photos)
        if (categories.length() > 0 && wants("categories", desiredFields)) contact.put("categories", categories)

        if (desiredFields.isNotEmpty()) {
            if (!desiredFields.contains("displayName")) contact.remove("displayName")
            if (!desiredFields.contains("nickname")) contact.remove("nickname")
            if (!desiredFields.contains("note")) contact.remove("note")
            if (!desiredFields.contains("birthday")) contact.remove("birthday")
        }
        return contact
    }

    private fun field(id: String?, value: String?, type: String?, pref: Boolean): JSObject {
        val f = JSObject()
        if (id != null) f.put("id", id)
        f.put("value", value ?: "")
        f.put("type", type ?: "other")
        f.put("pref", pref)
        return f
    }

    // Maps TYPE_* constants to fixed English labels ("home", "work", ...).
    // The platform's getTypeLabel returns text in the device language, so a
    // contact saved and re-read under different languages would not match.

    private fun phoneLabel(type: Int, custom: String?): String = when (type) {
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_MOBILE -> "mobile"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_FAX_WORK -> "work fax"
        Phone.TYPE_FAX_HOME -> "home fax"
        Phone.TYPE_PAGER -> "pager"
        Phone.TYPE_MAIN -> "main"
        Phone.TYPE_CALLBACK -> "callback"
        Phone.TYPE_CAR -> "car"
        Phone.TYPE_COMPANY_MAIN -> "company main"
        Phone.TYPE_OTHER_FAX -> "other fax"
        Phone.TYPE_RADIO -> "radio"
        Phone.TYPE_TELEX -> "telex"
        Phone.TYPE_TTY_TDD -> "tty tdd"
        Phone.TYPE_WORK_MOBILE -> "work mobile"
        Phone.TYPE_WORK_PAGER -> "work pager"
        Phone.TYPE_ASSISTANT -> "assistant"
        Phone.TYPE_MMS -> "mms"
        Phone.TYPE_ISDN -> "isdn"
        Phone.TYPE_CUSTOM -> custom?.lowercase(Locale.getDefault()) ?: "other"
        else -> "other"
    }

    private fun emailLabel(type: Int, custom: String?): String = when (type) {
        Email.TYPE_HOME -> "home"
        Email.TYPE_WORK -> "work"
        Email.TYPE_MOBILE -> "mobile"
        Email.TYPE_CUSTOM -> custom?.lowercase(Locale.getDefault()) ?: "other"
        else -> "other"
    }

    private fun addressLabel(type: Int, custom: String?): String = when (type) {
        StructuredPostal.TYPE_HOME -> "home"
        StructuredPostal.TYPE_WORK -> "work"
        StructuredPostal.TYPE_CUSTOM -> custom?.lowercase(Locale.getDefault()) ?: "other"
        else -> "other"
    }

    private fun organizationLabel(type: Int, custom: String?): String = when (type) {
        Organization.TYPE_WORK -> "work"
        Organization.TYPE_CUSTOM -> custom?.lowercase(Locale.getDefault()) ?: "other"
        else -> "other"
    }

    private fun imLabel(protocol: Int, custom: String?): String = when (protocol) {
        Im.PROTOCOL_AIM -> "aim"
        Im.PROTOCOL_MSN -> "msn"
        Im.PROTOCOL_YAHOO -> "yahoo"
        Im.PROTOCOL_SKYPE -> "skype"
        Im.PROTOCOL_QQ -> "qq"
        Im.PROTOCOL_GOOGLE_TALK -> "google talk"
        Im.PROTOCOL_ICQ -> "icq"
        Im.PROTOCOL_JABBER -> "jabber"
        Im.PROTOCOL_CUSTOM -> custom?.lowercase(Locale.getDefault()) ?: "other"
        else -> "other"
    }

    /** Website has no `getTypeLabel` helper, so map its TYPE_* constants manually. */
    private fun websiteLabel(type: Int, custom: String?): String = when (type) {
        Website.TYPE_HOMEPAGE -> "homepage"
        Website.TYPE_BLOG -> "blog"
        Website.TYPE_PROFILE -> "profile"
        Website.TYPE_HOME -> "home"
        Website.TYPE_WORK -> "work"
        Website.TYPE_FTP -> "ftp"
        Website.TYPE_CUSTOM -> custom?.lowercase(Locale.getDefault()) ?: "other"
        else -> "other"
    }

    private fun rawIdForContact(contactId: String): String? {
        val cursor = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        ) ?: return null
        cursor.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun groupTitle(groupRowId: String?): String? {
        if (groupRowId == null) return null
        val cursor = context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups._ID} = ?",
            arrayOf(groupRowId),
            null
        ) ?: return null
        cursor.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    /** A contact can have a photo row with no image in it; those must not
     *  produce a photo entry. */
    private fun photoRowHasData(dataId: String?): Boolean {
        if (dataId == null) return false
        return try {
            val uri = ContentUris.withAppendedId(Data.CONTENT_URI, dataId.toLong())
            context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun parseBirthday(raw: String?): Long? {
        if (raw.isNullOrEmpty()) return null
        val formats = arrayOf("yyyy-MM-dd", "--MM-dd", "yyyyMMdd")
        for (pattern in formats) {
            try {
                val date = SimpleDateFormat(pattern, Locale.US).parse(raw)
                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Save (insert or update)
    // ---------------------------------------------------------------------

    /**
     * Inserts a new contact or updates the existing one identified by
     * `contact.id`. Returns the affected raw-contact id.
     *
     * @throws ContactsException on failure.
     */
    fun save(contact: JSONObject): String {
        val existingId = contact.stringOrNull("id")?.takeIf { it.isNotEmpty() }
        return if (existingId == null) insert(contact) else update(existingId, contact)
    }

    private fun insert(contact: JSONObject): String {
        val ops = ArrayList<ContentProviderOperation>()
        val rawIndex = 0
        // No ACCOUNT_TYPE/ACCOUNT_NAME values: absent columns let the provider
        // pick the user's default account. Explicit nulls request a *local*
        // contact, which Android 16+ rejects when the default account is a
        // cloud account ("Cannot add contacts to local or SIM accounts").
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
        )
        appendDataInserts(ops, rawIndex, contact)

        try {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawUri = results[0].uri ?: throw ContactsException(ContactsError.UNKNOWN)
            return ContentUris.parseId(rawUri).toString()
        } catch (e: ContactsException) {
            throw e
        } catch (e: Exception) {
            throw ContactsException(ContactsError.IO_ERROR, e)
        }
    }

    private fun update(contactId: String, contact: JSONObject): String {
        val rawId = rawIdForContact(contactId)
            ?: throw ContactsException(ContactsError.INVALID_ARGUMENT)
        val ops = ArrayList<ContentProviderOperation>()

        // Replace only the fields present in the payload; absent fields are
        // left untouched.
        val mimeTypeByWireKey = mapOf(
            "name" to StructuredName.CONTENT_ITEM_TYPE,
            "phoneNumbers" to Phone.CONTENT_ITEM_TYPE,
            "emails" to Email.CONTENT_ITEM_TYPE,
            "addresses" to StructuredPostal.CONTENT_ITEM_TYPE,
            "ims" to Im.CONTENT_ITEM_TYPE,
            "organizations" to Organization.CONTENT_ITEM_TYPE,
            "urls" to Website.CONTENT_ITEM_TYPE,
            "nickname" to Nickname.CONTENT_ITEM_TYPE,
            "note" to Note.CONTENT_ITEM_TYPE,
            "birthday" to Event.CONTENT_ITEM_TYPE,
            "photos" to Photo.CONTENT_ITEM_TYPE
        )
        for ((key, mime) in mimeTypeByWireKey) {
            if (!contact.has(key) || contact.isNull(key)) continue
            // Only the birthday is managed among events; never delete the
            // contact's anniversaries or other custom dates.
            var selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
            if (key == "birthday") {
                selection += " AND ${Event.TYPE} = ${Event.TYPE_BIRTHDAY}"
            }
            ops.add(
                ContentProviderOperation.newDelete(Data.CONTENT_URI)
                    .withSelection(selection, arrayOf(rawId, mime))
                    .build()
            )
        }
        appendDataInserts(ops, null, contact, rawId)

        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            return rawId
        } catch (e: Exception) {
            throw ContactsException(ContactsError.IO_ERROR, e)
        }
    }

    /**
     * Appends the data-row inserts for every field of [contact]. When
     * [rawIndex] is non-null the rows back-reference an as-yet-uninserted raw
     * contact (insert flow); otherwise they reference the existing [rawId]
     * (update flow).
     */
    private fun appendDataInserts(
        ops: ArrayList<ContentProviderOperation>,
        rawIndex: Int?,
        contact: JSONObject,
        rawId: String? = null
    ) {
        fun newInsert(): ContentProviderOperation.Builder {
            val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            if (rawIndex != null) {
                builder.withValueBackReference(Data.RAW_CONTACT_ID, rawIndex)
            } else {
                builder.withValue(Data.RAW_CONTACT_ID, rawId)
            }
            return builder
        }

        // Sets the row's TYPE column, adding LABEL only for custom types.
        fun ContentProviderOperation.Builder.withType(
            typed: TypedLabel,
            typeColumn: String,
            labelColumn: String
        ): ContentProviderOperation.Builder {
            withValue(typeColumn, typed.type)
            if (typed.label != null) withValue(labelColumn, typed.label)
            return this
        }

        contact.optJSONObject("name")?.let { name ->
            ops.add(
                newInsert()
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, name.stringOrNull("formatted") ?: contact.stringOrEmpty("displayName"))
                    .withValue(StructuredName.FAMILY_NAME, name.stringOrEmpty("familyName"))
                    .withValue(StructuredName.GIVEN_NAME, name.stringOrEmpty("givenName"))
                    .withValue(StructuredName.MIDDLE_NAME, name.stringOrEmpty("middleName"))
                    .withValue(StructuredName.PREFIX, name.stringOrEmpty("honorificPrefix"))
                    .withValue(StructuredName.SUFFIX, name.stringOrEmpty("honorificSuffix"))
                    .build()
            )
        }

        forEachField(contact, "phoneNumbers") { value, type, pref ->
            newInsert()
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, value)
                .withType(phoneType(type), Phone.TYPE, Phone.LABEL)
                .withValue(Data.IS_SUPER_PRIMARY, if (pref) 1 else 0)
                .build()
        }.forEach { ops.add(it) }

        forEachField(contact, "emails") { value, type, pref ->
            newInsert()
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, value)
                .withType(emailType(type), Email.TYPE, Email.LABEL)
                .withValue(Data.IS_SUPER_PRIMARY, if (pref) 1 else 0)
                .build()
        }.forEach { ops.add(it) }

        forEachField(contact, "ims") { value, type, pref ->
            newInsert()
                .withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
                .withValue(Im.DATA, value)
                .withType(imProtocol(type), Im.PROTOCOL, Im.CUSTOM_PROTOCOL)
                .withValue(Data.IS_SUPER_PRIMARY, if (pref) 1 else 0)
                .build()
        }.forEach { ops.add(it) }

        forEachField(contact, "urls") { value, type, pref ->
            newInsert()
                .withValue(Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
                .withValue(Website.URL, value)
                .withType(websiteType(type), Website.TYPE, Website.LABEL)
                .withValue(Data.IS_SUPER_PRIMARY, if (pref) 1 else 0)
                .build()
        }.forEach { ops.add(it) }

        contact.optJSONArray("addresses")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                        .withType(addressType(a.stringOrEmpty("type")), StructuredPostal.TYPE, StructuredPostal.LABEL)
                        .withValue(StructuredPostal.FORMATTED_ADDRESS, a.stringOrEmpty("formatted"))
                        .withValue(StructuredPostal.STREET, a.stringOrEmpty("streetAddress"))
                        .withValue(StructuredPostal.CITY, a.stringOrEmpty("locality"))
                        .withValue(StructuredPostal.REGION, a.stringOrEmpty("region"))
                        .withValue(StructuredPostal.POSTCODE, a.stringOrEmpty("postalCode"))
                        .withValue(StructuredPostal.COUNTRY, a.stringOrEmpty("country"))
                        .withValue(Data.IS_SUPER_PRIMARY, if (a.optBoolean("pref")) 1 else 0)
                        .build()
                )
            }
        }

        contact.optJSONArray("organizations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                        .withType(organizationType(o.stringOrEmpty("type")), Organization.TYPE, Organization.LABEL)
                        .withValue(Organization.COMPANY, o.stringOrEmpty("name"))
                        .withValue(Organization.DEPARTMENT, o.stringOrEmpty("department"))
                        .withValue(Organization.TITLE, o.stringOrEmpty("title"))
                        .withValue(Data.IS_SUPER_PRIMARY, if (o.optBoolean("pref")) 1 else 0)
                        .build()
                )
            }
        }

        contact.stringOrNull("nickname")?.takeIf { it.isNotEmpty() }?.let {
            ops.add(
                newInsert()
                    .withValue(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                    .withValue(Nickname.NAME, it)
                    .build()
            )
        }

        contact.stringOrNull("note")?.takeIf { it.isNotEmpty() }?.let {
            ops.add(
                newInsert()
                    .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                    .withValue(Note.NOTE, it)
                    .build()
            )
        }

        if (contact.opt("birthday") is Number) {
            val ms = contact.optLong("birthday")
            val formatted = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
            ops.add(
                newInsert()
                    .withValue(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                    .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                    .withValue(Event.START_DATE, formatted)
                    .build()
            )
        }

        contact.optJSONArray("photos")?.let { arr ->
            photoBytes(arr.optJSONObject(0))?.let { bytes ->
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                        .withValue(Photo.PHOTO, bytes)
                        .build()
                )
            }
        }
    }

    /**
     * Decodes the first photo entry into image bytes: `base64` values are
     * decoded directly; `url` values are read from local `file://` /
     * `content://` URIs.
     */
    private fun photoBytes(photo: JSONObject?): ByteArray? {
        val value = photo?.stringOrNull("value")
        if (value.isNullOrEmpty()) return null
        return try {
            if ((photo.stringOrNull("type") ?: "url").lowercase(Locale.getDefault()) == "base64") {
                Base64.decode(value, Base64.DEFAULT)
            } else {
                context.contentResolver.openInputStream(Uri.parse(value))?.use { it.readBytes() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun forEachField(
        contact: JSONObject,
        key: String,
        build: (value: String, type: String, pref: Boolean) -> ContentProviderOperation
    ): List<ContentProviderOperation> {
        val arr = contact.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<ContentProviderOperation>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val value = f.stringOrNull("value")
            if (value.isNullOrEmpty()) continue
            out.add(build(value, f.stringOrNull("type") ?: "other", f.optBoolean("pref")))
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Remove
    // ---------------------------------------------------------------------

    /**
     * Removes the aggregated contact identified by [contactId]. Returns false
     * when no contact has that id.
     */
    fun remove(contactId: String): Boolean {
        val id = contactId.toLongOrNull() ?: return false
        val uri: Uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
        return context.contentResolver.delete(uri, null, null) > 0
    }

    // ---------------------------------------------------------------------
    // Wire type <-> provider TYPE_* constant mapping
    // ---------------------------------------------------------------------

    /** A provider TYPE_* value plus the custom label when TYPE_CUSTOM. */
    private data class TypedLabel(val type: Int, val label: String? = null)

    private fun phoneType(type: String): TypedLabel = when (type.lowercase(Locale.getDefault())) {
        "home" -> TypedLabel(Phone.TYPE_HOME)
        "mobile", "cell" -> TypedLabel(Phone.TYPE_MOBILE)
        "work" -> TypedLabel(Phone.TYPE_WORK)
        "work fax" -> TypedLabel(Phone.TYPE_FAX_WORK)
        "home fax" -> TypedLabel(Phone.TYPE_FAX_HOME)
        "fax" -> TypedLabel(Phone.TYPE_FAX_WORK)
        "pager" -> TypedLabel(Phone.TYPE_PAGER)
        "main" -> TypedLabel(Phone.TYPE_MAIN)
        "callback" -> TypedLabel(Phone.TYPE_CALLBACK)
        "car" -> TypedLabel(Phone.TYPE_CAR)
        "company main" -> TypedLabel(Phone.TYPE_COMPANY_MAIN)
        "other fax" -> TypedLabel(Phone.TYPE_OTHER_FAX)
        "radio" -> TypedLabel(Phone.TYPE_RADIO)
        "telex" -> TypedLabel(Phone.TYPE_TELEX)
        "tty tdd" -> TypedLabel(Phone.TYPE_TTY_TDD)
        "work mobile" -> TypedLabel(Phone.TYPE_WORK_MOBILE)
        "work pager" -> TypedLabel(Phone.TYPE_WORK_PAGER)
        "assistant" -> TypedLabel(Phone.TYPE_ASSISTANT)
        "mms" -> TypedLabel(Phone.TYPE_MMS)
        "isdn" -> TypedLabel(Phone.TYPE_ISDN)
        "other" -> TypedLabel(Phone.TYPE_OTHER)
        else -> TypedLabel(Phone.TYPE_CUSTOM, type)
    }

    private fun emailType(type: String): TypedLabel = when (type.lowercase(Locale.getDefault())) {
        "home" -> TypedLabel(Email.TYPE_HOME)
        "work" -> TypedLabel(Email.TYPE_WORK)
        "mobile" -> TypedLabel(Email.TYPE_MOBILE)
        "other" -> TypedLabel(Email.TYPE_OTHER)
        else -> TypedLabel(Email.TYPE_CUSTOM, type)
    }

    private fun addressType(type: String): TypedLabel = when (type.lowercase(Locale.getDefault())) {
        "home" -> TypedLabel(StructuredPostal.TYPE_HOME)
        "work" -> TypedLabel(StructuredPostal.TYPE_WORK)
        "other" -> TypedLabel(StructuredPostal.TYPE_OTHER)
        else -> TypedLabel(StructuredPostal.TYPE_CUSTOM, type)
    }

    private fun organizationType(type: String): TypedLabel = when (type.lowercase(Locale.getDefault())) {
        "work" -> TypedLabel(Organization.TYPE_WORK)
        "other" -> TypedLabel(Organization.TYPE_OTHER)
        else -> TypedLabel(Organization.TYPE_CUSTOM, type)
    }

    private fun websiteType(type: String): TypedLabel = when (type.lowercase(Locale.getDefault())) {
        "homepage" -> TypedLabel(Website.TYPE_HOMEPAGE)
        "blog" -> TypedLabel(Website.TYPE_BLOG)
        "profile" -> TypedLabel(Website.TYPE_PROFILE)
        "home" -> TypedLabel(Website.TYPE_HOME)
        "work" -> TypedLabel(Website.TYPE_WORK)
        "ftp" -> TypedLabel(Website.TYPE_FTP)
        "other" -> TypedLabel(Website.TYPE_OTHER)
        else -> TypedLabel(Website.TYPE_CUSTOM, type)
    }

    private fun imProtocol(type: String): TypedLabel = when (type.lowercase(Locale.getDefault())) {
        "aim" -> TypedLabel(Im.PROTOCOL_AIM)
        "msn" -> TypedLabel(Im.PROTOCOL_MSN)
        "yahoo" -> TypedLabel(Im.PROTOCOL_YAHOO)
        "skype" -> TypedLabel(Im.PROTOCOL_SKYPE)
        "qq" -> TypedLabel(Im.PROTOCOL_QQ)
        "google talk", "gtalk" -> TypedLabel(Im.PROTOCOL_GOOGLE_TALK)
        "icq" -> TypedLabel(Im.PROTOCOL_ICQ)
        "jabber" -> TypedLabel(Im.PROTOCOL_JABBER)
        else -> TypedLabel(Im.PROTOCOL_CUSTOM, type)
    }

    // ---------------------------------------------------------------------
    // Field -> mimetype mapping (used for targeted filter searches)
    // ---------------------------------------------------------------------

    private fun fieldToMimeType(field: String): String? = when (field) {
        "displayName", "name", "familyName", "givenName", "middleName",
        "honorificPrefix", "honorificSuffix", "formatted" -> StructuredName.CONTENT_ITEM_TYPE
        "phoneNumbers" -> Phone.CONTENT_ITEM_TYPE
        "emails" -> Email.CONTENT_ITEM_TYPE
        "addresses", "streetAddress", "locality", "region", "postalCode", "country" -> StructuredPostal.CONTENT_ITEM_TYPE
        "ims" -> Im.CONTENT_ITEM_TYPE
        "organizations", "department", "title" -> Organization.CONTENT_ITEM_TYPE
        "urls" -> Website.CONTENT_ITEM_TYPE
        "nickname" -> Nickname.CONTENT_ITEM_TYPE
        "note" -> Note.CONTENT_ITEM_TYPE
        "birthday" -> Event.CONTENT_ITEM_TYPE
        else -> null
    }
}
