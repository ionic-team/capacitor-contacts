package com.capacitorjs.plugins.contacts

/**
 * OutSystems unified error codes for the Contacts plugin, in the
 * `OS-PLUG-CONT-NNNN` format. The numeric suffix mirrors the legacy Cordova
 * `ContactError` codes so both stacks report equivalent failures.
 */
enum class ContactsError(val code: String, val message: String) {
    UNKNOWN("OS-PLUG-CONT-0000", "An unknown error occurred."),
    INVALID_ARGUMENT("OS-PLUG-CONT-0001", "Invalid arguments were provided."),
    TIMEOUT("OS-PLUG-CONT-0002", "The operation timed out."),
    PENDING_OPERATION("OS-PLUG-CONT-0003", "A pending operation is already in progress."),
    IO_ERROR("OS-PLUG-CONT-0004", "An I/O error occurred while accessing contacts."),
    NOT_SUPPORTED("OS-PLUG-CONT-0005", "The operation is not supported on this device."),
    OPERATION_CANCELLED("OS-PLUG-CONT-0006", "The operation was cancelled."),
    PERMISSION_DENIED("OS-PLUG-CONT-0020", "Contacts permission was denied.")
}

/**
 * Throwable wrapper for [ContactsError]. Kotlin enums cannot themselves be
 * [Throwable], so business logic throws this and the bridge reads `error` to
 * reject the call with the right code.
 */
class ContactsException(
    val error: ContactsError,
    cause: Throwable? = null
) : Exception(error.message, cause)
