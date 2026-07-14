package com.capacitorjs.plugins.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsErrorTests {

    @Test
    fun errorCodesFollowUnifiedFormat() {
        assertEquals("OS-PLUG-CONT-0000", ContactsError.UNKNOWN.code)
        assertEquals("OS-PLUG-CONT-0001", ContactsError.INVALID_ARGUMENT.code)
        assertEquals("OS-PLUG-CONT-0002", ContactsError.TIMEOUT.code)
        assertEquals("OS-PLUG-CONT-0003", ContactsError.PENDING_OPERATION.code)
        assertEquals("OS-PLUG-CONT-0004", ContactsError.IO_ERROR.code)
        assertEquals("OS-PLUG-CONT-0005", ContactsError.NOT_SUPPORTED.code)
        assertEquals("OS-PLUG-CONT-0006", ContactsError.OPERATION_CANCELLED.code)
        assertEquals("OS-PLUG-CONT-0020", ContactsError.PERMISSION_DENIED.code)
    }

    @Test
    fun exceptionCarriesErrorAndMessage() {
        val exception = ContactsException(ContactsError.IO_ERROR)
        assertEquals(ContactsError.IO_ERROR, exception.error)
        assertEquals(ContactsError.IO_ERROR.message, exception.message)
    }
}
