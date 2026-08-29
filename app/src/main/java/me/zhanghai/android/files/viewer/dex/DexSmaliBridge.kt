/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import org.jf.baksmali.Adaptors.ClassDefinition
import org.jf.baksmali.BaksmaliOptions
import org.jf.baksmali.formatter.BaksmaliWriter
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.writer.builder.DexBuilder
import org.jf.smali.smaliFlexLexer
import org.jf.smali.smaliParser
import org.jf.smali.smaliTreeWalker
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter

/**
 * Bridge between dexlib2's [ClassDef] objects and human-readable smali source text, using the
 * `baksmali` (disassembler) and `smali` (assembler) libraries.
 *
 *  - [disassembleClass] turns a single [ClassDef] into its `.smali` source representation — the
 *    same text `baksmali d` would emit — so the user can read and edit it.
 *  - [assembleClass] parses edited smali text back into a [ClassDef], ready to replace the
 *    original in the DEX model.
 *
 * **Disassembly** uses [ClassDefinition] (from baksmali's `Adaptors` package) with a
 * [BaksmaliWriter]. This is the exact code path that `baksmali d` uses internally.
 *
 * **Assembly** replicates the private `Smali.assembleSmaliFile` logic from the smali library,
 * but with an in-memory [StringReader] instead of a file. The ANTLR3 pipeline is:
 * `smaliFlexLexer → CommonTokenStream → smaliParser → AST → smaliTreeWalker → DexBuilder`.
 * The assembled class is extracted directly from the [DexBuilder].
 *
 * Both directions go through the official smali/baksmali APIs rather than hand-rolling a
 * parser, so we inherit the exact syntax rules of the reference tools.
 */
internal object DexSmaliBridge {

    /**
     * Disassembles [classDef] into smali source text. Returns the full `.smali` file content,
     * including the `.class` directive, all fields, methods, and annotations.
     */
    fun disassembleClass(classDef: ClassDef): String {
        val options = BaksmaliOptions()
        options.debugInfo = true
        val classDefinition = ClassDefinition(options, classDef)
        val stringWriter = StringWriter()
        val writer = BaksmaliWriter(stringWriter)
        classDefinition.writeTo(writer)
        writer.flush()
        return stringWriter.toString()
    }

    /**
     * Assembles [smaliText] (the full `.smali` file content, including `.class` directive) into a
     * [ClassDef], validated against [opcodes].
     *
     * @throws IOException if the smali text has syntax or semantic errors that the assembler rejects.
     *         The message includes the error count and (when available) details from the exception.
     */
    @Throws(IOException::class)
    fun assembleClass(smaliText: String, opcodes: Opcodes): ClassDef {
        val reader = StringReader(smaliText)
        // ANTLR3 lexer/parser pipeline — same as Smali.assembleSmaliFile but from a Reader.
        val lexer = smaliFlexLexer(reader, opcodes.api)
        val tokens = CommonTokenStream(lexer)
        val parser = smaliParser(tokens)
        val result = parser.smali_file()
        val lexerErrors = lexer.numberOfSyntaxErrors
        val parserErrors = parser.numberOfSyntaxErrors
        if (lexerErrors > 0 || parserErrors > 0) {
            throw IOException(
                "Smali syntax errors (lexer: $lexerErrors, parser: $parserErrors)"
            )
        }
        // Tree-walker phase: walks the AST and emits instructions into the DexBuilder.
        val tree = result.tree
        val treeStream = CommonTreeNodeStream(tree)
        treeStream.tokenStream = tokens
        val dexBuilder = DexBuilder(opcodes)
        val walker = smaliTreeWalker(treeStream)
        walker.setApiLevel(opcodes.api)
        walker.setVerboseErrors(true)
        walker.setDexBuilder(dexBuilder)
        walker.smali_file()
        val walkerErrors = walker.numberOfSyntaxErrors
        if (walkerErrors > 0) {
            throw IOException("Smali semantic errors ($walkerErrors) — check for invalid instructions or references")
        }
        // Extract the assembled class from the DexBuilder's class pool.
        val classDef = dexBuilder.classSection.getSortedClasses().firstOrNull()
            ?: throw IOException("Smali assembler produced no class definition — check for syntax errors")
        return classDef
    }
}
