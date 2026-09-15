package moe.antimony.hoshi.epub

import java.security.MessageDigest

internal const val MAX_PATH_COMPONENT_UTF8_BYTES = 255
internal const val MAX_BOOK_STORAGE_BASENAME_UTF8_BYTES = MAX_PATH_COMPONENT_UTF8_BYTES - ".epub".length

internal fun String.toImportedBookStorageName(): String =
    sanitizeImportedBookTitle()
        .fitUtf8PathComponent(MAX_BOOK_STORAGE_BASENAME_UTF8_BYTES)

internal fun String.fitUtf8PathComponent(maxUtf8Bytes: Int): String {
    require(maxUtf8Bytes > HASH_SUFFIX_UTF8_BYTES) { "UTF-8 path component budget is too small." }
    if (toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes) return this

    val suffix = "-${sha256Hex().take(HASH_HEX_LENGTH)}"
    val prefix = takeUtf8Prefix(maxUtf8Bytes - suffix.toByteArray(Charsets.UTF_8).size).trimEnd()
    return prefix + suffix
}

private fun String.sanitizeImportedBookTitle(): String =
    split(Regex("[\\\\/:*?\"<>|\\n\\r\\u0000-\\u001F]"))
        .joinToString("_")
        .trim()

private fun String.takeUtf8Prefix(maxUtf8Bytes: Int): String = buildString {
    var sourceIndex = 0
    var usedBytes = 0
    while (sourceIndex < this@takeUtf8Prefix.length) {
        val codePoint = this@takeUtf8Prefix.codePointAt(sourceIndex)
        val codePointText = String(Character.toChars(codePoint))
        val codePointBytes = codePointText.toByteArray(Charsets.UTF_8).size
        if (usedBytes + codePointBytes > maxUtf8Bytes) break
        append(codePointText)
        usedBytes += codePointBytes
        sourceIndex += Character.charCount(codePoint)
    }
}

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val HASH_HEX_LENGTH = 16
private const val HASH_SUFFIX_UTF8_BYTES = HASH_HEX_LENGTH + 1
