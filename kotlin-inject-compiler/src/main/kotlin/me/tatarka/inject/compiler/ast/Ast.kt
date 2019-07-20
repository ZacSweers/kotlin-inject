package me.tatarka.inject.compiler.ast

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ClassName
import kotlinx.metadata.*
import kotlinx.metadata.jvm.annotations
import kotlinx.metadata.jvm.getterSignature
import kotlinx.metadata.jvm.signature
import me.tatarka.inject.compiler.javaToKotlinType
import me.tatarka.inject.compiler.metadata
import javax.annotation.processing.Messager
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.ExecutableType
import javax.lang.model.type.NoType
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import kotlin.reflect.KClass

interface AstProvider {
    val types: Types
    val elements: Elements
    val messager: Messager

    fun TypeElement.toAstClass(): AstClass? {
        val metadata = metadata ?: return null
        return AstClass(this@AstProvider, this, metadata)
    }

    fun KClass<*>.toAstClass(): AstClass? {
        return elements.getTypeElement(java.canonicalName)?.toAstClass()
    }

    fun declaredTypeOf(astClass: AstClass, vararg astTypes: AstType): AstType {
        return AstType(
            this, types.getDeclaredType(astClass.element, *astTypes.map { it.type }.toTypedArray()),
            astClass.kmClass.type
        )
    }

    fun declaredTypeOf(klass: KClass<*>, vararg astTypes: AstType): AstType {
        val type = elements.getTypeElement(klass.java.canonicalName)
        return AstType(
            this,
            types.getDeclaredType(type, *astTypes.map { it.type }.toTypedArray()),
            klass.toKmType()
        )
    }
}

sealed class AstElement(provider: AstProvider) : AstProvider by provider

class AstClass(provider: AstProvider, val element: TypeElement, internal val kmClass: KmClass) : AstElement(provider) {
    val packageName: String get() = elements.getPackageOf(element).qualifiedName.toString()

    val name: String get() = element.simpleName.toString()

    val companion: AstClass? by lazy {
        val companionName = kmClass.companionObject ?: return@lazy null
        val companionType = ElementFilter.typesIn(element.enclosedElements).firstOrNull { type ->
            type.simpleName.contentEquals(companionName)
        }
        companionType?.toAstClass()
    }

    val superclass: AstClass? by lazy {
        val superclassType = element.superclass
        if (superclassType is NoType) return@lazy null
        val superclass = provider.types.asElement(superclassType) as TypeElement
        superclass.toAstClass()
    }

    val interfaces: List<AstClass> by lazy {
        element.interfaces.mapNotNull { ifaceType ->
            val iface = provider.types.asElement(ifaceType) as TypeElement
            iface.toAstClass()
        }
    }

    val constructors: List<AstConstructor> by lazy {
        ElementFilter.constructorsIn(element.enclosedElements).mapNotNull { constructor ->
            //TODO: not sure how to match constructors
            AstConstructor(this, this, constructor, kmClass.constructors[0])
        }
    }

    val methods: List<AstMethod> by lazy {
        ElementFilter.methodsIn(element.enclosedElements).mapNotNull { method ->
            for (property in kmClass.properties) {
                val javaName = property.getterSignature?.name ?: continue
                if (method.simpleName.contentEquals(javaName)) {
                    return@mapNotNull AstProperty(this, method, property)
                }
            }
            for (function in kmClass.functions) {
                val javaName = function.signature?.name
                if (method.simpleName.contentEquals(javaName)) {
                    return@mapNotNull AstFunction(this, method, function)
                }
            }
            null
        }
    }

    val type: AstType by lazy {
        AstType(this, element.asType(), kmClass.type)
    }

    fun visitInheritanceChain(f: (AstClass) -> Unit) {
        f(this)
        superclass?.visitInheritanceChain(f)
        interfaces.forEach { it.visitInheritanceChain(f) }
    }

    fun asClassName(): ClassName = element.asClassName()
}


sealed class AstMethod(provider: AstProvider, val element: ExecutableElement) : AstElement(provider) {
    abstract val name: String

    abstract val modifiers: Set<AstModifier>

    abstract val receiverParameterType: AstType?

    abstract val returnType: AstType

    abstract fun returnTypeFor(enclosingClass: AstClass): AstType

    inline fun <reified T : Annotation> annotationOf(): T? = element.getAnnotation(T::class.java)
}

class AstConstructor(
    provider: AstProvider,
    private val parent: AstClass,
    internal val element: ExecutableElement,
    private val kmConstructor: KmConstructor
) : AstElement(provider) {
    val type: AstType get() = parent.type

    val parameters: List<AstParam> by lazy {
        element.parameters.mapNotNull { element ->
            for (parameter in kmConstructor.valueParameters) {
                if (element.simpleName.contentEquals(parameter.name)) {
                    return@mapNotNull AstParam(this, element, parameter)
                }
            }
            null
        }
    }
}

