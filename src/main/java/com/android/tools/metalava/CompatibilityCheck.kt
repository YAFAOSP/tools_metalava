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

package com.android.tools.metalava

import com.android.tools.metalava.Issues.Issue
import com.android.tools.metalava.NullnessMigration.Companion.findNullnessAnnotation
import com.android.tools.metalava.NullnessMigration.Companion.isNullable
import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.Item.Companion.describe
import com.android.tools.metalava.model.MergedCodebase
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.PackageItem
import com.android.tools.metalava.model.ParameterItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.configuration
import com.android.tools.metalava.model.psi.PsiItem
import com.android.tools.metalava.model.text.TextCodebase
import com.intellij.psi.PsiField
import java.io.File
import java.util.function.Predicate

/**
 * Compares the current API with a previous version and makes sure
 * the changes are compatible. For example, you can make a previously
 * nullable parameter non null, but not vice versa.
 *
 * TODO: Only allow nullness changes on final classes!
 */
class CompatibilityCheck(
    val filterReference: Predicate<Item>,
    private val oldCodebase: Codebase,
    private val apiType: ApiType,
    private val base: Codebase? = null,
    private val reporter: Reporter
) : ComparisonVisitor() {

    /**
     * Request for compatibility checks.
     * [file] represents the signature file to be checked. [apiType] represents which
     * part of the API should be checked, [releaseType] represents what kind of codebase
     * we are comparing it against.
     */
    data class CheckRequest(
        val file: File,
        val apiType: ApiType
    ) {
        override fun toString(): String {
            return "--check-compatibility:${apiType.flagName}:released $file"
        }
    }

    /** In old signature files, methods inherited from hidden super classes
     * are not included. An example of this is StringBuilder.setLength.
     * More details about this are listed in Compatibility.skipInheritedMethods.
     * We may see these in the codebase but not in the (old) signature files,
     * so in these cases we want to ignore certain changes such as considering
     * StringBuilder.setLength a newly added method.
     */
    private val comparingWithPartialSignatures = oldCodebase is TextCodebase && oldCodebase.format == FileFormat.V1

    var foundProblems = false

    private fun containingMethod(item: Item): MethodItem? {
        if (item is MethodItem) {
            return item
        }
        if (item is ParameterItem) {
            return item.containingMethod()
        }
        return null
    }

    private fun compareNullability(old: Item, new: Item) {
        val oldMethod = containingMethod(old)
        val newMethod = containingMethod(new)

        if (oldMethod != null && newMethod != null) {
            if (oldMethod.containingClass().qualifiedName() != newMethod.containingClass().qualifiedName() || ((oldMethod.inheritedFrom != null) != (newMethod.inheritedFrom != null))) {
                // If the old method and new method are defined on different classes, then it's possible
                // that the old method was previously overridden and we omitted it.
                // So, if the old method and new methods are defined on different classes, then we skip
                // nullability checks
                return
            }
        }
        // Should not remove nullness information
        // Can't change information incompatibly
        val oldNullnessAnnotation = findNullnessAnnotation(old)
        if (oldNullnessAnnotation != null) {
            val newNullnessAnnotation = findNullnessAnnotation(new)
            if (newNullnessAnnotation == null) {
                val implicitNullness = AnnotationItem.getImplicitNullness(new)
                if (implicitNullness == true && isNullable(old)) {
                    return
                }
                if (implicitNullness == false && !isNullable(old)) {
                    return
                }
                val name = AnnotationItem.simpleName(oldNullnessAnnotation)
                if (old.type()?.primitive == true) {
                    return
                }
                report(
                    Issues.INVALID_NULL_CONVERSION, new,
                    "Attempted to remove $name annotation from ${describe(new)}"
                )
            } else {
                val oldNullable = isNullable(old)
                val newNullable = isNullable(new)
                if (oldNullable != newNullable) {
                    // You can change a parameter from nonnull to nullable
                    // You can change a method from nullable to nonnull
                    // You cannot change a parameter from nullable to nonnull
                    // You cannot change a method from nonnull to nullable
                    if (oldNullable && old is ParameterItem) {
                        report(
                            Issues.INVALID_NULL_CONVERSION,
                            new,
                            "Attempted to change parameter from @Nullable to @NonNull: " +
                                "incompatible change for ${describe(new)}"
                        )
                    } else if (!oldNullable && old is MethodItem) {
                        report(
                            Issues.INVALID_NULL_CONVERSION,
                            new,
                            "Attempted to change method return from @NonNull to @Nullable: " +
                                "incompatible change for ${describe(new)}"
                        )
                    }
                }
            }
        }
    }

    override fun compare(old: Item, new: Item) {
        val oldModifiers = old.modifiers
        val newModifiers = new.modifiers
        if (oldModifiers.isOperator() && !newModifiers.isOperator()) {
            report(
                Issues.OPERATOR_REMOVAL,
                new,
                "Cannot remove `operator` modifier from ${describe(new)}: Incompatible change"
            )
        }

        if (oldModifiers.isInfix() && !newModifiers.isInfix()) {
            report(
                Issues.INFIX_REMOVAL,
                new,
                "Cannot remove `infix` modifier from ${describe(new)}: Incompatible change"
            )
        }

        compareNullability(old, new)
    }

    override fun compare(old: ParameterItem, new: ParameterItem) {
        val prevName = old.publicName()
        val newName = new.publicName()
        if (prevName != null) {
            if (newName == null) {
                report(
                    Issues.PARAMETER_NAME_CHANGE,
                    new,
                    "Attempted to remove parameter name from ${describe(new)}"
                )
            } else if (newName != prevName) {
                report(
                    Issues.PARAMETER_NAME_CHANGE,
                    new,
                    "Attempted to change parameter name from $prevName to $newName in ${describe(new.containingMethod())}"
                )
            }
        }

        if (old.hasDefaultValue() && !new.hasDefaultValue()) {
            report(
                Issues.DEFAULT_VALUE_CHANGE,
                new,
                "Attempted to remove default value from ${describe(new)}"
            )
        }

        if (old.isVarArgs() && !new.isVarArgs()) {
            // In Java, changing from array to varargs is a compatible change, but
            // not the other way around. Kotlin is the same, though in Kotlin
            // you have to change the parameter type as well to an array type; assuming you
            // do that it's the same situation as Java; otherwise the normal
            // signature check will catch the incompatibility.
            report(
                Issues.VARARG_REMOVAL,
                new,
                "Changing from varargs to array is an incompatible change: ${describe(
                    new,
                    includeParameterTypes = true,
                    includeParameterNames = true
                )}"
            )
        }
    }

    override fun compare(old: ClassItem, new: ClassItem) {
        val oldModifiers = old.modifiers
        val newModifiers = new.modifiers

        if (old.isInterface() != new.isInterface() ||
            old.isEnum() != new.isEnum() ||
            old.isAnnotationType() != new.isAnnotationType()
        ) {
            report(
                Issues.CHANGED_CLASS, new, "${describe(new, capitalize = true)} changed class/interface declaration"
            )
            return // Avoid further warnings like "has changed abstract qualifier" which is implicit in this change
        }

        for (iface in old.interfaceTypes()) {
            val qualifiedName = iface.asClass()?.qualifiedName() ?: continue
            if (!new.implements(qualifiedName)) {
                report(
                    Issues.REMOVED_INTERFACE, new, "${describe(old, capitalize = true)} no longer implements $iface"
                )
            }
        }

        for (iface in new.filteredInterfaceTypes(filterReference)) {
            val qualifiedName = iface.asClass()?.qualifiedName() ?: continue
            if (!old.implements(qualifiedName)) {
                report(
                    Issues.ADDED_INTERFACE, new, "Added interface $iface to class ${describe(old)}"
                )
            }
        }

        if (!oldModifiers.isSealed() && newModifiers.isSealed()) {
            report(Issues.ADD_SEALED, new, "Cannot add 'sealed' modifier to ${describe(new)}: Incompatible change")
        } else if (old.isClass() && !oldModifiers.isAbstract() && newModifiers.isAbstract()) {
            report(
                Issues.CHANGED_ABSTRACT, new, "${describe(new, capitalize = true)} changed 'abstract' qualifier"
            )
        }

        if (oldModifiers.isFunctional() && !newModifiers.isFunctional()) {
            report(
                Issues.FUN_REMOVAL,
                new,
                "Cannot remove 'fun' modifier from ${describe(new)}: source incompatible change"
            )
        }

        // Check for changes in final & static, but not in enums (since PSI and signature files differ
        // a bit in whether they include these for enums
        if (!new.isEnum()) {
            if (!oldModifiers.isFinal() && newModifiers.isFinal()) {
                // It is safe to make a class final if it did not previously have any public
                // constructors because it was impossible for an application to create a subclass.
                if (old.constructors().filter { it.isPublic || it.isProtected }.none()) {
                    report(
                        Issues.ADDED_FINAL_UNINSTANTIABLE, new,
                        "${describe(
                            new,
                            capitalize = true
                        )} added 'final' qualifier but was previously uninstantiable and therefore could not be subclassed"
                    )
                } else {
                    report(
                        Issues.ADDED_FINAL, new, "${describe(new, capitalize = true)} added 'final' qualifier"
                    )
                }
            }

            if (oldModifiers.isStatic() != newModifiers.isStatic()) {
                val hasPublicConstructor = old.constructors().any { it.isPublic }
                if (!old.isInnerClass() || hasPublicConstructor) {
                    report(
                        Issues.CHANGED_STATIC,
                        new,
                        "${describe(new, capitalize = true)} changed 'static' qualifier"
                    )
                }
            }
        }

        val oldVisibility = oldModifiers.getVisibilityString()
        val newVisibility = newModifiers.getVisibilityString()
        if (oldVisibility != newVisibility) {
            // TODO: Use newModifiers.asAccessibleAs(oldModifiers) to provide different error messages
            // based on whether this seems like a reasonable change, e.g. making a private or final method more
            // accessible is fine (no overridden method affected) but not making methods less accessible etc
            report(
                Issues.CHANGED_SCOPE, new,
                "${describe(new, capitalize = true)} changed visibility from $oldVisibility to $newVisibility"
            )
        }

        if (!old.deprecated == new.deprecated) {
            report(
                Issues.CHANGED_DEPRECATED, new,
                "${describe(
                    new,
                    capitalize = true
                )} has changed deprecation state ${old.deprecated} --> ${new.deprecated}"
            )
        }

        val oldSuperClassName = old.superClass()?.qualifiedName()
        if (oldSuperClassName != null) { // java.lang.Object can't have a superclass.
            if (!new.extends(oldSuperClassName)) {
                report(
                    Issues.CHANGED_SUPERCLASS, new,
                    "${describe(
                        new,
                        capitalize = true
                    )} superclass changed from $oldSuperClassName to ${new.superClass()?.qualifiedName()}"
                )
            }
        }

        if (old.hasTypeVariables() || new.hasTypeVariables()) {
            val oldTypeParamsCount = old.typeParameterList().typeParameterCount()
            val newTypeParamsCount = new.typeParameterList().typeParameterCount()
            if (oldTypeParamsCount > 0 && oldTypeParamsCount != newTypeParamsCount) {
                report(
                    Issues.CHANGED_TYPE, new,
                    "${describe(
                        old,
                        capitalize = true
                    )} changed number of type parameters from $oldTypeParamsCount to $newTypeParamsCount"
                )
            }
        }
    }

    override fun compare(old: MethodItem, new: MethodItem) {
        val oldModifiers = old.modifiers
        val newModifiers = new.modifiers

        val oldReturnType = old.returnType()
        val newReturnType = new.returnType()
        if (!new.isConstructor()) {
            val oldTypeParameter = oldReturnType.asTypeParameter(old)
            val newTypeParameter = newReturnType.asTypeParameter(new)
            var compatible = true
            if (oldTypeParameter == null &&
                newTypeParameter == null
            ) {
                if (oldReturnType != newReturnType ||
                    oldReturnType.arrayDimensions() != newReturnType.arrayDimensions()
                ) {
                    compatible = false
                }
            } else if (oldTypeParameter == null && newTypeParameter != null) {
                val constraints = newTypeParameter.typeBounds()
                for (constraint in constraints) {
                    val oldClass = oldReturnType.asClass()
                    val newClass = constraint.asClass()
                    if (oldClass == null || newClass == null || !oldClass.extendsOrImplements(newClass.qualifiedName())) {
                        compatible = false
                    }
                }
            } else if (oldTypeParameter != null && newTypeParameter == null) {
                // It's never valid to go from being a parameterized type to not being one.
                // This would drop the implicit cast breaking backwards compatibility.
                compatible = false
            } else {
                // If both return types are parameterized then the constraints must be
                // exactly the same.
                val oldConstraints = oldTypeParameter?.typeBounds() ?: emptyList()
                val newConstraints = newTypeParameter?.typeBounds() ?: emptyList()
                if (oldConstraints.size != newConstraints.size ||
                    newConstraints != oldConstraints
                ) {
                    val oldTypeString = describeBounds(oldReturnType, oldConstraints)
                    val newTypeString = describeBounds(newReturnType, newConstraints)
                    val message =
                        "${describe(
                            new,
                            capitalize = true
                        )} has changed return type from $oldTypeString to $newTypeString"

                    report(Issues.CHANGED_TYPE, new, message)
                    return
                }
            }

            if (!compatible) {
                var oldTypeString = oldReturnType.toSimpleType()
                var newTypeString = newReturnType.toSimpleType()
                // Typically, show short type names like "String" if they're distinct (instead of long type names like
                // "java.util.Set<T!>")
                if (oldTypeString == newTypeString) {
                    // If the short names aren't unique, then show full type names like "java.util.Set<T!>"
                    oldTypeString = oldReturnType.toString()
                    newTypeString = newReturnType.toString()
                }
                val message =
                    "${describe(new, capitalize = true)} has changed return type from $oldTypeString to $newTypeString"
                report(Issues.CHANGED_TYPE, new, message)
            }

            // Annotation methods
            if (
                new.containingClass().isAnnotationType() &&
                old.containingClass().isAnnotationType() &&
                new.defaultValue() != old.defaultValue()
            ) {
                val prevValue = old.defaultValue()
                val prevString = if (prevValue.isEmpty()) {
                    "nothing"
                } else {
                    prevValue
                }

                val newValue = new.defaultValue()
                val newString = if (newValue.isEmpty()) {
                    "nothing"
                } else {
                    newValue
                }
                val message = "${describe(
                    new,
                    capitalize = true
                )} has changed value from $prevString to $newString"

                // Adding a default value to an annotation method is safe
                val annotationMethodAddingDefaultValue =
                    new.containingClass().isAnnotationType() && old.defaultValue().isEmpty()

                if (!annotationMethodAddingDefaultValue) {
                    report(Issues.CHANGED_VALUE, new, message)
                }
            }
        }

        // Check for changes in abstract, but only for regular classes; older signature files
        // sometimes describe interface methods as abstract
        if (new.containingClass().isClass()) {
            if (!oldModifiers.isAbstract() && newModifiers.isAbstract() &&
                // In old signature files, overridden methods of abstract methods declared
                // in super classes are sometimes omitted by doclava. This means that the method
                // looks (from the signature file perspective) like it has not been implemented,
                // whereas in reality it has. For just one example of this, consider
                // FragmentBreadCrumbs.onLayout: it's a concrete implementation in that class
                // of the inherited method from ViewGroup. However, in the signature file,
                // FragmentBreadCrumbs does not list this method; it's only listed (as abstract)
                // in the super class. In this scenario, the compatibility check would believe
                // the old method in FragmentBreadCrumbs is abstract and the new method is not,
                // which is not the case. Therefore, if the old method is coming from a signature
                // file based codebase with an old format, we omit abstract change warnings.
                // The reverse situation can also happen: AbstractSequentialList defines listIterator
                // as abstract, but it's not recorded as abstract in the signature files anywhere,
                // so we treat this as a nearly abstract method, which it is not.
                (old.inheritedFrom == null || !comparingWithPartialSignatures)
            ) {
                report(
                    Issues.CHANGED_ABSTRACT, new, "${describe(new, capitalize = true)} has changed 'abstract' qualifier"
                )
            }
        }

        if (new.containingClass().isInterface() || new.containingClass().isAnnotationType()) {
            if (oldModifiers.isDefault() && newModifiers.isAbstract()) {
                report(
                    Issues.CHANGED_DEFAULT, new, "${describe(new, capitalize = true)} has changed 'default' qualifier"
                )
            }
        }

        if (oldModifiers.isNative() != newModifiers.isNative()) {
            report(
                Issues.CHANGED_NATIVE, new, "${describe(new, capitalize = true)} has changed 'native' qualifier"
            )
        }

        // Check changes to final modifier. But skip enums where it varies between signature files and PSI
        // whether the methods are considered final.
        if (!new.containingClass().isEnum() && !oldModifiers.isStatic()) {
            // Skip changes in final; modifier change could come from inherited
            // implementation from hidden super class. An example of this
            // is SpannableString.charAt whose implementation comes from
            // SpannableStringInternal.
            if (old.inheritedFrom == null || !comparingWithPartialSignatures) {
                // Compiler-generated methods vary in their 'final' qualifier between versions of
                // the compiler, so this check needs to be quite narrow. A change in 'final'
                // status of a method is only relevant if (a) the method is not declared 'static'
                // and (b) the method is not already inferred to be 'final' by virtue of its class.
                if (!old.isEffectivelyFinal() && new.isEffectivelyFinal()) {
                    report(
                        Issues.ADDED_FINAL,
                        new,
                        "${describe(new, capitalize = true)} has added 'final' qualifier"
                    )
                } else if (old.isEffectivelyFinal() && !new.isEffectivelyFinal()) {
                    // Disallowed removing final: If an app inherits the class and starts overriding
                    // the method it's going to crash on earlier versions where the method is final
                    // It doesn't break compatibility in the strict sense, but does make it very
                    // difficult to extend this method in practice.
                    report(
                        Issues.REMOVED_FINAL,
                        new,
                        "${describe(new, capitalize = true)} has removed 'final' qualifier"
                    )
                }
            }
        }

        if (oldModifiers.isStatic() != newModifiers.isStatic()) {
            report(
                Issues.CHANGED_STATIC, new, "${describe(new, capitalize = true)} has changed 'static' qualifier"
            )
        }

        val oldVisibility = oldModifiers.getVisibilityString()
        val newVisibility = newModifiers.getVisibilityString()
        if (oldVisibility != newVisibility) {
            // Only report issue if the change is a decrease in access; e.g. public -> protected
            if (!newModifiers.asAccessibleAs(oldModifiers)) {
                report(
                    Issues.CHANGED_SCOPE, new,
                    "${describe(new, capitalize = true)} changed visibility from $oldVisibility to $newVisibility"
                )
            }
        }

        if (old.deprecated != new.deprecated) {
            report(
                Issues.CHANGED_DEPRECATED, new,
                "${describe(
                    new,
                    capitalize = true
                )} has changed deprecation state ${old.deprecated} --> ${new.deprecated}"
            )
        }

        /*
        // see JLS 3 13.4.20 "Adding or deleting a synchronized modifier of a method does not break "
        // "compatibility with existing binaries."
        if (oldModifiers.isSynchronized() != newModifiers.isSynchronized()) {
            report(
                Errors.CHANGED_SYNCHRONIZED, new,
                "${describe(
                    new,
                    capitalize = true
                )} has changed 'synchronized' qualifier from ${oldModifiers.isSynchronized()} to ${newModifiers.isSynchronized()}"
            )
        }
        */

        for (exception in old.throwsTypes()) {
            if (!new.throws(exception.qualifiedName())) {
                // exclude 'throws' changes to finalize() overrides with no arguments
                if (old.name() != "finalize" || old.parameters().isNotEmpty()) {
                    report(
                        Issues.CHANGED_THROWS, new,
                        "${describe(new, capitalize = true)} no longer throws exception ${exception.qualifiedName()}"
                    )
                }
            }
        }

        for (exec in new.filteredThrowsTypes(filterReference)) {
            if (!old.throws(exec.qualifiedName())) {
                // exclude 'throws' changes to finalize() overrides with no arguments
                if (!(old.name() == "finalize" && old.parameters().isEmpty()) &&
                    // exclude cases where throws clause was missing in signatures from
                    // old enum methods
                    !old.isEnumSyntheticMethod()
                ) {
                    val message = "${describe(new, capitalize = true)} added thrown exception ${exec.qualifiedName()}"
                    report(Issues.CHANGED_THROWS, new, message)
                }
            }
        }

        if (new.modifiers.isInline()) {
            val oldTypes = old.typeParameterList().typeParameters()
            val newTypes = new.typeParameterList().typeParameters()
            for (i in oldTypes.indices) {
                if (i == newTypes.size) {
                    break
                }
                if (newTypes[i].isReified() && !oldTypes[i].isReified()) {
                    val message = "${describe(
                        new,
                        capitalize = true
                    )} made type variable ${newTypes[i].simpleName()} reified: incompatible change"
                    report(Issues.ADDED_REIFIED, new, message)
                }
            }
        }
    }

    private fun describeBounds(
        type: TypeItem,
        constraints: List<TypeItem>
    ): String {
        return type.toSimpleType() +
            if (constraints.isEmpty()) {
                " (extends java.lang.Object)"
            } else {
                " (extends ${constraints.joinToString(separator = " & ") { it.toTypeString() }})"
            }
    }

    override fun compare(old: FieldItem, new: FieldItem) {
        val oldModifiers = old.modifiers
        val newModifiers = new.modifiers

        if (!old.isEnumConstant()) {
            val oldType = old.type()
            val newType = new.type()
            if (oldType != newType) {
                val message = "${describe(new, capitalize = true)} has changed type from $oldType to $newType"
                report(Issues.CHANGED_TYPE, new, message)
            } else if (!old.hasSameValue(new)) {
                val prevValue = old.initialValue()
                val prevString = if (prevValue == null && !old.modifiers.isFinal()) {
                    "nothing/not constant"
                } else {
                    prevValue
                }

                val newValue = new.initialValue()
                val newString = if (newValue is PsiField) {
                    newValue.containingClass?.qualifiedName + "." + newValue.name
                } else {
                    newValue
                }
                val message = "${describe(
                    new,
                    capitalize = true
                )} has changed value from $prevString to $newString"

                report(Issues.CHANGED_VALUE, new, message)
            }
        }

        val oldVisibility = oldModifiers.getVisibilityString()
        val newVisibility = newModifiers.getVisibilityString()
        if (oldVisibility != newVisibility) {
            // Only report issue if the change is a decrease in access; e.g. public -> protected
            if (!newModifiers.asAccessibleAs(oldModifiers)) {
                report(
                    Issues.CHANGED_SCOPE, new,
                    "${
                    describe(
                        new,
                        capitalize = true
                    )
                    } changed visibility from $oldVisibility to $newVisibility"
                )
            }
        }

        if (oldModifiers.isStatic() != newModifiers.isStatic()) {
            report(
                Issues.CHANGED_STATIC, new, "${describe(new, capitalize = true)} has changed 'static' qualifier"
            )
        }

        if (!oldModifiers.isFinal() && newModifiers.isFinal()) {
            report(
                Issues.ADDED_FINAL, new, "${describe(new, capitalize = true)} has added 'final' qualifier"
            )
        } else if (
            // Final can't be removed if field is static with compile-time constant
            oldModifiers.isFinal() && !newModifiers.isFinal() &&
            oldModifiers.isStatic() && old.initialValue() != null
        ) {
            report(
                Issues.REMOVED_FINAL, new, "${describe(new, capitalize = true)} has removed 'final' qualifier"
            )
        }

        if (oldModifiers.isVolatile() != newModifiers.isVolatile()) {
            report(
                Issues.CHANGED_VOLATILE, new, "${describe(new, capitalize = true)} has changed 'volatile' qualifier"
            )
        }

        if (old.deprecated != new.deprecated) {
            report(
                Issues.CHANGED_DEPRECATED, new,
                "${describe(
                    new,
                    capitalize = true
                )} has changed deprecation state ${old.deprecated} --> ${new.deprecated}"
            )
        }
    }

    private fun handleAdded(issue: Issue, item: Item) {
        if (item.originallyHidden) {
            // This is an element which is hidden but is referenced from
            // some public API. This is an error, but some existing code
            // is doing this. This is not an API addition.
            return
        }

        if (!filterReference.test(item)) {
            // This item is something we weren't asked to verify
            return
        }

        var message = "Added ${describe(item)}"

        // Clarify error message for removed API to make it less ambiguous
        if (apiType == ApiType.REMOVED) {
            message += " to the removed API"
        } else if (options.showAnnotations.isNotEmpty()) {
            if (options.showAnnotations.matchesSuffix("SystemApi")) {
                message += " to the system API"
            } else if (options.showAnnotations.matchesSuffix("TestApi")) {
                message += " to the test API"
            }
        }

        // In some cases we run the comparison on signature files
        // generated into the temp directory, but in these cases
        // try to report the item against the real item in the API instead
        val equivalent = findBaseItem(item)
        if (equivalent != null) {
            report(issue, equivalent, message)
            return
        }

        report(issue, item, message)
    }

    private fun handleRemoved(issue: Issue, item: Item) {
        if (!item.emit) {
            // It's a stub; this can happen when analyzing partial APIs
            // such as a signature file for a library referencing types
            // from the upstream library dependencies.
            return
        }

        report(issue, item, "Removed ${if (item.deprecated) "deprecated " else ""}${describe(item)}")
    }

    private fun findBaseItem(
        item: Item
    ): Item? {
        base ?: return null

        return when (item) {
            is PackageItem -> base.findPackage(item.qualifiedName())
            is ClassItem -> base.findClass(item.qualifiedName())
            is MethodItem -> base.findClass(item.containingClass().qualifiedName())?.findMethod(
                item,
                includeSuperClasses = true,
                includeInterfaces = true
            )
            is FieldItem -> base.findClass(item.containingClass().qualifiedName())?.findField(item.name())
            else -> null
        }
    }

    override fun added(new: PackageItem) {
        handleAdded(Issues.ADDED_PACKAGE, new)
    }

    override fun added(new: ClassItem) {
        val error = if (new.isInterface()) {
            Issues.ADDED_INTERFACE
        } else {
            Issues.ADDED_CLASS
        }
        handleAdded(error, new)
    }

    override fun added(new: MethodItem) {
        // In old signature files, methods inherited from hidden super classes
        // are not included. An example of this is StringBuilder.setLength.
        // More details about this are listed in Compatibility.skipInheritedMethods.
        // We may see these in the codebase but not in the (old) signature files,
        // so skip these -- they're not really "added".
        if (new.inheritedFrom != null && comparingWithPartialSignatures) {
            return
        }

        // *Overriding* methods from super classes that are outside the
        // API is OK (e.g. overriding toString() from java.lang.Object)
        val superMethods = new.superMethods()
        for (superMethod in superMethods) {
            if (superMethod.isFromClassPath()) {
                return
            }
        }

        // Do not fail if this "new" method is really an override of an
        // existing superclass method, but we should fail if this is overriding
        // an abstract method, because method's abstractness affects how users use it.
        // See if there's a member from inherited class
        val inherited = if (new.isConstructor()) {
            null
        } else {
            new.containingClass().findMethod(
                new,
                includeSuperClasses = true,
                includeInterfaces = false
            )
        }

        // Builtin annotation methods: just a difference in signature file
        if (new.isEnumSyntheticMethod()) {
            return
        }

        // In old signature files, annotation methods are missing! This will show up as an added method.
        if (new.containingClass().isAnnotationType() && oldCodebase is TextCodebase && oldCodebase.format == FileFormat.V1) {
            return
        }

        // In most cases it is not permitted to add a new method to an interface, even with a
        // default implementation because it could could create ambiguity if client code implements
        // two interfaces that each now define methods with the same signature.
        // Annotation types cannot implement other interfaces, however, so it is permitted to add
        // add new default methods to annotation types.
        if (new.containingClass().isAnnotationType() && new.hasDefaultValue()) {
            return
        }

        if (inherited == null || inherited == new || !inherited.modifiers.isAbstract()) {
            val error = when {
                new.modifiers.isAbstract() -> Issues.ADDED_ABSTRACT_METHOD
                new.containingClass().isInterface() -> when {
                    new.modifiers.isStatic() -> Issues.ADDED_METHOD
                    new.modifiers.isDefault() -> {
                        // Hack to always mark added Kotlin interface methods as abstract until
                        // we properly support JVM default methods for Kotlin. This has to check
                        // if it's a PsiItem because TextItem doesn't support isKotlin.
                        //
                        // TODO(b/200077254): Remove Kotlin special case
                        if (new is PsiItem && new.isKotlin()) {
                            Issues.ADDED_ABSTRACT_METHOD
                        } else {
                            Issues.ADDED_METHOD
                        }
                    }
                    else -> Issues.ADDED_ABSTRACT_METHOD
                }
                else -> Issues.ADDED_METHOD
            }
            handleAdded(error, new)
        }
    }

    override fun added(new: FieldItem) {
        if (new.inheritedFrom != null && comparingWithPartialSignatures) {
            return
        }

        handleAdded(Issues.ADDED_FIELD, new)
    }

    override fun removed(old: PackageItem, from: Item?) {
        handleRemoved(Issues.REMOVED_PACKAGE, old)
    }

    override fun removed(old: ClassItem, from: Item?) {
        val error = when {
            old.isInterface() -> Issues.REMOVED_INTERFACE
            old.deprecated -> Issues.REMOVED_DEPRECATED_CLASS
            else -> Issues.REMOVED_CLASS
        }

        handleRemoved(error, old)
    }

    override fun removed(old: MethodItem, from: ClassItem?) {
        // See if there's a member from inherited class
        val inherited = if (old.isConstructor()) {
            null
        } else {
            // This can also return self, specially handled below
            from?.findMethod(
                old,
                includeSuperClasses = true,
                includeInterfaces = from.isInterface()
            )
        }
        if (inherited == null || inherited != old && inherited.isHiddenOrRemoved()) {
            val error = if (old.deprecated) Issues.REMOVED_DEPRECATED_METHOD else Issues.REMOVED_METHOD
            handleRemoved(error, old)
        }
    }

    override fun removed(old: FieldItem, from: ClassItem?) {
        val inherited = from?.findField(
            old.name(),
            includeSuperClasses = true,
            includeInterfaces = from.isInterface()
        )
        if (inherited == null) {
            val error = if (old.deprecated) Issues.REMOVED_DEPRECATED_FIELD else Issues.REMOVED_FIELD
            handleRemoved(error, old)
        }
    }

    private fun report(
        issue: Issue,
        item: Item,
        message: String
    ) {
        if (reporter.report(issue, item, message) && configuration.getSeverity(issue) == Severity.ERROR) {
            foundProblems = true
        }
    }

    companion object {
        fun checkCompatibility(
            newCodebase: Codebase,
            oldCodebase: Codebase,
            apiType: ApiType,
            baseApi: Codebase? = null,
        ) {
            val filter = apiType.getReferenceFilter()
                .or(apiType.getEmitFilter())
                .or(ApiType.PUBLIC_API.getReferenceFilter())
                .or(ApiType.PUBLIC_API.getEmitFilter())

            val checker = CompatibilityCheck(filter, oldCodebase, apiType, baseApi, options.reporterCompatibilityReleased)

            val oldFullCodebase = if (options.showUnannotated && apiType == ApiType.PUBLIC_API) {
                MergedCodebase(listOfNotNull(oldCodebase, baseApi))
            } else {
                // To avoid issues with partial oldCodeBase we fill gaps with newCodebase, the
                // first parameter is master, so we don't change values of oldCodeBase
                MergedCodebase(listOfNotNull(oldCodebase, newCodebase))
            }
            val newFullCodebase = MergedCodebase(listOfNotNull(newCodebase, baseApi))

            CodebaseComparator().compare(checker, oldFullCodebase, newFullCodebase, filter)

            val message = "Found compatibility problems checking " +
                "the ${apiType.displayName} API (${newCodebase.location}) against the API in ${oldCodebase.location}"

            if (checker.foundProblems) {
                throw DriverException(exitCode = -1, stderr = message)
            }
        }
    }
}
