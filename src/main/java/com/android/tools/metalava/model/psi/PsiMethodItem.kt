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

package com.android.tools.metalava.model.psi

import com.android.tools.metalava.model.AnnotationTarget
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.ModifierList
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeParameterList
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTypesUtil
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.uast.UAnnotationMethod
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UThrowExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.io.StringWriter

open class PsiMethodItem(
    override val codebase: PsiBasedCodebase,
    val psiMethod: PsiMethod,
    private val containingClass: PsiClassItem,
    private val name: String,
    modifiers: PsiModifierItem,
    documentation: String,
    private val returnType: PsiTypeItem,
    private val parameters: List<PsiParameterItem>
) :
    PsiItem(
        codebase = codebase,
        modifiers = modifiers,
        documentation = documentation,
        element = psiMethod
    ),
    MethodItem {

    init {
        for (parameter in parameters) {
            @Suppress("LeakingThis")
            parameter.containingMethod = this
        }
    }

    /**
     * If this item was created by filtering down a different codebase, this temporarily
     * points to the original item during construction. This is used to let us initialize
     * for example throws lists later, when all classes in the codebase have been
     * initialized.
     */
    internal var source: PsiMethodItem? = null

    override var inheritedMethod: Boolean = false
    override var inheritedFrom: ClassItem? = null

    override var property: PsiPropertyItem? = null

    override fun name(): String = name
    override fun containingClass(): PsiClassItem = containingClass

    override fun equals(other: Any?): Boolean {
        // TODO: Allow mix and matching with other MethodItems?
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PsiMethodItem

        if (psiMethod != other.psiMethod) return false

        return true
    }

    override fun hashCode(): Int {
        return psiMethod.hashCode()
    }

    override fun findMainDocumentation(): String {
        if (documentation == "") return documentation
        val comment = codebase.getComment(documentation)
        val end = findFirstTag(comment)?.textRange?.startOffset ?: documentation.length
        return comment.text.substring(0, end)
    }

    override fun isConstructor(): Boolean = false

    override fun isImplicitConstructor(): Boolean = false

    override fun returnType(): TypeItem = returnType

    override fun parameters(): List<PsiParameterItem> = parameters

    override val synthetic: Boolean get() = isEnumSyntheticMethod()

    private var superMethods: List<MethodItem>? = null
    override fun superMethods(): List<MethodItem> {
        if (superMethods == null) {
            val result = mutableListOf<MethodItem>()
            psiMethod.findSuperMethods().mapTo(result) { codebase.findMethod(it) }
            superMethods = result
        }

        return superMethods!!
    }

    override fun typeParameterList(): TypeParameterList {
        if (psiMethod.hasTypeParameters()) {
            return PsiTypeParameterList(
                codebase,
                psiMethod.typeParameterList
                    ?: return TypeParameterList.NONE
            )
        } else {
            return TypeParameterList.NONE
        }
    }

    override fun typeArgumentClasses(): List<ClassItem> {
        return PsiTypeItem.typeParameterClasses(codebase, psiMethod.typeParameterList)
    }

    //    private var throwsTypes: List<ClassItem>? = null
    private lateinit var throwsTypes: List<ClassItem>

    fun setThrowsTypes(throwsTypes: List<ClassItem>) {
        this.throwsTypes = throwsTypes
    }

    override fun throwsTypes(): List<ClassItem> = throwsTypes

    override fun isCloned(): Boolean {
        val psiClass = run {
            val p = containingClass().psi() as? PsiClass ?: return false
            if (p is UClass) {
                p.sourcePsi as? PsiClass ?: return false
            } else {
                p
            }
        }
        return psiMethod.containingClass != psiClass
    }

    override fun isExtensionMethod(): Boolean {
        if (isKotlin()) {
            val ktParameters =
                ((psiMethod as? UMethod)?.sourcePsi as? KtNamedFunction)?.valueParameters
                    ?: return false
            return ktParameters.size < parameters.size
        }

        return false
    }

    override fun isKotlinProperty(): Boolean {
        return psiMethod is UMethod && (
            psiMethod.sourcePsi is KtProperty ||
                psiMethod.sourcePsi is KtPropertyAccessor ||
                psiMethod.sourcePsi is KtParameter && (psiMethod.sourcePsi as KtParameter).hasValOrVar()
            )
    }

    override fun findThrownExceptions(): Set<ClassItem> {
        val method = psiMethod as? UMethod ?: return emptySet()
        if (!isKotlin()) {
            return emptySet()
        }

        val exceptions = mutableSetOf<ClassItem>()

        method.accept(object : AbstractUastVisitor() {
            override fun visitThrowExpression(node: UThrowExpression): Boolean {
                val type = node.thrownExpression.getExpressionType()
                if (type != null) {
                    val exceptionClass = codebase.getType(type).asClass()
                    if (exceptionClass != null && !isCaught(exceptionClass, node)) {
                        exceptions.add(exceptionClass)
                    }
                }
                return super.visitThrowExpression(node)
            }

            private fun isCaught(exceptionClass: ClassItem, node: UThrowExpression): Boolean {
                var current: UElement = node
                while (true) {
                    val tryExpression = current.getParentOfType<UTryExpression>(
                        UTryExpression::class.java, true, UMethod::class.java
                    ) ?: return false

                    for (catchClause in tryExpression.catchClauses) {
                        for (type in catchClause.types) {
                            val qualifiedName = type.canonicalText
                            if (exceptionClass.extends(qualifiedName)) {
                                return true
                            }
                        }
                    }

                    current = tryExpression
                }
            }
        })

        return exceptions
    }

    fun areAllParametersOptional(): Boolean {
        for (param in parameters) {
            if (!param.hasDefaultValue()) {
                return false
            }
        }
        return true
    }

    override fun defaultValue(): String {
        return when (psiMethod) {
            is UAnnotationMethod -> {
                psiMethod.uastDefaultValue?.let {
                    codebase.printer.toSourceString(it)
                } ?: ""
            }
            is PsiAnnotationMethod -> {
                psiMethod.defaultValue?.let {
                    codebase.printer.toSourceExpression(it, this)
                } ?: super.defaultValue()
            }
            else -> super.defaultValue()
        }
    }

    override fun duplicate(targetContainingClass: ClassItem): PsiMethodItem {
        val duplicated = create(codebase, targetContainingClass as PsiClassItem, psiMethod)

        duplicated.inheritedFrom = containingClass

        // Preserve flags that may have been inherited (propagated) from surrounding packages
        if (targetContainingClass.hidden) {
            duplicated.hidden = true
        }
        if (targetContainingClass.removed) {
            duplicated.removed = true
        }
        if (targetContainingClass.docOnly) {
            duplicated.docOnly = true
        }
        if (targetContainingClass.deprecated) {
            duplicated.deprecated = true
        }
        duplicated.throwsTypes = throwsTypes
        return duplicated
    }

    /* Call corresponding PSI utility method -- if I can find it!
    override fun matches(other: MethodItem): Boolean {
        if (other !is PsiMethodItem) {
            return super.matches(other)
        }

        // TODO: Find better API: this also checks surrounding class which we don't want!
        return psiMethod.isEquivalentTo(other.psiMethod)
    }
    */

    /**
     * Converts the method to a stub that can be converted back to a PsiMethod.
     *
     * Note: This must not be used for emitting stub jars. For that, see
     * [com.android.tools.metalava.stub.StubWriter].
     *
     * @param replacementMap a map that specifies replacement types for formal type parameters.
     */
    @Language("JAVA")
    fun toStubForCloning(replacementMap: Map<String, String> = emptyMap()): String {
        val method = this
        // There are type variables; we have to recreate the method signature
        val sb = StringBuilder(100)

        val modifierString = StringWriter()
        ModifierList.write(
            modifierString, method.modifiers, method,
            target = AnnotationTarget.INTERNAL,
            removeAbstract = false,
            removeFinal = false,
            addPublic = true
        )
        sb.append(modifierString.toString())

        val typeParameters = typeParameterList().toString()
        if (typeParameters.isNotEmpty()) {
            sb.append(' ')
            sb.append(TypeItem.convertTypeString(typeParameters, replacementMap))
        }

        val returnType = method.returnType()
        sb.append(returnType.convertTypeString(replacementMap))

        sb.append(' ')
        sb.append(method.name())

        sb.append("(")
        method.parameters().asSequence().forEachIndexed { i, parameter ->
            if (i > 0) {
                sb.append(", ")
            }

            val parameterModifierString = StringWriter()
            ModifierList.write(
                parameterModifierString, parameter.modifiers, parameter,
                target = AnnotationTarget.INTERNAL
            )
            sb.append(parameterModifierString.toString())
            sb.append(parameter.type().convertTypeString(replacementMap))
            sb.append(' ')
            sb.append(parameter.name())
        }
        sb.append(")")

        val throws = method.throwsTypes().asSequence().sortedWith(ClassItem.fullNameComparator)
        if (throws.any()) {
            sb.append(" throws ")
            throws.asSequence().sortedWith(ClassItem.fullNameComparator).forEachIndexed { i, type ->
                if (i > 0) {
                    sb.append(", ")
                }
                // No need to replace variables; we can't have type arguments for exceptions
                sb.append(type.qualifiedName())
            }
        }

        sb.append(" { return ")
        val defaultValue = PsiTypesUtil.getDefaultValueOfType(method.psiMethod.returnType)
        sb.append(defaultValue)
        sb.append("; }")

        return sb.toString()
    }

    override fun finishInitialization() {
        super.finishInitialization()

        throwsTypes = throwsTypes(codebase, psiMethod)
    }

    companion object {
        fun create(
            codebase: PsiBasedCodebase,
            containingClass: PsiClassItem,
            psiMethod: PsiMethod
        ): PsiMethodItem {
            assert(!psiMethod.isConstructor)
            val name = psiMethod.name
            val commentText = javadoc(psiMethod)
            val modifiers = modifiers(codebase, psiMethod, commentText)
            if (modifiers.isFinal() && containingClass.modifiers.isFinal()) {
                // The containing class is final, so it is implied that every method is final as well.
                // No need to apply 'final' to each method. (We do it here rather than just in the
                // signature emit code since we want to make sure that the signature comparison
                // methods with super methods also consider this method non-final.)
                modifiers.setFinal(false)
            }
            val parameters = parameterList(codebase, psiMethod)
            var psiReturnType = psiMethod.returnType

            // UAST workaround: the enum synthetic methods are sometimes missing return types,
            // see https://youtrack.jetbrains.com/issue/KT-39560
            if (psiReturnType == null && containingClass.isEnum()) {
                if (name == "valueOf") {
                    psiReturnType = codebase.getClassType(containingClass.psiClass)
                } else if (name == "values") {
                    psiReturnType = PsiArrayType(codebase.getClassType(containingClass.psiClass))
                }
            }

            val returnType = codebase.getType(psiReturnType!!)
            val method = PsiMethodItem(
                codebase = codebase,
                psiMethod = psiMethod,
                containingClass = containingClass,
                name = name,
                documentation = commentText,
                modifiers = modifiers,
                returnType = returnType,
                parameters = parameters
            )
            method.modifiers.setOwner(method)
            return method
        }

        fun create(
            codebase: PsiBasedCodebase,
            containingClass: PsiClassItem,
            original: PsiMethodItem
        ): PsiMethodItem {
            val method = PsiMethodItem(
                codebase = codebase,
                psiMethod = original.psiMethod,
                containingClass = containingClass,
                name = original.name(),
                documentation = original.documentation,
                modifiers = PsiModifierItem.create(codebase, original.modifiers),
                returnType = PsiTypeItem.create(codebase, original.returnType),
                parameters = PsiParameterItem.create(codebase, original.parameters())
            )
            method.modifiers.setOwner(method)
            method.source = original
            method.inheritedMethod = original.inheritedMethod

            return method
        }

        internal fun parameterList(
            codebase: PsiBasedCodebase,
            psiMethod: PsiMethod
        ): List<PsiParameterItem> {
            return if (psiMethod is UMethod) {
                psiMethod.uastParameters.mapIndexed { index, parameter ->
                    PsiParameterItem.create(codebase, parameter, index)
                }
            } else {
                psiMethod.parameterList.parameters.mapIndexed { index, parameter ->
                    PsiParameterItem.create(codebase, parameter, index)
                }
            }
        }

        private fun throwsTypes(codebase: PsiBasedCodebase, psiMethod: PsiMethod): List<ClassItem> {
            val interfaces = psiMethod.throwsList.referencedTypes
            if (interfaces.isEmpty()) {
                return emptyList()
            }

            val result = ArrayList<ClassItem>(interfaces.size)
            for (cls in interfaces) {
                result.add(codebase.findClass(cls) ?: continue)
            }

            // We're sorting the names here even though outputs typically do their own sorting,
            // since for example the MethodItem.sameSignature check wants to do an element-by-element
            // comparison to see if the signature matches, and that should match overrides even if
            // they specify their elements in different orders.
            result.sortWith(ClassItem.fullNameComparator)
            return result
        }
    }

    override fun toString(): String = "${if (isConstructor()) "constructor" else "method"} ${
    containingClass.qualifiedName()}.${name()}(${parameters().joinToString { it.type().toSimpleType() }})"
}
