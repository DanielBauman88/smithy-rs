/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.rustlang

import org.intellij.lang.annotations.Language
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolWriter
import software.amazon.smithy.codegen.core.SymbolWriter.Factory
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.BooleanShape
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.DoubleShape
import software.amazon.smithy.model.shapes.FloatShape
import software.amazon.smithy.model.shapes.NumberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.DeprecatedTrait
import software.amazon.smithy.model.traits.DocumentationTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute.Companion.deprecated
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.isOptional
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.ValueExpression
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.util.PANIC
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.letIf
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.utils.AbstractCodeWriter
import java.io.File
import java.util.function.BiFunction

/**
 * # RustWriter (and Friends)
 *
 * RustWriter contains a set of features to make generating Rust code much more ergonomic ontop of the Smithy CodeWriter
 * interface.
 *
 * ## Recommended Patterns
 * For templating large blocks of Rust code, the preferred method is [rustTemplate]. This enables sharing a set of template
 * variables by creating a "codegenScope."
 *
 * ```kotlin
 * fun requestHeaders(): Writeable { ... }
 * let codegenScope = arrayOf("http" to CargoDependency.http)
 * let writer = RustWriter() // in normal code, you would normally get a RustWriter passed to you
 * writer.rustTemplate("""
 *  // regular types can be rendered directly
 *  let request = #{http}::Request::builder().uri("http://example.com");
 *  // writeables can be rendered with `:W`
 *  let request_headers = #{request_headers:W};
 * """, *codegenScope, "request_headers" to requestHeaders())
 * ```
 *
 * For short snippets of code [rust] can be used. This is equivalent but only positional arguments can be used.
 *
 * For formatting blocks where the block content generation requires looping, [rustBlock] and the equivalent [rustBlockTemplate]
 * are the recommended approach.
 */

fun <T : AbstractCodeWriter<T>> T.withBlock(
    textBeforeNewLine: String,
    textAfterNewLine: String,
    vararg args: Any,
    block: T.() -> Unit,
): T {
    return conditionalBlock(textBeforeNewLine, textAfterNewLine, conditional = true, block = block, args = args)
}

fun <T : AbstractCodeWriter<T>> T.assignment(variableName: String, vararg ctx: Pair<String, Any>, block: T.() -> Unit) {
    withBlockTemplate("let $variableName =", ";", *ctx) {
        block()
    }
}

fun <T : AbstractCodeWriter<T>> T.withBlockTemplate(
    textBeforeNewLine: String,
    textAfterNewLine: String,
    vararg ctx: Pair<String, Any>,
    block: T.() -> Unit,
): T {
    return withTemplate(textBeforeNewLine, ctx) { header ->
        conditionalBlock(header, textAfterNewLine, conditional = true, block = block)
    }
}

private fun <T : AbstractCodeWriter<T>, U> T.withTemplate(
    template: String,
    scope: Array<out Pair<String, Any>>,
    trim: Boolean = true,
    f: T.(String) -> U,
): U {
    scope.forEach { (k, v) ->
        when (v) {
            is Unit -> PANIC("provided `kotlin.Unit` for $k. This is a bug.")
        }
    }
    val contents = transformTemplate(template, scope, trim)
    pushState()
    this.putContext(scope.toMap().mapKeys { (k, _) -> k.lowercase() })
    val out = f(contents)
    this.popState()
    return out
}

/**
 * Write a block to the writer.
 * If [conditional] is true, the [textBeforeNewLine], followed by [block], followed by [textAfterNewLine]
 * If [conditional] is false, only [block] is written.
 * This enables conditionally wrapping a block in a prefix/suffix, e.g.
 *
 * ```
 * writer.withBlock("Some(", ")", conditional = symbol.isOptional()) {
 *      write("symbolValue")
 * }
 * ```
 */
fun <T : AbstractCodeWriter<T>> T.conditionalBlock(
    textBeforeNewLine: String,
    textAfterNewLine: String,
    conditional: Boolean = true,
    vararg args: Any,
    block: T.() -> Unit,
): T {
    if (conditional) {
        openBlock(textBeforeNewLine.trim(), *args)
    }

    block(this)
    if (conditional) {
        closeBlock(textAfterNewLine.trim())
    }
    return this
}

/**
 * Convenience wrapper that tells Intellij that the contents of this block are Rust
 */
fun <T : AbstractCodeWriter<T>> T.rust(
    @Language("Rust", prefix = "macro_rules! foo { () =>  {{\n", suffix = "\n}}}") contents: String,
    vararg args: Any,
) {
    this.write(contents.trim(), *args)
}