class AstFunction(provider: AstProvider, element: ExecutableElement, private val kmFunction: KmFunction) :
    AstMethod(provider, element) {

    override val name: String get() = kmFunction.name

    override val modifiers: Set<AstModifier> by lazy {
        val result = mutableSetOf<AstModifier>()
        val flags = kmFunction.flags
        if (Flag.Common.IS_PRIVATE(flags)) {
            result.add(AstModifier.PRIVATE)
        }
        if (Flag.Common.IS_ABSTRACT(flags)) {
            result.add(AstModifier.ABSTRACT)
        }
        result
    }

    override val returnType: AstType
        get() = AstType(this, element.returnType, kmFunction.returnType)

    override fun returnTypeFor(enclosingClass: AstClass): AstType {
        val declaredType = enclosingClass.element.asType() as DeclaredType
        val methodType = types.asMemberOf(declaredType, element) as ExecutableType
        return AstType(this, methodType.returnType, kmFunction.returnType)
    }

    override val receiverParameterType: AstType?
        get() = kmFunction.receiverParameterType?.let {
            AstType(this, element.parameters[0].asType(), it)
        }

    val parameters: List<AstParam> by lazy {
        element.parameters.mapNotNull { element ->
            for (parameter in kmFunction.valueParameters) {
                if (element.simpleName.contentEquals(parameter.name)) {
                    return@mapNotNull AstParam(this, element, parameter)
                }
            }
            null
        }
    }
}

class AstProperty(provider: AstProvider, element: ExecutableElement, private val kmProperty: KmProperty) :
    AstMethod(provider, element) {

    override val name: String get() = kmProperty.name

    override val modifiers: Set<AstModifier> by lazy {
        val result = mutableSetOf<AstModifier>()
        val flags = kmProperty.flags
        if (Flag.Common.IS_PRIVATE(flags)) {
            result.add(AstModifier.PRIVATE)
        }
        if (Flag.Common.IS_ABSTRACT(flags)) {
            result.add(AstModifier.ABSTRACT)
        }
        result
    }

    override val returnType: AstType
        get() = AstType(this, element.returnType, kmProperty.returnType)

    override fun returnTypeFor(enclosingClass: AstClass): AstType {
        val declaredType = enclosingClass.element.asType() as DeclaredType
        val methodType = types.asMemberOf(declaredType, element) as ExecutableType
        return AstType(this, methodType.returnType, kmProperty.returnType)
    }

    override val receiverParameterType: AstType?
        get() = kmProperty.receiverParameterType?.let {
            AstType(this, element.parameters[0].asType(), it)
        }
}

class AstType(provider: AstProvider, val type: TypeMirror, private val kmType: KmType) : AstElement(provider) {
    val annotations: List<AstAnnotation> by lazy {
        kmType.annotations.map { annotation ->
            val mirror = provider.elements.getTypeElement(annotation.className.replace('/', '.'))
            AstAnnotation(this, mirror.asType() as DeclaredType, annotation)
        }
    }

    val arguments: List<AstType> by lazy {
        (type as DeclaredType).typeArguments.zip(kmType.arguments).map { (a1, a2) ->
            AstType(this, a1, a2.type!!)
        }
    }

    fun isUnit(): Boolean = type is NoType

    inline fun isNotUnit() = !isUnit()

    override fun equals(other: Any?): Boolean {
        if (other !is AstType) return false
        return asTypeName() == other.asTypeName()
    }

    override fun hashCode(): Int {
        return asTypeName().hashCode()
    }

    override fun toString(): String {
        return type.asTypeName().javaToKotlinType().toString()
    }
}

class AstAnnotation(provider: AstProvider, val annotationType: DeclaredType, private val kmAnnotation: KmAnnotation) :
    AstElement(provider) {

    override fun equals(other: Any?): Boolean {
        if (other !is AstAnnotation) return false
        return kmAnnotation == other.kmAnnotation
    }

    override fun hashCode(): Int {
        return kmAnnotation.hashCode()
    }

    override fun toString(): String {
        return "@$annotationType(${kmAnnotation.arguments.toList().joinToString(separator = ", ") { (name, value) -> "$name=${value.value}" }})"
    }
}

class AstParam(provider: AstProvider, val element: VariableElement, val kmValueParameter: KmValueParameter) :
    AstElement(provider) {

    val name: String get() = kmValueParameter.name

    val type: AstType by lazy {
        AstType(this, element.asType(), kmValueParameter.type!!)
    }
}

enum class AstModifier {
    PRIVATE, ABSTRACT
}

private val KmClass.type: KmType
    get() = KmType(flags = flags).apply {
        classifier = KmClassifier.Class(name)
    }

private val KmAnnotation.type: KmType
    get() = KmType(0).apply {
        classifier = KmClassifier.Class(className)
    }

private fun KClass<*>.toKmType(): KmType = KmType(0).apply {
    classifier = KmClassifier.Class(java.canonicalName)
}

fun AstType.asTypeName(): TypeName = type.asTypeName().javaToKotlinType()

fun ParameterSpec.Companion.parametersOf(constructor: AstConstructor): List<ParameterSpec> =
    parametersOf(constructor.element)

