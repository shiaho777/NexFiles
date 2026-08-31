/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.compat.reversedCompat
import me.zhanghai.android.files.file.FileItem

@Parcelize
data class FileSortOptions(
    val by: By,
    val order: Order,
    val isDirectoriesFirst: Boolean
) : Parcelable {
    fun createComparator(): Comparator<FileItem> {
        // The name-unimportant-prefix check runs as the first key on every comparison of every
        // sort; a dedicated comparator avoids allocating a Boolean key and re-scanning prefixes
        // through a nested lambda on each of the O(n log n) comparisons.
        var comparator: Comparator<FileItem> = NAME_UNIMPORTANT_PREFIX_COMPARATOR
            .thenBy { it.nameCollationKey }
        when (by) {
            // Nothing to do.
            By.NAME -> {}
            By.TYPE ->
                comparator = compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) {
                    it.extension
                }.then(comparator)
            By.SIZE -> comparator = compareBy<FileItem> { it.attributes.size() }.then(comparator)
            By.LAST_MODIFIED ->
                comparator = compareBy<FileItem> { it.attributes.lastModifiedTime() }
                    .then(comparator)
        }
        when (order) {
            Order.ASCENDING -> {}
            Order.DESCENDING -> comparator = comparator.reversedCompat()
        }
        if (isDirectoriesFirst) {
            val isDirectoryComparator = compareBy<FileItem> { it.attributes.isDirectory }
                .reversedCompat()
            comparator = isDirectoryComparator.then(comparator)
        }
        return comparator
    }

    companion object {
        // Same behavior as Nautilus.
        private const val NAME_UNIMPORTANT_FIRST_PREFIX = '.'
        private const val NAME_UNIMPORTANT_SECOND_PREFIX = '#'

        private val NAME_UNIMPORTANT_PREFIX_COMPARATOR =
            Comparator<FileItem> { a, b ->
                isNameUnimportant(a.name).compareTo(isNameUnimportant(b.name))
            }

        private fun isNameUnimportant(name: String): Boolean {
            val first = name.firstOrNull() ?: return false
            return first == NAME_UNIMPORTANT_FIRST_PREFIX || first == NAME_UNIMPORTANT_SECOND_PREFIX
        }
    }

    enum class By {
        NAME,
        TYPE,
        SIZE,
        LAST_MODIFIED
    }

    enum class Order {
        ASCENDING,
        DESCENDING
    }
}