/**
 * Convenience wrapper that tells Intellij that the contents of this block are Rust
 */
fun RustWriter.rustInline(
    @Language("Rust", prefix = "macro_rules! foo { () =>  {{ ", suffix = "}}}") contents: String,
    vararg args: Any,
) {
    this.writeInline(contents, *args)
}

/* rewrite #{foo} to #{foo:T} (the smithy template format) */
private fun transformTemplate(template: String, scope: Array<out Pair<String, Any>>, trim: Boolean = true): String {
    check(scope.distinctBy { it.first.lowercase() }.size == scope.size) { "Duplicate cased keys not supported" }
    val output = template.replace(Regex("""#\{([a-zA-Z_0-9]+)(:\w)?\}""")) { matchResult ->
        val keyName = matchResult.groupValues[1]
        val templateType = matchResult.groupValues[2].ifEmpty { ":T" }
        if (!scope.toMap().keys.contains(keyName)) {
            throw CodegenException(
                "Rust block template expected `$keyName` but was not present in template.\n  hint: Template contains: ${
                scope.map { it.first }
                }",
            )
        }
        "#{${keyName.lowercase()}$templateType}"
    }

    return output.letIf(trim) { output.trim() }
}

/**
 * Sibling method to [rustBlock] that enables `#{variablename}` style templating
 */
fun <T : AbstractCodeWriter<T>> T.rustBlockTemplate(
    @Language("Rust", prefix = "macro_rules! foo { () =>  {{ ", suffix = "}}}") contents: String,
    vararg ctx: Pair<String, Any>,
    block: T.() -> Unit,
) {
    withTemplate(contents, ctx) { header ->
        this.openBlock("$header {")
        block(this)
        closeBlock("}")
    }
}

/**
 * API for templating long blocks of Rust
 *
 * This enables writing code like:
 *
 * ```kotlin
 * writer.rustTemplate("""
 * let some_val = #{operation}::from_response(response);
 * let serialized = #{serde_json}::to_json(some_val);
 * """, "operation" to operationSymbol, "serde_json" to RuntimeType.SerdeJson)
 * ```
 *
 * Before being passed to the underlying code writer, this syntax is rewritten to match the slightly more verbose
 * `CodeWriter` formatting: `#{name:T}`
 *
 * Variables are lower cased so that they become valid identifiers for named Smithy parameters.
 */
fun RustWriter.rustTemplate(
    @Language("Rust", prefix = "macro_rules! foo { () =>  {{ ", suffix = "}}}") contents: String,
    vararg ctx: Pair<String, Any>,
) {
    withTemplate(contents, ctx) { template ->
        write(template)
    }
}

/**
 * An API for templating inline Rust code.
 *
 * Works just like [RustWriter.rustTemplate] but won't write a newline at the end and won't trim the input
 */
fun RustWriter.rustInlineTemplate(
    @Language("Rust", prefix = "macro_rules! foo { () =>  {{ ", suffix = "}}}") contents: String,
    vararg ctx: Pair<String, Any>,
) {
    withTemplate(contents, ctx, trim = false) { template ->
        writeInline(template)
    }
}

/*
 * Writes a Rust-style block, demarcated by curly braces
 */
fun <T : AbstractCodeWriter<T>> T.rustBlock(
    @Language("Rust", prefix = "macro_rules! foo { () =>  {{ ", suffix = "}}}")
    header: String,
    vararg args: Any,
    block: T.() -> Unit,
): T {
    openBlock("$header {", *args)
    block(this)
    closeBlock("}")
    return this
}

/**
 * Generate a RustDoc comment for [shape]
 */
fun <T : AbstractCodeWriter<T>> T.documentShape(
    shape: Shape,
    model: Model,
    autoSuppressMissingDocs: Boolean = true,
    note: String? = null,
): T {
    val docTrait = shape.getMemberTrait(model, DocumentationTrait::class.java).orNull()

    return docsOrFallback(docTrait?.value, autoSuppressMissingDocs, note)
}

fun <T : AbstractCodeWriter<T>> T.docsOrFallback(
    docString: String? = null,
    autoSuppressMissingDocs: Boolean = true,
    note: String? = null,
): T {
    val htmlDocs: (T.() -> Unit)? = when (docString?.isNotBlank()) {
        true -> { { docs(normalizeHtml(escape(docString))) } }
        else -> null
    }
    return docsOrFallback(htmlDocs, autoSuppressMissingDocs, note)
}

