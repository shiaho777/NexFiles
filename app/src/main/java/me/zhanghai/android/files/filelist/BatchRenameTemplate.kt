/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

/**
 * Pure-function engine that turns a rename template into a new file name per source. Designed to be
 * fully testable (no Android dependencies) and to cover the operations users actually reach for:
 * inserting the original name/extension, an incrementing counter, case changes, and regex
 * substitution — the same primitives MT exposes.
 *
 * Template syntax:
 *   [name]  — original base name (no extension)
 *   [ext]   — original extension (with the leading dot, e.g. ".jpg")
 *   [n]     — counter, starting at [startIndex], width padded to the number of n's
 *             ([n]=1,2,3  [nn]=01,02,03  [nnn]=001,002,003)
 *   [N]     — upper-cased original full name
 *   s/old/new/[flags]  — regex substitution applied to the accumulated name so far;
 *             flags: i (ignore case), g (global, default — only first match replaced otherwise)
 *
 * Everything else is literal text. Tokens can be combined: "[name]_[nn][ext]" etc.
 */
object BatchRenameTemplate {

    /**
     * Applies [template] to each name in [names] in order, returning the resulting names. The
     * counter increments per item starting at [startIndex]. Names with no extension yield [ext]="".
     */
    fun apply(names: List<String>, template: String, startIndex: Int = 1): List<String> {
        require(startIndex >= 0) { "startIndex must be >= 0" }
        return names.mapIndexed { index, original ->
            applyToOne(original, template, startIndex + index)
        }
    }

    /** Applies the template to a single [original] name with an explicit counter value. */
    fun applyToOne(original: String, template: String, counter: Int): String {
        // A leading "s/.../.../flags" token is a whole-name substitution; everything after it (if
        // any) is treated as additional literal/suffix text appended after substitution.
        var result = original
        var remaining = template
        // Handle a leading regex substitution specially since it spans the whole current name.
        if (remaining.startsWith("s/") && remaining.count { it == '/' } >= 2) {
            val endFlags = remaining.indexOf('/', 2)
            val replacementEnd = remaining.indexOf('/', endFlags + 1)
            if (replacementEnd >= 0) {
                val pattern = remaining.substring(2, endFlags)
                val replacement = remaining.substring(endFlags + 1, replacementEnd)
                val flags = remaining.substring(replacementEnd + 1).takeWhile { it.isLetter() }
                result = applyRegex(result, pattern, replacement, flags)
                remaining = remaining.substring(replacementEnd + 1 + flags.length)
            }
        }
        // Token expansion on whatever remains of the template.
        if (remaining.isNotEmpty()) {
            result = expandTokens(result, remaining, counter)
        }
        return result
    }

    private fun expandTokens(original: String, template: String, counter: Int): String {
        val (baseName, ext) = splitName(original)
        val builder = StringBuilder(template.length + 8)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '[') {
                val close = template.indexOf(']', i + 1)
                if (close > i) {
                    val token = template.substring(i + 1, close)
                    when {
                        token == "name" -> builder.append(baseName)
                        token == "ext" -> builder.append(ext)
                        token == "N" -> builder.append(original.uppercase())
                        token.all { it == 'n' } && token.isNotEmpty() -> {
                            // [n], [nn], [nnn] — pad counter to token width.
                            builder.append(counter.toString().padStart(token.length, '0'))
                        }
                        else -> {
                            // Unknown token: emit verbatim so the user sees their typo rather than
                            // a silent drop.
                            builder.append('[').append(token).append(']')
                        }
                    }
                    i = close + 1
                    continue
                }
            }
            builder.append(c)
            i++
        }
        return builder.toString()
    }

    private fun applyRegex(input: String, pattern: String, replacement: String, flags: String): String {
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
        }
        val regex = try {
            Regex(pattern, options)
        } catch (e: Exception) {
            // Invalid pattern: leave the name untouched rather than crashing the whole batch.
            return input
        }
        val global = 'g' in flags
        return if (global) regex.replace(input, replacement) else regex.replaceFirst(input, replacement)
    }

    /** Splits [name] into (baseName, extensionWithDot). No-extension names return (name, ""). */
    fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        // Treat a leading dot (hidden files like ".bashrc") as part of the name, not an extension.
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }
}
