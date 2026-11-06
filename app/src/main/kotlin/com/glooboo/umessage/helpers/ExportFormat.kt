package com.glooboo.umessage.helpers

/** The file formats the messages can be exported in. */
enum class ExportFormat(val extension: String) {
    /** uMessage's own file: everything, including pictures, and the one uMessage restores. */
    JSON("json"),

    /** The XML of "SMS Backup & Restore", for other apps. */
    SMS_XML("xml"),
}