fun <T : AbstractCodeWriter<T>> T.docsOrFallback(
    docsWritable: (T.() -> Unit)? = null,
    autoSuppressMissingDocs: Boolean = true,
    note: String? = null,
): T {
    if (docsWritable != null) {
        // If docs are modeled, then place them on the code generated shape

        docsWritable(this)
        note?.also {
            // Add a blank line between the docs and the note to visually differentiate
            write("///")
            docs("_Note: ${it}_")
        }
    } else if (autoSuppressMissingDocs) {
        rust("##[allow(missing_docs)] // documentation missing in model")
    }
    // Otherwise, suppress the missing docs lint for this shape since
    // the lack of documentation is a modeling issue rather than a codegen issue.
    return this
}

/** Document the containing entity (e.g. module, crate, etc.)
 * Instead of prefixing lines with `///` lines are prefixed with `//!`
 */
fun RustWriter.containerDocs(text: String, vararg args: Any): RustWriter {
    return docs(text, newlinePrefix = "//! ", args = args)
}

/**
 * Write RustDoc-style docs into the writer
 *
 * Several modifications are made to provide consistent RustDoc formatting:
 *    - All lines will be prefixed by `///`
 *    - Tabs are replaced with spaces
 *    - Empty newlines are removed
 */
fun <T : AbstractCodeWriter<T>> T.docs(text: String, vararg args: Any, newlinePrefix: String = "/// "): T {
    // Because writing docs relies on the newline prefix, ensure that there was a new line written
    // before we write the docs
    this.ensureNewline()
    pushState()
    setNewlinePrefix(newlinePrefix)
    val cleaned = text.lines()
        .joinToString("\n") {
            // Rustdoc warns on tabs in documentation
            it.trimStart().replace("\t", "  ")
        }
    write(cleaned, *args)
    popState()
    return this
}

/**
 * Writes a comment into the code
 *
 * Equivalent to [docs] but lines are preceded with `// ` instead of `///`
 */
fun <T : AbstractCodeWriter<T>> T.comment(text: String, vararg args: Any): T {
    return docs(text, *args, newlinePrefix = "// ")
}

/**
 * Generates a `#[deprecated]` attribute for [shape].
 */
fun RustWriter.deprecatedShape(shape: Shape): RustWriter {
    val deprecatedTrait = shape.getTrait<DeprecatedTrait>() ?: return this

    val note = deprecatedTrait.message.orNull()
    val since = deprecatedTrait.since.orNull()

    Attribute(deprecated(since, note)).render(this)

    return this
}

/** Escape the [expressionStart] character to avoid problems during formatting */
fun <T : AbstractCodeWriter<T>> T.escape(text: String): String =
    text.replace("$expressionStart", "$expressionStart$expressionStart")

/** Parse input as HTML and normalize it */
fun normalizeHtml(input: String): String {
    val doc = Jsoup.parse(input)
    doc.body().apply {
        normalizeAnchors() // Convert anchor tags missing href attribute into pre tags
    }

    return doc.body().html()
}

private fun Element.normalizeAnchors() {
    getElementsByTag("a").forEach {
        val link = it.attr("href")
        if (link.isBlank()) {
            it.changeInto("code")
        }
    }
}

private fun Element.changeInto(tagName: String) {
    replaceWith(Element(tagName).also { elem -> elem.appendChildren(childNodesCopy()) })
}

/**
 * Write _exactly_ the text as written into the code writer without newlines or formatting
 */
fun RustWriter.raw(text: String) = writeInline(escape(text))

/**
 * Rustdoc doesn't support `r#` for raw identifiers.
 * This function adjusts doc links to refer to raw identifiers directly.
 */
fun docLink(docLink: String): String = docLink.replace("::r##", "::").replace("::r#", "::")

