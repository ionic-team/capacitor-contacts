package com.capacitorjs.plugins.contacts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONArray
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Capacitor bridge for the Contacts plugin.
 *
 * Permission model is **implicit**: there are no `checkPermissions` /
 * `requestPermissions` methods. Each method ensures the permission it needs
 * before touching the provider — read for `find`/`pickContact`, read+write
 * for `save`/`remove` — matching the legacy cordova-plugin-contacts
 * per-action split. All provider work runs off the main thread.
 */
@CapacitorPlugin(
    name = "Contacts",
    permissions = [
        Permission(
            alias = ContactsPlugin.READ,
            strings = [Manifest.permission.READ_CONTACTS]
        ),
        Permission(
            alias = ContactsPlugin.WRITE,
            strings = [
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
            ]
        )
    ]
)
class ContactsPlugin : Plugin() {

    companion object {
        const val READ = "readContacts"
        const val WRITE = "writeContacts"
    }

    private val implementation by lazy { Contacts(context) }

    /** Serializes provider work off the main thread. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun handleOnDestroy() {
        executor.shutdown()
        super.handleOnDestroy()
    }

    // ------------------------------------------------------------------
    // find
    // ------------------------------------------------------------------

    @PluginMethod
    fun find(call: PluginCall) {
        val fields = call.getArray("fields")
        if (fields == null || fields.length() == 0) {
            reject(call, ContactsError.INVALID_ARGUMENT)
            return
        }
        ensurePermission(call)
    }

    private fun runFind(call: PluginCall) {
        executor.execute {
            try {
                val fields = toStringList(call.getArray("fields"))
                val filter = call.getString("filter", "") ?: ""
                val multiple = call.getBoolean("multiple", false) ?: false
                val desired = toStringList(call.getArray("desiredFields"))
                val hasPhoneNumber = call.getBoolean("hasPhoneNumber", false) ?: false

                val contacts = implementation.search(fields, filter, multiple, desired, hasPhoneNumber)
                val ret = JSObject()
                ret.put("contacts", contacts)
                call.resolve(ret)
            } catch (e: ContactsException) {
                reject(call, e.error)
            } catch (e: Exception) {
                reject(call, ContactsError.UNKNOWN, e)
            }
        }
    }

    // ------------------------------------------------------------------
    // save
    // ------------------------------------------------------------------

    @PluginMethod
    fun save(call: PluginCall) {
        if (call.getObject("contact") == null) {
            reject(call, ContactsError.INVALID_ARGUMENT)
            return
        }
        ensurePermission(call)
    }

    private fun runSave(call: PluginCall) {
        executor.execute {
            try {
                val contact = call.getObject("contact")
                if (contact == null) {
                    reject(call, ContactsError.INVALID_ARGUMENT)
                    return@execute
                }
                val rawId = implementation.save(contact)
                val saved = implementation.getContactByRawId(rawId)
                if (saved != null) {
                    call.resolve(saved)
                } else {
                    reject(call, ContactsError.UNKNOWN)
                }
            } catch (e: ContactsException) {
                reject(call, e.error)
            } catch (e: Exception) {
                reject(call, ContactsError.UNKNOWN, e)
            }
        }
    }

    // ------------------------------------------------------------------
    // remove
    // ------------------------------------------------------------------

    @PluginMethod
    fun remove(call: PluginCall) {
        if (call.getString("id").isNullOrEmpty()) {
            reject(call, ContactsError.INVALID_ARGUMENT)
            return
        }
        ensurePermission(call)
    }

    private fun runRemove(call: PluginCall) {
        executor.execute {
            try {
                val id = call.getString("id") ?: run {
                    reject(call, ContactsError.INVALID_ARGUMENT)
                    return@execute
                }
                if (implementation.remove(id)) {
                    call.resolve()
                } else {
                    // No accessible contact has this id.
                    reject(call, ContactsError.INVALID_ARGUMENT)
                }
            } catch (e: Exception) {
                reject(call, ContactsError.UNKNOWN, e)
            }
        }
    }

    // ------------------------------------------------------------------
    // pickContact
    // ------------------------------------------------------------------

    @PluginMethod
    fun pickContact(call: PluginCall) {
        ensurePermission(call)
    }

    /** True while the system picker is on screen. */
    @Volatile
    private var pickInProgress = false

    private fun runPickContact(call: PluginCall) {
        if (pickInProgress) {
            reject(call, ContactsError.PENDING_OPERATION)
            return
        }
        pickInProgress = true
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        try {
            startActivityForResult(call, intent, "pickContactResult")
        } catch (e: Exception) {
            // No contacts app / picker activity on this device.
            pickInProgress = false
            reject(call, ContactsError.NOT_SUPPORTED, e)
        }
    }

    @ActivityCallback
    private fun pickContactResult(call: PluginCall?, result: androidx.activity.result.ActivityResult) {
        pickInProgress = false
        if (call == null) return
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val uri = result.data?.data
                val contactId = uri?.lastPathSegment
                if (contactId == null) {
                    reject(call, ContactsError.UNKNOWN)
                    return
                }
                executor.execute {
                    try {
                        val contact = implementation.getContactById(contactId)
                        if (contact != null) call.resolve(contact) else reject(call, ContactsError.UNKNOWN)
                    } catch (e: Exception) {
                        reject(call, ContactsError.UNKNOWN, e)
                    }
                }
            }
            Activity.RESULT_CANCELED -> reject(call, ContactsError.OPERATION_CANCELLED)
            else -> reject(call, ContactsError.UNKNOWN)
        }
    }

    // ------------------------------------------------------------------
    // Implicit permission handling
    // ------------------------------------------------------------------

    /** The permission alias each method needs. */
    private fun aliasFor(call: PluginCall): String = when (call.methodName) {
        "save", "remove" -> WRITE
        else -> READ
    }

    /** Ensures the method's permission alias, then dispatches the saved call. */
    private fun ensurePermission(call: PluginCall) {
        if (getPermissionState(aliasFor(call)) == PermissionState.GRANTED) {
            dispatch(call)
        } else {
            requestPermissionForAlias(aliasFor(call), call, "permissionCallback")
        }
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        if (getPermissionState(aliasFor(call)) == PermissionState.GRANTED) {
            dispatch(call)
        } else {
            reject(call, ContactsError.PERMISSION_DENIED)
        }
    }

    /** Routes a permission-cleared call to its implementation runner. */
    private fun dispatch(call: PluginCall) {
        when (call.methodName) {
            "find" -> runFind(call)
            "save" -> runSave(call)
            "remove" -> runRemove(call)
            "pickContact" -> runPickContact(call)
            else -> reject(call, ContactsError.NOT_SUPPORTED)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun toStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            array.optString(i, null)?.let { out.add(it) }
        }
        return out
    }

    private fun reject(call: PluginCall, error: ContactsError, cause: Exception? = null) {
        if (cause != null) {
            call.reject(error.message, error.code, cause)
        } else {
            call.reject(error.message, error.code)
        }
    }
}
