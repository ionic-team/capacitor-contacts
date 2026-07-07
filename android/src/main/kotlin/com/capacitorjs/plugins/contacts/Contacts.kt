package com.capacitorjs.plugins.contacts

import com.getcapacitor.Logger

class Contacts {

    fun echo(value: String): String {
        Logger.info("Echo", value)

        return value
    }
}