class RustWriter private constructor(
    private val filename: String,
    val namespace: String,
    private val commentCharacter: String = "//",
    private val printWarning: Boolean = true,
    /** Insert comments indicating where code was generated */
    private val debugMode: Boolean = false,
) :
    SymbolWriter<RustWriter, UseDeclarations>(UseDeclarations(namespace)) {
    companion object {
        fun root() = forModule(null)
        fun forModule(module: String?): RustWriter = if (module == null) {
            RustWriter("lib.rs", "crate")
        } else {
            RustWriter("$module.rs", "crate::$module")
        }

        fun factory(debugMode: Boolean): Factory<RustWriter> = Factory { fileName: String, namespace: String ->
            when {
                fileName.endsWith(".toml") -> RustWriter(fileName, namespace, "#", debugMode = debugMode)
                fileName.endsWith(".md") -> rawWriter(fileName, debugMode = debugMode)
                fileName == "LICENSE" -> rawWriter(fileName, debugMode = debugMode)
                else -> RustWriter(fileName, namespace, debugMode = debugMode)
            }
        }

        private fun rawWriter(fileName: String, debugMode: Boolean): RustWriter =
            RustWriter(
                fileName,
                namespace = "ignore",
                commentCharacter = "ignore",
                printWarning = false,
                debugMode = debugMode,
            )
    }

    override fun write(content: Any?, vararg args: Any?): RustWriter {
        // TODO(https://github.com/rust-lang/rustfmt/issues/5425): The second condition introduced here is to prevent
        //     this rustfmt bug
        val contentIsNotJustAComma = (content as? String?)?.let { it.trim() != "," } ?: false
        if (debugMode && contentIsNotJustAComma) {
            val location = Thread.currentThread().stackTrace
            location.first { it.isRelevant() }?.let { "/* ${it.fileName}:${it.lineNumber} */" }
                ?.also { super.writeInline(it) }
        }

        return super.write(content, *args)
    }

    /** Helper function to determine if a stack frame is relevant for debug purposes */
    private fun StackTraceElement.isRelevant(): Boolean {
        if (this.className.contains("AbstractCodeWriter") || this.className.startsWith("java.lang")) {
            return false
        }
        if (this.fileName == "RustWriter.kt") {
            return false
        }
        return true
    }

    private val preamble = mutableListOf<Writable>()
    private val formatter = RustSymbolFormatter()
    private var n = 0

    init {
        expressionStart = '#'
        if (filename.endsWith(".rs")) {
            require(namespace.startsWith("crate") || filename.startsWith("tests/")) { "We can only write into files in the crate (got $namespace)" }
        }
        putFormatter('T', formatter)
        putFormatter('D', RustDocLinker())
        putFormatter('W', RustWriteableInjector())
    }

    fun module(): String? = if (filename.startsWith("src") && filename.endsWith(".rs")) {
        filename.removeSuffix(".rs").substringAfterLast(File.separatorChar)
    } else null

    fun safeName(prefix: String = "var"): String {
        n += 1
        return "${prefix}_$n"
    }

    fun first(preWriter: Writable) {
        preamble.add(preWriter)
    }

    /**
     * Create an inline module. Instead of being in a new file, inline modules are written as a `mod { ... }` block
     * directly into the parent.
     *
     * Callers must take care to use [this] when writing to ensure code is written to the right place:
     * ```kotlin
     * val writer = RustWriter.forModule("model")
     * writer.withModule(RustModule.public("nested")) {
     *   Generator(...).render(this) // GOOD
     *   Generator(...).render(writer) // WRONG!
     * }
     * ```
     *
     * The returned writer will inject any local imports into the module as needed.
     */
    fun withInlineModule(
        module: RustModule.LeafModule,
        moduleWriter: Writable,
    ): RustWriter {
        check(module.isInline()) {
            "Only inline modules may be used with `withInlineModule`: $module"
        }
        // In Rust, modules must specify their own imports—they don't have access to the parent scope.
        // To easily handle this, create a new inner writer to collect imports, then dump it
        // into an inline module.
        val innerWriter = RustWriter(this.filename, "${this.namespace}::${module.name}", printWarning = false)
        moduleWriter(innerWriter)
        module.documentation?.let { docs -> docs(docs) }
        module.rustMetadata.render(this)
        rustBlock("mod ${module.name}") {
            writeWithNoFormatting(innerWriter.toString())
        }
        innerWriter.dependencies.forEach { addDependency(it) }
        return this
    }

    /**
     * Generate a wrapping if statement around a nullable value.
     * The provided code block will only be called if the value is not `None`.
     */
    fun ifSome(member: Symbol, value: ValueExpression, block: RustWriter.(value: ValueExpression) -> Unit) {
        when {
            member.isOptional() -> {
                val innerValue = ValueExpression.Reference(safeName("inner"))
                rustBlock("if let Some(${innerValue.name}) = ${value.asRef()}") {
                    block(innerValue)
                }
            }

            else -> this.block(value)
        }
    }

    /**
     * Generate a wrapping if statement around a primitive field.
     * The specified block will only be called if the field is not set to its default value - `0` for
     * numbers, `false` for booleans.
     */
    fun ifNotDefault(shape: Shape, variable: ValueExpression, block: RustWriter.(field: ValueExpression) -> Unit) {
        when (shape) {
            is FloatShape, is DoubleShape -> rustBlock("if ${variable.asValue()} != 0.0") {
                block(variable)
            }

            is NumberShape -> rustBlock("if ${variable.asValue()} != 0") {
                block(variable)
            }

            is BooleanShape -> rustBlock("if ${variable.asValue()}") {
                block(variable)
            }

            else -> rustBlock("") {
                this.block(variable)
            }
        }
    }

    /**
     * Generate a wrapping if statement around a field.
     *
     * - If the field is optional, it will only be called if the field is present
     * - If the field is an unboxed primitive, it will only be called if the field is non-zero
     *
     * # Example
     *
     * For a nullable structure shape (e.g. `Option<MyStruct>`), the following code will be generated:
     *
     * ```
     * if let Some(v) = my_nullable_struct {
     *   /* {block(variable)} */
     * }
     * ```
     *
     * # Example
     *
     * For a non-nullable integer shape, the following code will be generated:
     *
     * ```
     * if my_int != 0 {
     *   /* {block(variable)} */
     * }
     * ```
     */
    fun ifSet(
        shape: Shape,
        member: Symbol,
        variable: ValueExpression,
        block: RustWriter.(field: ValueExpression) -> Unit,
    ) {
        ifSome(member, variable) { inner -> ifNotDefault(shape, inner, block) }
    }

    fun listForEach(
        target: Shape,
        outerField: String,
        block: RustWriter.(field: String, target: ShapeId) -> Unit,
    ) {
        if (target is CollectionShape) {
            val derefName = safeName("inner")
            rustBlock("for $derefName in $outerField") {
                block(derefName, target.member.target)
            }
        } else {
            this.block(outerField, target.toShapeId())
        }
    }

    override fun toString(): String {
        val contents = super.toString()
        val preheader = if (preamble.isNotEmpty()) {
            val prewriter = RustWriter(filename, namespace, printWarning = false)
            preamble.forEach { it(prewriter) }
            prewriter.toString()
        } else null

        // Hack to support TOML: the [commentCharacter] is overridden to support writing TOML.
        val header = if (printWarning) {
            "$commentCharacter Code generated by software.amazon.smithy.rust.codegen.smithy-rs. DO NOT EDIT."
        } else null
        val useDecls = importContainer.toString().ifEmpty {
            null
        }
        return listOfNotNull(preheader, header, useDecls, contents).joinToString(separator = "\n", postfix = "\n")
    }

    fun format(r: Any) = formatter.apply(r, "")

    fun addDepsRecursively(symbol: Symbol) {
        addDependency(symbol)
        symbol.references.forEach { addDepsRecursively(it.symbol) }
    }

    /**
     * Generate RustDoc links, e.g. [`Abc`](crate::module::Abc)
     */
    inner class RustDocLinker : BiFunction<Any, String, String> {
        override fun apply(t: Any, u: String): String {
            return when (t) {
                is Symbol -> "[`${t.name}`](${docLink(t.rustType().qualifiedName())})"
                else -> throw CodegenException("Invalid type provided to RustDocLinker ($t) expected Symbol")
            }
        }
    }

    /**
     * Formatter to enable formatting any [writable] with the #W formatter.
     */
    inner class RustWriteableInjector : BiFunction<Any, String, String> {
        override fun apply(t: Any, u: String): String {
            @Suppress("UNCHECKED_CAST")
            val func =
                t as? Writable ?: throw CodegenException("RustWriteableInjector.apply choked on non-function t ($t)")
            val innerWriter = RustWriter(filename, namespace, printWarning = false)
            func(innerWriter)
            innerWriter.dependencies.forEach { addDependency(it) }
            return innerWriter.toString().trimEnd()
        }
    }

    inner class RustSymbolFormatter : BiFunction<Any, String, String> {
        override fun apply(t: Any, u: String): String {
            return when (t) {
                is RuntimeType -> {
                    t.dependency?.also { addDependency(it) }
                    // for now, use the fully qualified type name
                    t.fullyQualifiedName()
                }

                is Symbol -> {
                    addDepsRecursively(t)
                    t.rustType().render(fullyQualified = true)
                }

                is RustType -> {
                    t.render(fullyQualified = true)
                }

                is Function<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val func =
                        t as? Writable ?: throw CodegenException("Invalid function type (expected writable) ($t)")
                    val innerWriter = RustWriter(filename, namespace, printWarning = false)
                    func(innerWriter)
                    innerWriter.dependencies.forEach { addDependency(it) }
                    return innerWriter.toString().trimEnd()
                }

                else -> throw CodegenException("Invalid type provided to RustSymbolFormatter: $t")
                // escaping generates `##` sequences for all the common cases where
                // it will be run through templating, but in this context, we won't be escaped
            }.replace("##", "#")
        }
    }
}
