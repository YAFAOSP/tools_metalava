/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.metalava.model

import com.android.tools.metalava.NullnessMigration.Companion.findNullnessAnnotation
import com.android.tools.metalava.RECENTLY_NONNULL
import com.android.tools.metalava.RECENTLY_NULLABLE
import com.android.tools.metalava.model.visitors.ItemVisitor
import com.android.tools.metalava.model.visitors.TypeVisitor
import com.intellij.psi.PsiElement
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents a code element such as a package, a class, a method, a field, a parameter.
 *
 * This extra abstraction on top of PSI allows us to more model the API (and customize
 * visibility, which cannot always be done by looking at a particular piece of code and examining
 * visibility and @hide/@removed annotations: sometimes package private APIs are unhidden by
 * being used in public APIs for example.
 *
 * The abstraction also lets us back the model by an alternative implementation read from
 * signature files, to do compatibility checks.
 * */
interface Item {
    val codebase: Codebase

    /** Return the modifiers of this class */
    val modifiers: ModifierList

    /**
     * Whether this element was originally hidden with @hide/@Hide. The [hidden] property
     * tracks whether it is *actually* hidden, since elements can be unhidden via show annotations, etc.
     */
    var originallyHidden: Boolean

    /** Whether this element has been hidden with @hide/@Hide (or after propagation, in some containing class/pkg) */
    var hidden: Boolean

    var emit: Boolean

    fun parent(): Item?

    /** Recursive check to see if this item or any of its parents (containing class, containing package) are hidden */
    fun hidden(): Boolean {
        return hidden || parent()?.hidden() ?: false
    }

    /** Whether this element has been removed with @removed/@Remove (or after propagation, in some containing class) */
    var removed: Boolean

    /** True if this element has been marked deprecated */
    var deprecated: Boolean

    /** True if this element is only intended for documentation */
    var docOnly: Boolean

    /**
     * True if this is a synthetic element, such as the generated "value" and "valueOf" methods
     * in enums
     */
    val synthetic: Boolean

    /** True if this item is either hidden or removed */
    fun isHiddenOrRemoved(): Boolean = hidden || removed

    /** Visits this element using the given [visitor] */
    fun accept(visitor: ItemVisitor)

    /** Visits all types in this item hierarchy */
    fun acceptTypes(visitor: TypeVisitor)

    /** Get a mutable version of modifiers for this item */
    fun mutableModifiers(): MutableModifierList

    /** The javadoc/KDoc comment for this code element, if any. This is
     * the original content of the documentation, including lexical tokens
     * to begin, continue and end the comment (such as /+*).
     * See [fullyQualifiedDocumentation] to look up the documentation with
     * fully qualified references to classes.
     */
    var documentation: String

    /**
     * Looks up docs for the first instance of a specific javadoc tag having
     * the (optionally) provided value (e.g. parameter name).
     */
    fun findTagDocumentation(tag: String, value: String? = null): String?

    /**
     * A rank used for sorting. This allows signature files etc to
     * sort similar items by a natural order, if non-zero.
     * (Even though in signature files the elements are normally
     * sorted first logically (constructors, then methods, then fields)
     * and then alphabetically, this lets us preserve the source
     * ordering for example for overloaded methods of the same name,
     * where it's not clear that an alphabetical order (of each
     * parameter?) would be preferable.)
     */
    val sortingRank: Int

    /**
     * Add the given text to the documentation.
     *
     * If the [tagSection] is null, add the comment to the initial text block
     * of the description. Otherwise if it is "@return", add the comment
     * to the return value. Otherwise the [tagSection] is taken to be the
     * parameter name, and the comment added as parameter documentation
     * for the given parameter.
     */
    fun appendDocumentation(comment: String, tagSection: String? = null, append: Boolean = true)

    val isPublic: Boolean
    val isProtected: Boolean
    val isInternal: Boolean
    val isPackagePrivate: Boolean
    val isPrivate: Boolean

    // make sure these are implemented so we can place in maps:
    override fun equals(other: Any?): Boolean

    override fun hashCode(): Int

    /** Whether this member was cloned in from a super class or interface */
    fun isCloned(): Boolean

    /**
     * Returns true if this item requires nullness information (e.g. for a method
     * where either the return value or any of the parameters are non-primitives.
     * Note that it doesn't consider whether it already has nullness annotations;
     * for that see [hasNullnessInfo].
     */
    fun requiresNullnessInfo(): Boolean = false

    /**
     * Returns true if this item requires nullness information and supplies it
     * (for all items, e.g. if a method is partially annotated this method would
     * still return false)
     */
    fun hasNullnessInfo(): Boolean = false

    /**
     * Whether this item was loaded from the classpath (e.g. jar dependencies)
     * rather than be declared as source
     */
    fun isFromClassPath(): Boolean = false

    /** Is this element declared in Java (rather than Kotlin) ? */
    fun isJava(): Boolean = true

    /** Is this element declared in Kotlin (rather than Java) ? */
    fun isKotlin() = !isJava()

    fun hasShowAnnotation(): Boolean = modifiers.hasShowAnnotation()
    fun onlyShowForStubPurposes(): Boolean = modifiers.onlyShowForStubPurposes()
    fun hasHideAnnotation(): Boolean = modifiers.hasHideAnnotations()
    fun hasHideMetaAnnotation(): Boolean = modifiers.hasHideMetaAnnotations()

    /**
     * Same as [hasShowAnnotation], except if it's a method, take into account super methods'
     * annotations.
     *
     * Unlike classes or fields, methods implicitly inherits visibility annotations, and for
     * some visibility calculation we need to take it into account.
     * (See ShowAnnotationTest.`Methods inherit showAnnotations but fields and classes don't`.)
     */
    fun hasShowAnnotationInherited(): Boolean = hasShowAnnotation()

    /**
     * Same as [onlyShowForStubPurposes], except if it's a method,
     * take into account super methods' annotations.
     *
     * Unlike classes or fields, methods implicitly inherits visibility annotations, and for
     * some visibility calculation we need to take it into account.
     * (See ShowAnnotationTest.`Methods inherit showAnnotations but fields and classes don't`.)
     */
    fun onlyShowForStubPurposesInherited(): Boolean = onlyShowForStubPurposes()

    fun checkLevel(): Boolean {
        return modifiers.checkLevel()
    }

    fun sourceFile(): SourceFileItem? {
        var curr: Item? = this
        while (curr != null) {
            if (curr is ClassItem && curr.isTopLevelClass()) {
                return curr.getSourceFile()
            }
            curr = curr.parent()
        }

        return null
    }

    /** Returns the PSI element for this item, if any */
    fun psi(): PsiElement? = null

    /** Tag field used for DFS etc */
    var tag: Boolean

    /**
     * Returns the [documentation], but with fully qualified links (except for the same package, and
     * when turning a relative reference into a fully qualified reference, use the javadoc syntax
     * for continuing to display the relative text, e.g. instead of {@link java.util.List}, use
     * {@link java.util.List List}.
     */
    fun fullyQualifiedDocumentation(): String = documentation

    /** Expands the given documentation comment in the current name context */
    fun fullyQualifiedDocumentation(documentation: String): String = documentation

    /** Produces a user visible description of this item, including a label such as "class" or "field" */
    fun describe(capitalize: Boolean = false) = describe(this, capitalize)

    /**
     * Returns the package that contains this item. If [strict] is false, this will return self
     * if called on a package, otherwise it will return the containing package (e.g. "foo" for "foo.bar").
     * The parameter is ignored on other item types.
     */
    fun containingPackage(strict: Boolean = true): PackageItem?

    /**
     * Returns the class that contains this item. If [strict] is false, this will return self
     * if called on a class, otherwise it will return the outer class, if any. The parameter is
     * ignored on other item types.
     */
    fun containingClass(strict: Boolean = true): ClassItem?

    /**
     * Returns the associated type if any. For example, for a field, property or parameter,
     * this is the type of the variable; for a method, it's the return type.
     * For packages, classes and files, it's null.
     */
    fun type(): TypeItem?

    /**
     * Marks the nullability of this Item as Recent.
     * That is, replaces @Nullable/@NonNull with @RecentlyNullable/@RecentlyNonNull
     */
    fun markRecent() {
        val annotation = findNullnessAnnotation(this) ?: return
        // Nullness information change: Add migration annotation
        val annotationClass = if (annotation.isNullable()) RECENTLY_NULLABLE else RECENTLY_NONNULL

        val modifiers = mutableModifiers()
        modifiers.removeAnnotation(annotation)

        // Don't map annotation names - this would turn newly non null back into non null
        modifiers.addAnnotation(codebase.createAnnotation("@$annotationClass", this, mapName = false))
    }

    companion object {
        fun describe(item: Item, capitalize: Boolean = false): String {
            return when (item) {
                is PackageItem -> describe(item, capitalize = capitalize)
                is ClassItem -> describe(item, capitalize = capitalize)
                is FieldItem -> describe(item, capitalize = capitalize)
                is MethodItem -> describe(
                    item,
                    includeParameterNames = false,
                    includeParameterTypes = true,
                    capitalize = capitalize
                )
                is ParameterItem -> describe(
                    item,
                    includeParameterNames = true,
                    includeParameterTypes = true,
                    capitalize = capitalize
                )
                else -> item.toString()
            }
        }

        fun describe(
            item: MethodItem,
            includeParameterNames: Boolean = false,
            includeParameterTypes: Boolean = false,
            includeReturnValue: Boolean = false,
            capitalize: Boolean = false
        ): String {
            val builder = StringBuilder()
            if (item.isConstructor()) {
                builder.append(if (capitalize) "Constructor" else "constructor")
            } else {
                builder.append(if (capitalize) "Method" else "method")
            }
            builder.append(' ')
            if (includeReturnValue && !item.isConstructor()) {
                builder.append(item.returnType().toSimpleType())
                builder.append(' ')
            }
            appendMethodSignature(builder, item, includeParameterNames, includeParameterTypes)
            return builder.toString()
        }

        fun describe(
            item: ParameterItem,
            includeParameterNames: Boolean = false,
            includeParameterTypes: Boolean = false,
            capitalize: Boolean = false
        ): String {
            val builder = StringBuilder()
            builder.append(if (capitalize) "Parameter" else "parameter")
            builder.append(' ')
            builder.append(item.name())
            builder.append(" in ")
            val method = item.containingMethod()
            appendMethodSignature(builder, method, includeParameterNames, includeParameterTypes)
            return builder.toString()
        }

        private fun appendMethodSignature(
            builder: StringBuilder,
            item: MethodItem,
            includeParameterNames: Boolean,
            includeParameterTypes: Boolean
        ) {
            builder.append(item.containingClass().qualifiedName())
            if (!item.isConstructor()) {
                builder.append('.')
                builder.append(item.name())
            }
            if (includeParameterNames || includeParameterTypes) {
                builder.append('(')
                var first = true
                for (parameter in item.parameters()) {
                    if (first) {
                        first = false
                    } else {
                        builder.append(',')
                        if (includeParameterNames && includeParameterTypes) {
                            builder.append(' ')
                        }
                    }
                    if (includeParameterTypes) {
                        builder.append(parameter.type().toSimpleType())
                        if (includeParameterNames) {
                            builder.append(' ')
                        }
                    }
                    if (includeParameterNames) {
                        builder.append(parameter.publicName() ?: parameter.name())
                    }
                }
                builder.append(')')
            }
        }

        private fun describe(item: FieldItem, capitalize: Boolean = false): String {
            return if (item.isEnumConstant()) {
                "${if (capitalize) "Enum" else "enum"} constant ${item.containingClass().qualifiedName()}.${item.name()}"
            } else {
                "${if (capitalize) "Field" else "field"} ${item.containingClass().qualifiedName()}.${item.name()}"
            }
        }

        private fun describe(item: ClassItem, capitalize: Boolean = false): String {
            return "${if (capitalize) "Class" else "class"} ${item.qualifiedName()}"
        }

        private fun describe(item: PackageItem, capitalize: Boolean = false): String {
            return "${if (capitalize) "Package" else "package"} ${item.qualifiedName()}"
        }
    }
}

abstract class DefaultItem(override val sortingRank: Int = nextRank.getAndIncrement()) : Item {
    override val isPublic: Boolean get() = modifiers.isPublic()
    override val isProtected: Boolean get() = modifiers.isProtected()
    override val isInternal: Boolean
        get() = modifiers.getVisibilityLevel() == VisibilityLevel.INTERNAL
    override val isPackagePrivate: Boolean get() = modifiers.isPackagePrivate()
    override val isPrivate: Boolean get() = modifiers.isPrivate()

    override var emit = true
    override var tag: Boolean = false

    companion object {
        private var nextRank = AtomicInteger()
    }
}
