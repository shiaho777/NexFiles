/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import java.lang.reflect.Method

/**
 * Built-in hook replacement rules, addressable by id across the Binder boundary.
 *
 * Since a Binder transaction can't carry an arbitrary Kotlin lambda, the sandbox service
 * expresses hook logic through these enumerable rules. Each rule takes a string [arg] and
 * produces a replacement function. The rules cover the most common reverse-engineering
 * operations; custom logic can be added by extending this enum (and the sandbox service).
 *
 * Rule ids are stable across versions (do not renumber); they're part of the AIDL contract.
 */
enum class HookRule(val id: Int) {
    /** Always return the value parsed from [arg] as the hook's return type (null/boolean/int/etc). */
    RETURN_CONSTANT(1),

    /** Log every invocation with the receiver and args, then call through to the original. */
    LOG_CALLS(2),

    /** Replace the string return value: occurrences of arg's first newline-separated half are
     *  replaced with the second half. */
    REPLACE_STRING(3),

    /** Block the call entirely: return null (or the type's zero value) without invoking original. */
    BLOCK_CALL(4);

    companion object {
        fun fromId(id: Int): HookRule? = values().firstOrNull { it.id == id }
    }
}

/**
 * Produces a replacement function for [rule] applied to [target].
 *
 * The replacement runs in the sandbox process, on the exact thread the hooked method was called
 * from. [original] lets it call through to the un-hooked implementation.
 */
fun hookReplacementFor(
    rule: HookRule,
    arg: String,
    target: Method
): (receiver: Any?, args: Array<Any?>, original: () -> Any?) -> Any? = when (rule) {
    HookRule.RETURN_CONSTANT -> { _, _, _ ->
        parseConstant(arg, target.returnType)
    }
    HookRule.LOG_CALLS -> { receiver, args, original ->
        val msg = "${target.declaringClass.name}.${target.name} called: receiver=$receiver args=${args.toList()}"
        HookLogDispatcher.info(target.declaringClass.name, msg)
        original()
    }
    HookRule.REPLACE_STRING -> { _, _, original ->
        val (from, to) = arg.substringBefore('\n', arg) to arg.substringAfter('\n', "")
        // Call through to the original, then replace occurrences of `from` with `to` in the
        // returned string. Mutating arguments requires a custom rule; this covers the common
        // case of rewriting a string the method returns (URLs, keys, labels).
        val result = original()
        if (result is String) result.replace(from, to) else result
    }
    HookRule.BLOCK_CALL -> { _, _, _ ->
        defaultForType(target.returnType)
    }
}

/** Parses [arg] into a value of [type], used by RETURN_CONSTANT. */
private fun parseConstant(arg: String, type: Class<*>): Any? {
    if (arg.isEmpty() || arg == "null") return null
    return when (type) {
        Boolean::class.javaPrimitiveType, Boolean::class.java -> arg.toBooleanStrictOrNull() ?: false
        Int::class.javaPrimitiveType, Integer::class.java -> arg.toIntOrNull() ?: 0
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> arg.toLongOrNull() ?: 0L
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> arg.toFloatOrNull() ?: 0f
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> arg.toDoubleOrNull() ?: 0.0
        String::class.java -> arg
        else -> null
    }
}

/** Returns the zero/null value for [type], used by BLOCK_CALL. */
private fun defaultForType(type: Class<*>): Any? = when (type) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    Byte::class.javaPrimitiveType -> 0.toByte()
    Short::class.javaPrimitiveType -> 0.toShort()
    Char::class.javaPrimitiveType -> 0.toChar()
    Void::class.javaPrimitiveType, Unit::class.java -> null
    else -> null
}
