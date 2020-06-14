package net.termer.twine.rtfl.utils

import java.util.*


/**
 * Sanitizes the provided content for HTML insertion
 * @param content The content to sanitize
 * @return The content, sanitized for HTML insertion
 * @since 1.0
 */
fun sanitizeHTML(content: String) = content
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/**
 * Returns all indexes of the specified substring in this String
 * @param substring The substring to find the indexes of
 * @return An array containing all of the indexes of the specified substring
 * @since 1.0
 */
fun String.indexesOf(substring: String): Array<Int> {
    val indexes = ArrayList<Int>()
    val startIndex = this.indexOf(substring)

    if(startIndex > -1) {
        indexes.add(startIndex)
        var lastIndex = startIndex
        while(lastIndex > -1) {
            lastIndex = this.indexOf(substring, lastIndex + 1)

            if(lastIndex > -1 && !indexes.contains(lastIndex))
                indexes.add(lastIndex)
        }
    }

    return indexes.toTypedArray()
}