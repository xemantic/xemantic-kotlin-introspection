/*
 * xemantic-kotlin-introspection - Kotlin API introspection of the classpath, rendering LLM-friendly signatures
 * Copyright (C) 2025-2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.kotlin.introspection

import com.xemantic.kotlin.core.text.trimLastNewLine
import com.xemantic.kotlin.core.text.unaryPlus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.Attributes
import java.util.jar.JarFile
import kotlin.metadata.*
import kotlin.metadata.internal.common.KotlinCommonMetadata
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.annotations
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.time.measureTime
import kotlin.metadata.jvm.Metadata as jvmMetadata

/**
 * Represents an indexed extension function with its source location.
 */
internal data class IndexedExtension(
    val function: KmFunction,
    val sourceClass: String,  // fully qualified class name where it's defined
    val source: String,  // the package, or the object, to import it from
    val isObjectMember: Boolean  // whether the source is an object
)

/**
 * Represents an indexed extension property with its source location.
 */
internal data class IndexedExtensionProperty(
    val property: KmProperty,
    val sourceClass: String,  // fully qualified class name where it's defined
    val source: String,  // the package, or the object, to import it from
    val isObjectMember: Boolean  // whether the source is an object
)

/**
 * A Kotlin class found on the classpath, by its metadata name, like `com/example/Outer.Nested`,
 * with the member extensions, in case of an object, which can be imported from it.
 */
private class IndexedClass(
    val name: String,
    val binaryName: String,
    val scope: ClassScope,
    val isSearchable: Boolean,
    val extensions: List<KmFunction>,
    val extensionProperties: List<KmProperty>
)

/**
 * What decides whether the declarations within a class are part of the API.
 *
 * @param isOpen whether the user code can declare a subclass of this class.
 */
private class ClassScope(
    val visibility: Visibility,
    val isOpen: Boolean,
    val isCompanion: Boolean
)

private val KmClass.scope: ClassScope
    get() = ClassScope(
        visibility = visibility,
        // a sealed class cannot be extended outside its own module, and an interface cannot have protected members
        isOpen = kind == ClassKind.CLASS && (modality == Modality.OPEN || modality == Modality.ABSTRACT),
        isCompanion = kind == ClassKind.COMPANION_OBJECT
    )

/**
 * Whether a declaration of this visibility is part of the API, the one which can be used from the user code,
 * and therefore listed, resolved, and found.
 * Private and internal declarations are not, neither are the `@PublishedApi internal` ones,
 * callable only from public inline functions.
 * Protected members are, but only of an open class, or of the companion object of an open class,
 * as no subclass using them can be declared otherwise.
 * Nothing declared within a class which is not part of the API is.
 *
 * @param enclosing the classes enclosing the declaration, from the outermost one, empty for a top-level declaration.
 */
private fun Visibility.isApiIn(enclosing: List<ClassScope>): Boolean {
    val owner = enclosing.lastOrNull() ?: return this == Visibility.PUBLIC
    val outerClasses = enclosing.dropLast(1)
    // the protected members of a companion object are accessible to the subclasses of its outer class
    val subclassed = if (owner.isCompanion) outerClasses.lastOrNull() else owner
    val isVisible = this == Visibility.PUBLIC || (this == Visibility.PROTECTED && subclassed?.isOpen == true)
    return isVisible && owner.visibility.isApiIn(outerClasses)
}

internal class DefaultKotlinIntrospector(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    scanEntireClasspath: Boolean = true
) : KotlinIntrospector {

    private val logger = KotlinLogging.logger {}

    // Index of extension functions: receiver type name -> list of extensions
    private val extensionIndex: Map<String, List<IndexedExtension>>

    // Index of extension properties: receiver type name -> list of extension properties
    private val extensionPropertyIndex: Map<String, List<IndexedExtensionProperty>>

    // Index of all Kotlin classes: simple name (lowercase) -> list of fully qualified names
    private val classIndex: Map<String, List<String>>

    // Declarations of the built-in types, by their Kotlin class name, like kotlin/collections/List
    private val builtinClasses: Map<String, KmClass> by lazy { readBuiltinClasses() }

    // Declarations of the built-in collection types, which kotlin-reflect cannot tell apart
    // from their mutable counterparts, as both map to the same JVM type
    private val builtinCollectionClasses: Map<String, KmClass> by lazy {
        builtinClasses.filterKeys { it.startsWith("kotlin/collections/") }.mapKeys { it.key.replace('/', '.') }
    }

    private val overrideResolver = OverrideResolver(
        loadClass = ::loadClass,
        readKmClass = ::readKmClass,
        builtinClasses = { builtinClasses },
        builtinTypes = builtinTypes
    )

    companion object {

        /**
         * Kotlin built-in type names mapped to their KClass.
         * These types map to JVM types and don't have Kotlin metadata.
         */
        @OptIn(ExperimentalUnsignedTypes::class)
        private val builtinTypes: Map<String, KClass<*>> = mapOf(
            "kotlin.Any" to Any::class,
            "kotlin.String" to String::class,
            "kotlin.CharSequence" to CharSequence::class,
            "kotlin.Throwable" to Throwable::class,
            "kotlin.Cloneable" to Cloneable::class,
            "kotlin.Number" to Number::class,
            "kotlin.Comparable" to Comparable::class,
            "kotlin.Enum" to Enum::class,
            "kotlin.Annotation" to Annotation::class,
            "kotlin.Nothing" to Nothing::class,
            "kotlin.Unit" to Unit::class,
            "kotlin.reflect.KClass" to KClass::class,
            // primitive types, represented by their JVM wrapper classes
            "kotlin.Boolean" to Boolean::class.javaObjectType.kotlin,
            "kotlin.Char" to Char::class.javaObjectType.kotlin,
            "kotlin.Byte" to Byte::class.javaObjectType.kotlin,
            "kotlin.Short" to Short::class.javaObjectType.kotlin,
            "kotlin.Int" to Int::class.javaObjectType.kotlin,
            "kotlin.Float" to Float::class.javaObjectType.kotlin,
            "kotlin.Long" to Long::class.javaObjectType.kotlin,
            "kotlin.Double" to Double::class.javaObjectType.kotlin,
            // collection types, the mutable ones share the JVM type of the read-only ones
            "kotlin.collections.Iterator" to Iterator::class,
            "kotlin.collections.Iterable" to Iterable::class,
            "kotlin.collections.Collection" to Collection::class,
            "kotlin.collections.Set" to Set::class,
            "kotlin.collections.List" to List::class,
            "kotlin.collections.ListIterator" to ListIterator::class,
            "kotlin.collections.Map" to Map::class,
            "kotlin.collections.Map.Entry" to Map.Entry::class,
            "kotlin.collections.MutableIterator" to MutableIterator::class,
            "kotlin.collections.MutableIterable" to MutableIterable::class,
            "kotlin.collections.MutableCollection" to MutableCollection::class,
            "kotlin.collections.MutableSet" to MutableSet::class,
            "kotlin.collections.MutableList" to MutableList::class,
            "kotlin.collections.MutableListIterator" to MutableListIterator::class,
            "kotlin.collections.MutableMap" to MutableMap::class,
            "kotlin.collections.MutableMap.MutableEntry" to MutableMap.MutableEntry::class,
            // array types
            "kotlin.Array" to Array::class,
            "kotlin.IntArray" to IntArray::class,
            "kotlin.LongArray" to LongArray::class,
            "kotlin.ShortArray" to ShortArray::class,
            "kotlin.ByteArray" to ByteArray::class,
            "kotlin.FloatArray" to FloatArray::class,
            "kotlin.DoubleArray" to DoubleArray::class,
            "kotlin.BooleanArray" to BooleanArray::class,
            "kotlin.CharArray" to CharArray::class,
            // unsigned types
            "kotlin.UByte" to UByte::class,
            "kotlin.UShort" to UShort::class,
            "kotlin.UInt" to UInt::class,
            "kotlin.ULong" to ULong::class,
            "kotlin.UByteArray" to UByteArray::class,
            "kotlin.UShortArray" to UShortArray::class,
            "kotlin.UIntArray" to UIntArray::class,
            "kotlin.ULongArray" to ULongArray::class,
        )

        private val mutableBuiltinTypes = builtinTypes.keys.filter {
            it.substringAfterLast('.').startsWith("Mutable")
        }.toSet()

        private const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

        private val builtinsResources = listOf(
            "kotlin/kotlin.kotlin_builtins",
            "kotlin/annotation/annotation.kotlin_builtins",
            "kotlin/collections/collections.kotlin_builtins",
            "kotlin/ranges/ranges.kotlin_builtins",
            "kotlin/reflect/reflect.kotlin_builtins"
        )

        /**
         * Marks a parameter declaring a default value, which is not present in the metadata,
         * telling that the argument can be omitted.
         */
        private const val DEFAULT_VALUE_MARKER = "…"

        private const val DEFAULT_COMPANION_NAME = "Companion"

    }

    init {
        val scanTime = measureTime {
            if (scanEntireClasspath) {
                val (funcIndex, propIndex, clsIndex) = buildFullClasspathIndex()
                extensionIndex = funcIndex
                extensionPropertyIndex = propIndex
                classIndex = clsIndex
            } else {
                extensionIndex = emptyMap()
                extensionPropertyIndex = emptyMap()
                classIndex = emptyMap()
            }
        }
        val extensionsFound = extensionIndex.values.sumOf { it.size } +
            extensionPropertyIndex.values.sumOf { it.size }
        logger.info { "Classpath scan completed in $scanTime, found $extensionsFound extensions" }
    }

    private fun resolve(name: String): String? {
        val parts = name.split(".")

        // Try to find where the class name ends and member name begins
        // by progressively trying to load the class
        for (i in parts.size downTo 1) {
            val className = parts.take(i).joinToString(".")
            val memberName = if (i < parts.size) parts.drop(i).joinToString(".") else null

            // Check if this className is a built-in type
            builtinTypes[className]?.let { kClass ->
                return if (memberName != null) {
                    resolveBuiltinMember(kClass, className, memberName)
                } else {
                    formatBuiltinClass(kClass, className)
                }
            }

            val clazz = loadClass(className) ?: continue
            val metadata = readMetadata(clazz) ?: continue

            val resolved = when (metadata) {

                is KotlinClassMetadata.Class -> {
                    val kmClass = metadata.kmClass
                    // neither a class which is not part of the API, nor anything declared within it, is resolved
                    val outerScopes = enclosingScopes(kmClass) ?: return null
                    if (!kmClass.visibility.isApiIn(outerScopes)) return null
                    val scopes = outerScopes + kmClass.scope
                    if (memberName != null) {
                        resolveMember(kmClass, scopes, className, memberName)
                    } else {
                        formatClass(kmClass, scopes, className, clazz)
                    }
                }

                is KotlinClassMetadata.FileFacade -> memberName?.let {
                    resolvePackageMember(listOf(metadata.kmPackage), it)
                }

                is KotlinClassMetadata.MultiFileClassPart -> memberName?.let {
                    resolvePackageMember(listOf(metadata.kmPackage), it)
                }

                // the facade only lists the parts holding the actual declarations
                is KotlinClassMetadata.MultiFileClassFacade -> memberName?.let {
                    resolvePackageMember(
                        metadata.partClassNames.mapNotNull { partName ->
                            loadClass(partName.replace('/', '.'))?.let { readMetadata(it) }
                        }.filterIsInstance<KotlinClassMetadata.MultiFileClassPart>().map { it.kmPackage },
                        it
                    )
                }

                else -> null
            }

            if (resolved != null) return resolved
        }

        return null
    }

    /**
     * Loads a class by its fully qualified Kotlin name, where nested classes are separated with `.`
     * instead of the `$` of the JVM binary name.
     */
    private fun loadClass(name: String): Class<*>? {
        var binaryName = name
        while (true) {
            try {
                return classLoader.loadClass(binaryName)
            } catch (_: ClassNotFoundException) {
            } catch (_: LinkageError) {
            }
            val lastDot = binaryName.lastIndexOf('.')
            if (lastDot < 0) return null
            binaryName = binaryName.substring(0, lastDot) + "$" + binaryName.substring(lastDot + 1)
        }
    }

    private fun readMetadata(clazz: Class<*>): KotlinClassMetadata? {
        val metadataAnnotation = clazz.getAnnotation(Metadata::class.java) ?: return null
        return try {
            KotlinClassMetadata.readLenient(metadataAnnotation)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readKmClass(clazz: Class<*>): KmClass? =
        (readMetadata(clazz) as? KotlinClassMetadata.Class)?.kmClass

    /**
     * The scopes of the classes enclosing the [kmClass], from the outermost one,
     * or null if any of them cannot be loaded.
     */
    private fun enclosingScopes(kmClass: KmClass): List<ClassScope>? =
        enclosingClassNames(kmClass.name).map { name ->
            loadClass(name.replace('/', '.'))?.let { readKmClass(it) }?.scope ?: return null
        }

    /**
     * @param scopes the scopes of the [kmClass] and its enclosing classes, from the outermost one.
     */
    private fun resolveMember(
        kmClass: KmClass,
        scopes: List<ClassScope>,
        className: String,
        memberName: String
    ): String? {
        val isInterface = kmClass.kind == ClassKind.INTERFACE
        val typeParameters = kmClass.typeParameters.namesById()

        val signatures = kmClass.functions.filter {
            it.name == memberName && it.visibility.isApiIn(scopes)
        }.map { function ->
            formatFunction(function, inInterface = isInterface, owner = kmClass, classTypeParameters = typeParameters)
        } + kmClass.properties.filter {
            it.name == memberName && it.visibility.isApiIn(scopes)
        }.map { property ->
            formatProperty(property, inInterface = isInterface, owner = kmClass, classTypeParameters = typeParameters)
        } + getExtensionPropertiesFor(className).filter { it.property.name == memberName }.map { extProp ->
            formatExtensionProperty(extProp.property)
        } + getExtensionsFor(className).filter { it.function.name == memberName }.map { ext ->
            formatFunction(ext.function)
        }

        return signatures.joinToString("\n").ifEmpty { null }
    }

    private fun resolvePackageMember(kmPackages: List<KmPackage>, memberName: String): String? {
        val signatures = kmPackages.flatMap { kmPackage ->
            kmPackage.functions.filter { it.name == memberName && it.visibility.isApiIn(emptyList()) }.map { formatFunction(it) } +
                kmPackage.properties.filter { it.name == memberName && it.visibility.isApiIn(emptyList()) }.map { property ->
                    if (property.receiverParameterType != null) {
                        formatExtensionProperty(property)
                    } else {
                        formatProperty(property)
                    }
                }
        }
        return signatures.joinToString("\n").ifEmpty { null }
    }

    /**
     * Reads the declarations of the built-in types from the `.kotlin_builtins` files of the standard library.
     */
    private fun readBuiltinClasses(): Map<String, KmClass> = builtinsResources.flatMap { resource ->
        val stream = KotlinIntrospector::class.java.classLoader.getResourceAsStream(resource)
        val fragment = stream?.use { KotlinCommonMetadata.read(it) }?.kmModuleFragment
        fragment?.classes.orEmpty()
    }.associateBy { it.name }

    /**
     * Format a Kotlin built-in type using kotlin-reflect.
     */
    private fun formatBuiltinClass(
        kClass: KClass<*>,
        kotlinName: String
    ): String {
        val simpleName = kotlinName.substringAfterLast(".")
        val mutableKmClass = if (kotlinName in mutableBuiltinTypes) builtinCollectionClasses[kotlinName] else null

        // Collect all members to sort them together alphabetically
        data class Member(val name: String, val formatted: String)
        val allMembers = mutableListOf<Member>()

        val declaration: String
        val supertypeNames: Collection<String>

        if (mutableKmClass != null) {
            // kotlin-reflect would describe the read-only counterpart, sharing the same JVM type
            val typeParameters = mutableKmClass.typeParameters.namesById()
            val supertypes = mutableKmClass.supertypes.map { formatType(it, typeParameters = typeParameters) }
            declaration = buildString {
                +"interface "
                +simpleName
                +formatTypeParameters(mutableKmClass.typeParameters, typeParameters)
                if (supertypes.isNotEmpty()) {
                    +" : "
                    +supertypes.joinToString(", ")
                }
            }
            mutableKmClass.properties.forEach { property ->
                allMembers.add(
                    Member(property.name, formatProperty(property, inInterface = true, classTypeParameters = typeParameters))
                )
            }
            mutableKmClass.functions.forEach { function ->
                allMembers.add(
                    Member(function.name, formatFunction(function, inInterface = true, classTypeParameters = typeParameters))
                )
            }
            supertypeNames = builtinCollectionSupertypes(mutableKmClass)
        } else {
            // Supertypes (excluding Any and Java-specific interfaces)
            val javaOnlyInterfaces = setOf(
                java.io.Serializable::class,
                java.lang.Cloneable::class,
            )
            val supertypes = kClass.supertypes
                .filter { it.classifier != Any::class }
                .filter { it.classifier !in javaOnlyInterfaces }
                .map { formatKTypeProjection(it) }
            declaration = buildString {
                +(if (kClass.java.isInterface) "interface " else "class ")
                +simpleName
                +formatKTypeParameters(kClass.typeParameters)
                if (supertypes.isNotEmpty()) {
                    +" : "
                    +supertypes.joinToString(", ")
                }
            }

            // Properties (only public, non-extension)
            kClass.memberProperties
                .filter { it.visibility == KVisibility.PUBLIC }
                .forEach { prop ->
                    allMembers.add(Member(prop.name, formatKProperty(prop)))
                }

            // Functions (only public, non-extension, excluding synthetic, internal, and Java-only methods)
            kClass.memberFunctions
                .asSequence()
                .filter { it.visibility == KVisibility.PUBLIC }
                .filter { !it.name.startsWith("component") }  // Exclude componentN
                .filter { it.name != "copy" }  // Exclude copy for data classes
                .filter { !it.name.contains("$") }  // Exclude synthetic
                .filter { !it.toString().contains("internal ") }  // Exclude internal
                // Filter out Java-only methods (those with argN parameter names)
                .filter { func ->
                    val params = func.parameters.drop(1)  // Skip 'this'
                    params.isEmpty() || params.all { it.name != null && !it.name!!.startsWith("arg") }
                }
                .toList()
                .forEach { func ->
                    allMembers.add(Member(func.name, formatKFunction(func)))
                }

            supertypeNames = kClass.allSuperclasses
                .filter { it != Any::class }
                .mapNotNull { it.qualifiedName }
        }

        // Extension functions on this exact type (shown inside class body)
        // Only include extensions from kotlin.* packages for built-in types
        // The ones to import from an object, like Int.seconds of Duration.Companion, are shown outside class body
        val (objectExtensions, packageExtensions) = getExtensionsFor(kotlinName)
            .filter { isPublicStdlibSource(it.sourceClass) }
            .partition { it.isObjectMember }
        packageExtensions.forEach { ext ->
            allMembers.add(Member(ext.function.name, formatFunctionWithoutReceiver(ext.function)))
        }

        // Extension properties on this exact type (shown inside class body)
        val (objectExtensionProperties, packageExtensionProperties) = getExtensionPropertiesFor(kotlinName)
            .filter { isPublicStdlibSource(it.sourceClass) }
            .partition { it.isObjectMember }
        packageExtensionProperties.forEach { extProp ->
            allMembers.add(Member(extProp.property.name, formatExtensionPropertyWithoutReceiver(extProp.property)))
        }

        // Extension functions and properties on all the supertypes (shown outside class body)
        // Only include extensions from kotlin.* packages for built-in types
        val supertypeExtensions = objectExtensions + supertypeNames.flatMap { supertypeName ->
            getExtensionsFor(supertypeName)
                .filter { isPublicStdlibSource(it.sourceClass) }
        }
        val supertypeExtensionProperties = objectExtensionProperties + supertypeNames.flatMap { supertypeName ->
            getExtensionPropertiesFor(supertypeName)
                .filter { isPublicStdlibSource(it.sourceClass) }
        }

        return buildString {
            +declaration
            +" {\n"

            // Sort all members alphabetically and output
            for (member in allMembers.sortedBy { it.name }) {
                +"    "
                +member.formatted
                +"\n"
            }

            +"}"

            +renderExtensions(supertypeExtensions, supertypeExtensionProperties, contextPackage = null)

            trimLastNewLine()
        }
    }

    /**
     * All the supertypes of a built-in collection type, excluding `Any`.
     */
    private fun builtinCollectionSupertypes(kmClass: KmClass): Set<String> {
        val supertypeNames = linkedSetOf<String>()
        val pending = ArrayDeque(listOf(kmClass))
        while (pending.isNotEmpty()) {
            for (supertype in pending.removeFirst().supertypes) {
                val name = (supertype.classifier as? KmClassifier.Class)?.name?.replace('/', '.') ?: continue
                if (name == "kotlin.Any" || !supertypeNames.add(name)) continue
                builtinCollectionClasses[name]?.let { pending.add(it) }
            }
        }
        return supertypeNames
    }

    /**
     * All the supertypes of a class, excluding the ones from `kotlin.*` and `java.*` packages,
     * which are not traversed further, so that a class implementing, e.g., `Comparable`
     * is not flooded with the generic extensions of the standard library.
     */
    private fun userSupertypes(kmClass: KmClass): Set<String> {
        val supertypeNames = linkedSetOf<String>()
        val pending = ArrayDeque(listOf(kmClass))
        while (pending.isNotEmpty()) {
            for (supertype in pending.removeFirst().supertypes) {
                val name = (supertype.classifier as? KmClassifier.Class)?.name?.replace('/', '.') ?: continue
                if (name.startsWith("kotlin.") || name.startsWith("java.") || !supertypeNames.add(name)) continue
                loadClass(name)?.let { readKmClass(it) }?.let { pending.add(it) }
            }
        }
        return supertypeNames
    }

    /**
     * Only public packages of the standard library are relevant for the built-in types,
     * other libraries, like kotlin-reflect, also ship `kotlin.*` packages for internal use.
     */
    private fun isPublicStdlibSource(sourceClass: String): Boolean =
        sourceClass.startsWith("kotlin.") && !sourceClass.split('.').contains("internal")

    /**
     * Resolve a member of a built-in type.
     */
    private fun resolveBuiltinMember(
        kClass: KClass<*>,
        className: String,
        memberName: String
    ): String? {
        val mutableKmClass = if (className in mutableBuiltinTypes) builtinCollectionClasses[className] else null
        val mutableMembers = mutableKmClass?.let { kmClass ->
            val typeParameters = kmClass.typeParameters.namesById()
            kmClass.functions.filter { it.name == memberName }.map {
                formatFunction(it, inInterface = true, classTypeParameters = typeParameters)
            } + kmClass.properties.filter { it.name == memberName }.map {
                formatProperty(it, inInterface = true, classTypeParameters = typeParameters)
            }
        } ?: emptyList()

        // Members are sorted, as kotlin-reflect does not guarantee their order
        val members = mutableMembers.ifEmpty {
            kClass.memberFunctions.filter {
                it.name == memberName && it.visibility == KVisibility.PUBLIC
            }.map { formatKFunction(it) }.sorted() + kClass.memberProperties.filter {
                it.name == memberName && it.visibility == KVisibility.PUBLIC
            }.map { formatKProperty(it) }
        }

        val signatures = members + getExtensionPropertiesFor(className).filter {
            it.property.name == memberName
        }.map { formatExtensionProperty(it.property) } + getExtensionsFor(className).filter {
            it.function.name == memberName
        }.map { formatFunction(it.function) }

        return signatures.joinToString("\n").ifEmpty { null }
    }

    /**
     * Format a KFunction using kotlin-reflect.
     */
    private fun formatKFunction(func: KFunction<*>): String = buildString {

        if (func.isOperator) +"operator "
        if (func.isInfix) +"infix "
        if (func.isSuspend) +"suspend "

        +"fun "
        formatKTypeParameters(func.typeParameters).let { if (it.isNotEmpty()) +"$it " }
        +func.name
        +"("

        // Skip first parameter (receiver) for member functions
        val params = func.parameters.drop(1)  // Drop 'this'
        +(params.joinToString(", ") { formatKParameter(it) })

        +")"

        val returnType = formatKTypeProjection(func.returnType)
        if (returnType != "Unit") {
            +": "
            +returnType
        }
    }

    /**
     * Format a value parameter using kotlin-reflect, like `vararg elements: T` or `limit: Int = …`.
     */
    private fun formatKParameter(parameter: KParameter): String = buildString {
        if (parameter.isVararg) +"vararg "
        +(parameter.name ?: "arg${parameter.index}")
        +": "
        // the type of a vararg parameter is the array type, like Array<out T> or IntArray
        val type = if (parameter.isVararg) {
            parameter.type.arguments.singleOrNull()?.type?.let { formatKTypeProjection(it) }
                ?: (parameter.type.classifier as? KClass<*>)?.simpleName?.removeSuffix("Array")
                ?: formatKTypeProjection(parameter.type)
        } else {
            formatKTypeProjection(parameter.type)
        }
        +type
        if (parameter.isOptional) +" = $DEFAULT_VALUE_MARKER"
    }

    /**
     * Format a KProperty using kotlin-reflect.
     */
    private fun formatKProperty(prop: KProperty1<*, *>): String = buildString {
        +"val "
        +prop.name
        +": "
        +formatKTypeProjection(prop.returnType)
    }

    /**
     * Format type parameter declarations, like `<out E>`, using kotlin-reflect.
     */
    private fun formatKTypeParameters(typeParameters: List<KTypeParameter>): String {
        if (typeParameters.isEmpty()) return ""
        return typeParameters.joinToString(", ", prefix = "<", postfix = ">") { typeParameter ->
            buildString {
                if (typeParameter.isReified) +"reified "
                when (typeParameter.variance) {
                    kotlin.reflect.KVariance.IN -> +"in "
                    kotlin.reflect.KVariance.OUT -> +"out "
                    kotlin.reflect.KVariance.INVARIANT -> {}
                }
                +typeParameter.name
                typeParameter.upperBounds.singleOrNull()
                    ?.takeUnless { it.classifier == Any::class && it.isMarkedNullable }
                    ?.let {
                        +" : "
                        +formatKTypeProjection(it)
                    }
            }
        }
    }

    /**
     * Format a KType for display.
     * Uses simple names for kotlin.* types and fully qualified names for java.* types.
     */
    private fun formatKTypeProjection(type: kotlin.reflect.KType): String {
        val baseName = when (val classifier = type.classifier) {
            is KClass<*> -> {
                val qualifiedName = classifier.qualifiedName ?: classifier.simpleName ?: "Unknown"
                // Use simple name for kotlin.* types, fully qualified for others
                if (qualifiedName.startsWith("kotlin.")) {
                    classifier.simpleName ?: "Unknown"
                } else {
                    qualifiedName
                }
            }
            // the name only, without the platform type marker
            is KTypeParameter -> classifier.name
            else -> type.toString()
        }

        val args = type.arguments.map { projection ->
            val projType = projection.type ?: return@map "*"
            when (projection.variance) {
                kotlin.reflect.KVariance.INVARIANT, null -> formatKTypeProjection(projType)
                kotlin.reflect.KVariance.IN -> "in ${formatKTypeProjection(projType)}"
                kotlin.reflect.KVariance.OUT -> "out ${formatKTypeProjection(projType)}"
            }
        }

        val result = if (args.isEmpty()) {
            baseName
        } else {
            "$baseName<${args.joinToString(", ")}>"
        }

        return if (type.isMarkedNullable) "$result?" else result
    }

    /**
     * Format an extension function without the receiver prefix (for showing inside class body).
     */
    private fun formatFunctionWithoutReceiver(function: KmFunction): String = buildString {
        val typeParameters = function.typeParameters.namesById()
        +formatVisibility(function.visibility)
        if (function.isOperator) +"operator "
        if (function.isInfix) +"infix "
        if (function.isSuspend) +"suspend "

        +"fun "
        formatTypeParameters(function.typeParameters, typeParameters).let { if (it.isNotEmpty()) +"$it " }
        +function.name
        +"("

        +(function.valueParameters.joinToString(", ") { param ->
            formatValueParameter(param, typeParameters = typeParameters)
        })

        +")"

        function.returnType.let { returnType ->
            val typeName = formatType(returnType, typeParameters = typeParameters)
            if (typeName != "Unit") {
                +": "
                +typeName
            }
        }
        +formatWhereClause(function.typeParameters, typeParameters)
    }

    /**
     * Format an extension property without the receiver prefix (for showing inside class body).
     */
    private fun formatExtensionPropertyWithoutReceiver(property: KmProperty): String = buildString {
        val typeParameters = property.typeParameters.namesById()
        +formatVisibility(property.visibility)
        +(if (property.isVar) "var " else "val ")
        formatTypeParameters(property.typeParameters, typeParameters).let { if (it.isNotEmpty()) +"$it " }
        +property.name
        +": "
        +formatType(property.returnType, typeParameters = typeParameters)
    }

    /**
     * The modifiers and the kind of the class, like `data class ` or `object `.
     */
    private fun formatClassPrefix(kmClass: KmClass): String = buildString {
        val isInterface = kmClass.kind == ClassKind.INTERFACE

        // Visibility and modifiers
        +formatVisibility(kmClass.visibility)
        // Skip 'abstract' for interfaces - they're always abstract
        if (kmClass.modality == Modality.ABSTRACT && !isInterface) +"abstract "
        if (kmClass.modality == Modality.OPEN) +"open "
        if (kmClass.modality == Modality.SEALED) +"sealed "
        if (kmClass.isData) +"data "
        if (kmClass.isValue) +"value "
        if (kmClass.isInner) +"inner "

        // Class kind
        +when (kmClass.kind) {
            ClassKind.CLASS -> "class "
            ClassKind.INTERFACE -> "interface "
            ClassKind.ENUM_CLASS -> "enum class "
            ClassKind.ENUM_ENTRY -> "enum entry "
            ClassKind.OBJECT -> "object "
            ClassKind.COMPANION_OBJECT -> "companion object "
            ClassKind.ANNOTATION_CLASS -> "annotation class "
        }
    }

    /**
     * @param scopes the scopes of the [kmClass] and its enclosing classes, from the outermost one.
     */
    private fun formatClass(kmClass: KmClass, scopes: List<ClassScope>, className: String, clazz: Class<*>): String {
        val isInterface = kmClass.kind == ClassKind.INTERFACE
        val isEnum = kmClass.kind == ClassKind.ENUM_CLASS
        val isAnnotation = kmClass.kind == ClassKind.ANNOTATION_CLASS
        val isSealed = kmClass.modality == Modality.SEALED
        val typeParameters = kmClass.typeParameters.namesById()

        // Extract the package name for context-aware type formatting
        // For nested classes, use the actual Java package, not the parent class
        val contextPackage = clazz.`package`?.name ?: className.substringBeforeLast(".", "")

        // Only the members which can be used from the user code
        val properties = kmClass.properties.filter { it.visibility.isApiIn(scopes) }
        val functions = kmClass.functions.filter { it.visibility.isApiIn(scopes) }

        // Find primary constructor and its parameter names
        val primaryConstructor = kmClass.constructors.firstOrNull { !it.isSecondary }
        // The parameters of a constructor which cannot be called are not rendered, while the properties
        // they declare still are, in the class body. Except for an enum class, whose constructor is always private,
        // while its parameters are the idiomatic way of declaring the properties of the entries.
        val renderedConstructor = primaryConstructor?.takeIf {
            isEnum || it.visibility.isApiIn(scopes)
        }
        val constructorParamNames = renderedConstructor?.valueParameters?.map { it.name }?.toSet() ?: emptySet()

        // Constructor parameters - properties that match constructor parameter names
        val constructorProperties = properties.filter { it.name in constructorParamNames }.associateBy { it.name }

        // The companion object, unless it is not part of the API, with its members which are
        val companion = kmClass.companionObject
            // the binary name, as the class might be nested
            ?.let { name -> loadClass("${clazz.name}\$$name")?.let { readKmClass(it) } }
            ?.takeIf { it.visibility.isApiIn(scopes) }
        val companionScopes = scopes + listOfNotNull(companion?.scope)
        val companionProperties = companion?.properties.orEmpty().filter { it.visibility.isApiIn(companionScopes) }
        val companionFunctions = companion?.functions.orEmpty().filter { it.visibility.isApiIn(companionScopes) }

        val supertypes = formatSupertypes(kmClass, contextPackage, typeParameters)

        // Extensions from the index on this type and its supertypes (shown after class body with source)
        val receiverNames = listOf(className) + userSupertypes(kmClass)
        val extensions = receiverNames.flatMap { getExtensionsFor(it) }
        val extensionProperties = receiverNames.flatMap { getExtensionPropertiesFor(it) }

        return buildString {
            +formatClassPrefix(kmClass)

            // Class name (simple name, not FQN)
            // For nested classes, the name might be "Parent.Nested" or "Parent$Nested"
            +kmClass.name.substringAfterLast("/").substringAfterLast(".").substringAfterLast("$")
            +formatTypeParameters(kmClass.typeParameters, typeParameters, contextPackage)

            // For annotation classes, show parameters inline without body
            if (isAnnotation && primaryConstructor != null && primaryConstructor.valueParameters.isNotEmpty()) {
                +"("
                +(primaryConstructor.valueParameters.joinToString(", ") { param ->
                    formatValueParameter(
                        param,
                        contextPackage,
                        prefix = "val ",
                        // the default values are only present in the JVM annotation, not in the Kotlin metadata
                        defaultValue = { formatAnnotationDefaultValue(clazz, param.name, contextPackage) }
                    )
                })
                +")"
                return@buildString
            }

            if (renderedConstructor != null && renderedConstructor.valueParameters.isNotEmpty()) {
                // a protected constructor can be called only from a subclass
                if (!isEnum && renderedConstructor.visibility != Visibility.PUBLIC) {
                    +" "
                    +formatVisibility(renderedConstructor.visibility)
                    +"constructor"
                }
                +"("
                +(renderedConstructor.valueParameters.joinToString(", ") { param ->
                    // a parameter not declaring a property is still needed to call the constructor
                    val prop = constructorProperties[param.name]
                    val prefix = when {
                        prop == null -> ""
                        prop.isVar -> "var "
                        else -> "val "
                    }.let { if (prop != null && isOverride(kmClass, prop)) "override $it" else it }
                    formatValueParameter(param, contextPackage, typeParameters, prefix)
                })
                +")"
            }

            if (supertypes.isNotEmpty()) {
                +" : "
                +supertypes.joinToString(", ")
            }

            +formatWhereClause(kmClass.typeParameters, typeParameters, contextPackage)

            // Check if there's any body content
            val hasEnumEntries = isEnum && kmClass.kmEnumEntries.isNotEmpty()
            val hasSealedSubclasses = isSealed && kmClass.sealedSubclasses.isNotEmpty()
            val hasBodyProperties = properties.any { it.name !in constructorParamNames }
            val hasFunctions = functions.isNotEmpty()
            // a companion object without members of the API is not worth listing
            val hasCompanion = companionProperties.isNotEmpty() || companionFunctions.isNotEmpty()
            val nestedClasses = listedNestedClasses(kmClass, scopes, clazz)
            val hasBodyContent = hasEnumEntries || hasSealedSubclasses || nestedClasses.isNotEmpty() ||
                hasBodyProperties || hasFunctions || hasCompanion

            // Only add braces if there's body content or extensions
            if (!hasBodyContent && extensions.isEmpty() && extensionProperties.isEmpty()) {
                return@buildString
            }

            +" {\n"

            // For enum classes, show enum entries
            if (hasEnumEntries) {
                // Check if there are any members rendered after the entries
                val hasMembers = nestedClasses.isNotEmpty() || hasBodyProperties || hasFunctions || hasCompanion

                kmClass.kmEnumEntries.forEachIndexed { index, entry ->
                    +"    "
                    +entry.name
                    if (index < kmClass.kmEnumEntries.size - 1) {
                        +","
                    } else if (hasMembers) {
                        // Last entry needs semicolon if there are members after
                        +";"
                    }
                    +"\n"
                }
            }

            // For sealed classes, show sealed subclasses
            if (hasSealedSubclasses) {
                val parentSimpleName = kmClass.name.substringAfterLast("/")
                kmClass.sealedSubclasses.forEach { subclassName ->
                    // subclassName format: "com/example/Parent.SubClass" or "com/example/Parent$SubClass"
                    val simpleName = subclassName.substringAfterLast("/")
                        .substringAfterLast(".")  // Remove parent class prefix like "Parent."
                        .substringAfterLast("$")  // Remove parent class prefix like "Parent$"
                    val subclassPrefix = loadClass(subclassName.replace('/', '.'))
                        ?.let { readKmClass(it) }
                        ?.let { formatClassPrefix(it) }
                        ?: "class "
                    +"    $subclassPrefix$simpleName : $parentSimpleName\n"
                }
            }

            // Nested classes, only referenced, as they can be resolved by their own name
            nestedClasses.forEach { (name, nestedKmClass) ->
                +"    "
                +formatClassPrefix(nestedKmClass)
                +name
                // an inner class can refer to the type parameters of its outer class
                val nestedTypeParameters = (if (nestedKmClass.isInner) typeParameters else emptyMap()) +
                    nestedKmClass.typeParameters.namesById()
                +formatTypeParameters(nestedKmClass.typeParameters, nestedTypeParameters, contextPackage)
                val nestedSupertypes = formatSupertypes(nestedKmClass, contextPackage, nestedTypeParameters)
                if (nestedSupertypes.isNotEmpty()) {
                    +" : "
                    +nestedSupertypes.joinToString(", ")
                }
                +formatWhereClause(nestedKmClass.typeParameters, nestedTypeParameters, contextPackage)
                +"\n"
            }

            // Properties (excluding constructor properties)
            properties
                .filter { it.name !in constructorParamNames }
                .forEach { property ->
                    +"    "
                    +formatProperty(property, inInterface = isInterface, owner = kmClass, contextPackage = contextPackage, classTypeParameters = typeParameters)
                    +"\n"
                }

            // Functions
            functions.forEach { function ->
                +"    "
                +formatFunction(function, inInterface = isInterface, owner = kmClass, contextPackage = contextPackage, classTypeParameters = typeParameters)
                +"\n"
            }

            // Companion object members
            if (companion != null && hasCompanion) {
                +"    "
                +formatVisibility(companion.visibility)
                +"companion object "
                // a custom name is needed to qualify the members, like Outer.Factory.create()
                val companionName = kmClass.companionObject
                if (companionName != DEFAULT_COMPANION_NAME) +"$companionName "
                +"{\n"

                // Companion properties
                companionProperties.forEach { property ->
                    +"        "
                    +formatProperty(property, owner = companion, contextPackage = contextPackage)
                    +"\n"
                }

                // Companion functions
                companionFunctions.forEach { function ->
                    +"        "
                    +formatFunction(function, inInterface = false, owner = companion, contextPackage = contextPackage)
                    +"\n"
                }

                +"    }\n"
            }

            +"}"

            +renderExtensions(extensions, extensionProperties, contextPackage)

            trimLastNewLine()
        }
    }

    private fun formatSupertypes(
        kmClass: KmClass,
        contextPackage: String,
        typeParameters: Map<Int, String>
    ): List<String> = kmClass.supertypes
        .map { formatType(it, contextPackage, typeParameters) }
        .filter { it != "Any" }
        // Enum supertype is redundant for enum classes
        .filter { !(kmClass.kind == ClassKind.ENUM_CLASS && it.startsWith("Enum<")) }

    /**
     * Renders the extensions shown after a class body, grouped under a `// from <source>` comment
     * for each package, or object, to import them from, properties first, then functions.
     * Both are sorted by name, and then by source class, so that the output does not depend
     * on the classpath scan order, while overloads declared in one file keep their declaration order.
     */
    private fun renderExtensions(
        functions: List<IndexedExtension>,
        properties: List<IndexedExtensionProperty>,
        contextPackage: String?
    ): String = buildString {
        val functionsBySource = functions.groupBy { it.source }
        val propertiesBySource = properties.groupBy { it.source }
        val sources = (functionsBySource.keys + propertiesBySource.keys).sorted()
        for (source in sources) {
            // each line starts with a new line, so that there is no blank line between the groups
            +"\n// from $source"
            propertiesBySource[source].orEmpty()
                .sortedWith(compareBy({ it.property.name }, { it.sourceClass }))
                .forEach { +"\n${formatExtensionProperty(it.property, contextPackage)}" }
            functionsBySource[source].orEmpty()
                .sortedWith(compareBy({ it.function.name }, { it.sourceClass }))
                .forEach { +"\n${formatFunction(it.function, contextPackage = contextPackage)}" }
        }
    }

    /**
     * The nested classes worth listing in the outer class, by their simple names, excluding
     * the sealed subclasses and the companion object, which are already rendered,
     * and the ones not visible to the users of the outer class.
     */
    private fun listedNestedClasses(
        kmClass: KmClass,
        scopes: List<ClassScope>,
        clazz: Class<*>
    ): List<Pair<String, KmClass>> {
        val sealedSubclasses = kmClass.sealedSubclasses.toSet()
        return kmClass.nestedClasses
            .filter { "${kmClass.name}.$it" !in sealedSubclasses }
            .mapNotNull { name ->
                // the binary name, as the class might be nested
                loadClass("${clazz.name}\$$name")?.let { readKmClass(it) }?.let { name to it }
            }
            .filter { (_, nestedKmClass) ->
                isListedNestedClass(nestedKmClass) && nestedKmClass.visibility.isApiIn(scopes)
            }
    }

    private fun formatAnnotationDefaultValue(
        annotationClass: Class<*>,
        parameterName: String,
        contextPackage: String
    ): String? {
        val defaultValue = try {
            annotationClass.getDeclaredMethod(parameterName).defaultValue
        } catch (_: NoSuchMethodException) {
            null
        } ?: return null
        return formatAnnotationValue(defaultValue, contextPackage)
    }

    /**
     * Format a value of an annotation parameter as a Kotlin literal.
     */
    private fun formatAnnotationValue(value: Any, contextPackage: String): String = when (value) {
        is String -> "\"${value.escapeKotlin('"')}\""
        is Char -> "'${value.toString().escapeKotlin('\'')}'"
        is Long -> "${value}L"
        is Float -> "${value}f"
        is Enum<*> -> "${formatJavaClassName(value.declaringJavaClass, contextPackage)}.${value.name}"
        is Class<*> -> "${formatJavaClassName(value, contextPackage)}::class"
        is Annotation -> {
            val annotationType = value.annotationClass.java
            annotationType.declaredMethods.sortedBy { it.name }.joinToString(
                ", ",
                prefix = "${formatJavaClassName(annotationType, contextPackage)}(",
                postfix = ")"
            ) { method ->
                "${method.name} = ${formatAnnotationValue(method.invoke(value), contextPackage)}"
            }
        }
        is Array<*> -> value.joinToString(", ", "[", "]") { formatAnnotationValue(it!!, contextPackage) }
        is BooleanArray -> value.joinToString(", ", "[", "]")
        is ByteArray -> value.joinToString(", ", "[", "]")
        is ShortArray -> value.joinToString(", ", "[", "]")
        is IntArray -> value.joinToString(", ", "[", "]")
        is LongArray -> value.joinToString(", ", "[", "]") { formatAnnotationValue(it, contextPackage) }
        is FloatArray -> value.joinToString(", ", "[", "]") { formatAnnotationValue(it, contextPackage) }
        is DoubleArray -> value.joinToString(", ", "[", "]")
        is CharArray -> value.joinToString(", ", "[", "]") { formatAnnotationValue(it, contextPackage) }
        else -> value.toString()
    }

    private fun String.escapeKotlin(quote: Char): String = buildString {
        for (char in this@escapeKotlin) {
            when (char) {
                '\\' -> +"\\\\"
                '\n' -> +"\\n"
                '\r' -> +"\\r"
                '\t' -> +"\\t"
                '$' -> +"\\$"
                quote -> {
                    append('\\')
                    append(char)
                }
                else -> append(char)
            }
        }
    }

    /**
     * Format a name of a JVM class as it is known in Kotlin,
     * following the same rules as [formatType].
     */
    private fun formatJavaClassName(javaClass: Class<*>, contextPackage: String): String {
        val kotlinName = javaClass.kotlin.qualifiedName ?: return javaClass.name
        val javaName = javaClass.name.replace('$', '.')
        val simpleName = if (kotlinName == javaName) {
            kotlinName.removePrefix("${javaClass.packageName}.")
        } else {
            // a JVM type mapped to a Kotlin built-in type, like java.lang.String
            kotlinName.substringAfterLast('.')
        }
        return if (isWellKnownType(kotlinName) || javaClass.packageName == contextPackage) simpleName else kotlinName
    }

    /**
     * @param owner the class declaring the [function], to tell whether it is an override.
     */
    private fun formatFunction(
        function: KmFunction,
        inInterface: Boolean = false,
        owner: KmClass? = null,
        contextPackage: String? = null,
        classTypeParameters: Map<Int, String> = emptyMap()
    ): String = buildString {
        val typeParameters = classTypeParameters + function.typeParameters.namesById()

        +formatVisibility(function.visibility)

        val isOverride = isOverride(owner, function)
        if (isOverride) +"override "

        // Skip 'abstract' in interfaces - all interface members are implicitly abstract
        // Also skip if it's an override (can't be both override and abstract in output)
        if (function.modality == Modality.ABSTRACT && !inInterface && !isOverride) +"abstract "
        // Only show 'open' if not an override (override implies open-ness for further subclassing)
        if (function.modality == Modality.OPEN && !isOverride) +"open "

        // Function-specific modifiers
        if (function.isTailrec) +"tailrec "
        if (function.isSuspend) +"suspend "
        if (function.isInline) +"inline "
        if (function.isInfix) +"infix "
        if (function.isOperator) +"operator "

        +"fun "
        formatTypeParameters(function.typeParameters, typeParameters, contextPackage).let {
            if (it.isNotEmpty()) +"$it "
        }

        // Handle extension function receiver
        function.receiverParameterType?.let { receiverType ->
            +formatReceiverType(receiverType, contextPackage, typeParameters)
            +"."
        }

        +function.name
        +"("

        +(function.valueParameters.joinToString(", ") { param ->
            formatValueParameter(param, contextPackage, typeParameters)
        })

        +")"

        function.returnType.let { returnType ->
            val typeName = formatType(returnType, contextPackage, typeParameters)
            if (typeName != "Unit") {
                +": "
                +typeName
            }
        }

        +formatWhereClause(function.typeParameters, typeParameters, contextPackage)
    }

    /**
     * Format a function or constructor value parameter, like `vararg elements: T` or `limit: Int = …`.
     *
     * @param prefix the `val ` or `var ` of a constructor parameter declaring a property.
     * @param defaultValue the rendering of the declared default value,
     *   which is otherwise not present in the Kotlin metadata, and therefore rendered as a marker.
     */
    private fun formatValueParameter(
        parameter: KmValueParameter,
        contextPackage: String? = null,
        typeParameters: Map<Int, String> = emptyMap(),
        prefix: String = "",
        defaultValue: () -> String? = { null }
    ): String = buildString {
        if (parameter.varargElementType != null) +"vararg "
        +prefix
        +parameter.name
        +": "
        // the type of a vararg parameter is the array type, like Array<out T>
        +formatType(parameter.varargElementType ?: parameter.type, contextPackage, typeParameters)
        if (parameter.declaresDefaultValue) {
            +" = "
            +(defaultValue() ?: DEFAULT_VALUE_MARKER)
        }
    }

    /**
     * @param owner the class declaring the [property], to tell whether it is an override.
     */
    private fun formatProperty(
        property: KmProperty,
        inInterface: Boolean = false,
        owner: KmClass? = null,
        contextPackage: String? = null,
        classTypeParameters: Map<Int, String> = emptyMap()
    ): String = buildString {
        val typeParameters = classTypeParameters + property.typeParameters.namesById()

        +formatVisibility(property.visibility)

        val isOverride = isOverride(owner, property)
        if (isOverride) +"override "

        // Skip 'abstract' in interfaces - all interface members are implicitly abstract
        if (property.modality == Modality.ABSTRACT && !inInterface && !isOverride) +"abstract "
        if (property.modality == Modality.OPEN && !isOverride) +"open "
        if (property.isConst) +"const "

        +(if (property.isVar) "var " else "val ")
        formatTypeParameters(property.typeParameters, typeParameters, contextPackage).let {
            if (it.isNotEmpty()) +"$it "
        }

        // a member extension property
        property.receiverParameterType?.let { receiverType ->
            +formatReceiverType(receiverType, contextPackage, typeParameters)
            +"."
        }

        +property.name
        +": "
        +formatType(property.returnType, contextPackage, typeParameters)
        +formatWhereClause(property.typeParameters, typeParameters, contextPackage)
    }

    private fun formatExtensionProperty(property: KmProperty, contextPackage: String? = null): String = buildString {
        val typeParameters = property.typeParameters.namesById()

        +formatVisibility(property.visibility)
        +(if (property.isVar) "var " else "val ")
        formatTypeParameters(property.typeParameters, typeParameters, contextPackage).let {
            if (it.isNotEmpty()) +"$it "
        }

        // Extension property receiver
        property.receiverParameterType?.let { receiverType ->
            +formatReceiverType(receiverType, contextPackage, typeParameters)
            +"."
        }

        +property.name
        +": "
        +formatType(property.returnType, contextPackage, typeParameters)
        +formatWhereClause(property.typeParameters, typeParameters, contextPackage)
    }

    private fun List<KmTypeParameter>.namesById(): Map<Int, String> = associate { it.id to it.name }

    private fun KmType.isNullableAny(): Boolean =
        (classifier as? KmClassifier.Class)?.name == "kotlin/Any" && isNullable

    /**
     * Format type parameter declarations, like `<K, out V : Any>`.
     * Parameters with multiple upper bounds have them listed in the [formatWhereClause] instead.
     */
    private fun formatTypeParameters(
        declarations: List<KmTypeParameter>,
        typeParameters: Map<Int, String>,
        contextPackage: String? = null
    ): String {
        if (declarations.isEmpty()) return ""
        return declarations.joinToString(", ", prefix = "<", postfix = ">") { typeParameter ->
            buildString {
                if (typeParameter.isReified) +"reified "
                when (typeParameter.variance) {
                    KmVariance.IN -> +"in "
                    KmVariance.OUT -> +"out "
                    KmVariance.INVARIANT -> {}
                }
                +typeParameter.name
                typeParameter.upperBounds.singleOrNull()?.takeUnless { it.isNullableAny() }?.let {
                    +" : "
                    +formatType(it, contextPackage, typeParameters)
                }
            }
        }
    }

    private fun formatWhereClause(
        declarations: List<KmTypeParameter>,
        typeParameters: Map<Int, String>,
        contextPackage: String? = null
    ): String {
        val constraints = declarations.filter { it.upperBounds.size > 1 }.flatMap { typeParameter ->
            typeParameter.upperBounds.map { "${typeParameter.name} : ${formatType(it, contextPackage, typeParameters)}" }
        }
        return if (constraints.isEmpty()) "" else " where ${constraints.joinToString(", ")}"
    }

    private fun formatType(
        type: KmType,
        contextPackage: String? = null,
        typeParameters: Map<Int, String> = emptyMap()
    ): String {
        if (type.isSuspend) {
            formatSuspendFunctionType(type, contextPackage, typeParameters)?.let { return it }
        }

        val baseName = when (val classifier = type.classifier) {
            is KmClassifier.Class -> {
                val fqn = classifier.name.replace("/", ".")
                // the package is separated with '/', and nested classes with '.'
                val typePackage = classifier.name.substringBeforeLast("/", "").replace("/", ".")
                // Use simple name for:
                // 1. Well-known stdlib types (kotlin.*, java.*)
                // 2. Types from the same package as the context
                if (isWellKnownType(fqn) || typePackage == contextPackage) {
                    classifier.name.substringAfterLast("/")
                } else {
                    fqn
                }
            }
            is KmClassifier.TypeAlias -> classifier.name.substringAfterLast("/")
            // a type parameter of an outer declaration might be unknown in this context
            is KmClassifier.TypeParameter -> typeParameters[classifier.id] ?: "T"
        }

        val typeArgs = type.arguments.map { projection ->
            val projType = projection.type ?: return@map "*"
            when (projection.variance) {
                KmVariance.INVARIANT, null -> formatType(projType, contextPackage, typeParameters)
                KmVariance.IN -> "in ${formatType(projType, contextPackage, typeParameters)}"
                KmVariance.OUT -> "out ${formatType(projType, contextPackage, typeParameters)}"
            }
        }

        return if (typeArgs.isEmpty()) {
            baseName + if (type.isNullable) "?" else ""
        } else {
            "$baseName<${typeArgs.joinToString(", ")}>" + if (type.isNullable) "?" else ""
        }
    }

    /**
     * Formats a suspend function type, like `suspend Foo.(Int) -> String`.
     *
     * The metadata stores it as the runtime type `FunctionN<Foo, Int, Continuation<String>, Any?>`
     * marked with [KmType.isSuspend], which is not something one can write in Kotlin.
     *
     * @return the formatted type, or `null` if the type does not have the expected shape.
     */
    private fun formatSuspendFunctionType(
        type: KmType,
        contextPackage: String?,
        typeParameters: Map<Int, String>
    ): String? {
        val arguments = type.arguments.map { it.type ?: return null }
        if (arguments.size < 2) return null
        val returnType = arguments[arguments.size - 2].arguments.singleOrNull()?.type ?: return null
        val parameters = arguments.dropLast(2)
        val receiver = parameters.firstOrNull()?.takeIf { type.isExtensionFunctionType }
        val valueParameters = if (receiver != null) parameters.drop(1) else parameters

        fun format(type: KmType) = formatType(type, contextPackage, typeParameters)

        val functionType = buildString {
            +"suspend "
            if (receiver != null) {
                +formatReceiverType(receiver, contextPackage, typeParameters)
                +"."
            }
            +valueParameters.joinToString(", ", prefix = "(", postfix = ")") { format(it) }
            +" -> "
            +format(returnType)
        }
        return if (type.isNullable) "($functionType)?" else functionType
    }

    /**
     * Formats a receiver [type], parenthesizing a suspend function type, like `(suspend () -> Unit)`,
     * which would otherwise absorb the following `.name`.
     * A nullable one is already parenthesized by [formatType], like `(suspend () -> Unit)?`.
     */
    private fun formatReceiverType(
        type: KmType,
        contextPackage: String?,
        typeParameters: Map<Int, String>
    ): String {
        val formatted = formatType(type, contextPackage, typeParameters)
        return if (type.isSuspend && !type.isNullable && formatted.startsWith("suspend ")) "($formatted)" else formatted
    }

    /**
     * Whether this function type has a receiver, like `Foo.() -> Unit`.
     * The receiver is marked with the `@ExtensionFunctionType` type annotation.
     */
    private val KmType.isExtensionFunctionType: Boolean
        get() = annotations.any { it.className == "kotlin/ExtensionFunctionType" }

    /**
     * Determines if a type is a well-known stdlib type that should use simple name.
     * Only types from kotlin.* packages are considered well-known, as LLMs
     * have reliable knowledge of Kotlin standard library types.
     * Java types (java.*) use fully qualified names to avoid ambiguity.
     */
    private fun isWellKnownType(fqn: String): Boolean {
        return fqn.startsWith("kotlin.")
    }

    private fun formatVisibility(visibility: Visibility): String {
        return when (visibility) {
            Visibility.PUBLIC -> ""  // public is default in Kotlin, no need to show
            Visibility.PRIVATE -> "private "
            Visibility.PROTECTED -> "protected "
            Visibility.INTERNAL -> "internal "
            Visibility.PRIVATE_TO_THIS -> "private "
            Visibility.LOCAL -> ""
        }
    }

    private fun isOverride(owner: KmClass?, function: KmFunction): Boolean =
        function.kind == MemberKind.FAKE_OVERRIDE || (owner != null && overrideResolver.isOverride(owner, function))

    private fun isOverride(owner: KmClass?, property: KmProperty): Boolean =
        property.kind == MemberKind.FAKE_OVERRIDE || (owner != null && overrideResolver.isOverride(owner, property))

    private fun buildFullClasspathIndex(): Triple<Map<String, List<IndexedExtension>>, Map<String, List<IndexedExtensionProperty>>, Map<String, List<String>>> {
        val funcIndex = mutableMapOf<String, MutableList<IndexedExtension>>()
        val propIndex = mutableMapOf<String, MutableList<IndexedExtensionProperty>>()
        val classes = mutableMapOf<String, IndexedClass>()

        // The first occurrence of a class on the classpath wins, as with class loading
        val indexedClasses = mutableSetOf<String>()
        val visitedRoots = mutableSetOf<File>()
        val roots = ArrayDeque(classpathRoots())

        while (roots.isNotEmpty()) {
            val root = roots.removeFirst().absoluteFile.normalize()
            if (!visitedRoots.add(root) || !root.exists()) continue

            if (root.isDirectory) {
                root.walkTopDown()
                    .filter { it.isFile && isIndexedClassFile(it.name) }
                    .forEach { file ->
                        indexClass(file.readBytes(), indexedClasses, funcIndex, propIndex, classes)
                    }
            } else {
                scanJarFile(root, roots, indexedClasses, funcIndex, propIndex, classes)
            }
        }

        // the scopes of the enclosing classes, from the outermost one, unless any of them is missing
        fun enclosingScopes(indexedClass: IndexedClass): List<ClassScope>? =
            enclosingClassNames(indexedClass.name).map { name -> classes[name]?.scope ?: return null }

        fun isApi(indexedClass: IndexedClass): Boolean =
            enclosingScopes(indexedClass)?.let { indexedClass.scope.visibility.isApiIn(it) } == true

        // the member extensions of public objects, which can be imported like the top-level ones,
        // not the ones of protected objects, as they would be shown to all the users of the receiver type
        classes.values
            .filter { indexedClass ->
                indexedClass.scope.visibility == Visibility.PUBLIC &&
                    enclosingScopes(indexedClass)?.all { it.visibility == Visibility.PUBLIC } == true
            }
            .forEach { indexedClass ->
                indexExtensions(
                    indexedClass.extensions,
                    indexedClass.extensionProperties,
                    indexedClass.binaryName,
                    source = indexedClass.name.replace('/', '.'),
                    isObjectMember = true,
                    funcIndex,
                    propIndex
                )
            }

        // Index the classes by their simple names (lowercase for case-insensitive search)
        val clsIndex = mutableMapOf<String, MutableList<String>>()
        classes.values
            .filter { it.isSearchable && isApi(it) }
            .forEach { indexedClass ->
                // the Kotlin name, like com.example.Outer.Nested, which can be resolved
                val kotlinName = indexedClass.name.replace('/', '.')
                val simpleName = kotlinName.substringAfterLast('.').lowercase()
                clsIndex.getOrPut(simpleName) { mutableListOf() }.add(kotlinName)
            }

        return Triple(funcIndex, propIndex, clsIndex)
    }

    /**
     * The metadata names of the classes enclosing the class named like `com/example/Outer.Middle.Nested`,
     * which are `com/example/Outer` and `com/example/Outer.Middle`.
     */
    private fun enclosingClassNames(name: String): List<String> {
        val packagePrefix = name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val parts = name.substringAfterLast('/').split('.')
        return (1 until parts.size).map { packagePrefix + parts.take(it).joinToString(".") }
    }

    /**
     * Collects the classpath entries visible to the [classLoader], in the order of class loading delegation.
     */
    private fun classpathRoots(): List<File> = buildList {
        val loaders = generateSequence(classLoader) { it.parent }.toList().asReversed()

        // the application class loader is not a URLClassLoader since Java 9
        if (ClassLoader.getSystemClassLoader() in loaders) {
            System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .forEach { add(File(it)) }
        }

        loaders.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.urLs.mapNotNullTo(this) { it.toFile() }
        }

        // the roots of other class loaders are only discoverable through their resources
        classLoader.getResources("META-INF/MANIFEST.MF").asSequence().mapNotNullTo(this) { url ->
            if (url.protocol == "jar") {
                url.toString().removePrefix("jar:").substringBefore("!/").toFile()
            } else null
        }
        classLoader.getResources("").asSequence().mapNotNullTo(this) { it.toFile() }
    }

    private fun URL.toFile(): File? = toString().toFile()

    private fun String.toFile(): File? = try {
        URI(this).takeIf { it.scheme == "file" }?.let { File(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Whether the class file might contain a named Kotlin class, which is decided by its metadata eventually,
     * the anonymous classes, like `Outer$1` or `Outer$function$1`, are skipped here already,
     * to avoid reading them.
     */
    private fun isIndexedClassFile(fileName: String): Boolean =
        fileName.endsWith(".class") && fileName != "module-info.class" &&
            fileName.removeSuffix(".class").split('$').drop(1).none { it.isEmpty() || it.first().isDigit() }

    private fun scanJarFile(
        jarFile: File,
        roots: ArrayDeque<File>,
        indexedClasses: MutableSet<String>,
        funcIndex: MutableMap<String, MutableList<IndexedExtension>>,
        propIndex: MutableMap<String, MutableList<IndexedExtensionProperty>>,
        classes: MutableMap<String, IndexedClass>
    ) {
        try {
            JarFile(jarFile).use { jar ->
                // the Class-Path of a manifest, like the one of a pathing jar, also belongs to the classpath
                jar.manifest?.mainAttributes?.getValue(Attributes.Name.CLASS_PATH)
                    ?.split(' ')
                    ?.filter { it.isNotBlank() }
                    ?.forEach { entry ->
                        try {
                            jarFile.toURI().resolve(entry).toString().toFile()?.let { roots.add(it) }
                        } catch (_: IllegalArgumentException) {
                            // Skip malformed Class-Path entries
                        }
                    }
                jar.entries().asSequence()
                    // skip the class versions of multi-release jars
                    .filter { !it.name.startsWith("META-INF/") }
                    .filter { isIndexedClassFile(it.name.substringAfterLast('/')) }
                    .forEach { entry ->
                        val bytes = jar.getInputStream(entry).use { it.readBytes() }
                        indexClass(bytes, indexedClasses, funcIndex, propIndex, classes)
                    }
            }
        } catch (_: Exception) {
            // Skip unreadable JARs
        }
    }

    private fun indexClass(
        bytes: ByteArray,
        indexedClasses: MutableSet<String>,
        funcIndex: MutableMap<String, MutableList<IndexedExtension>>,
        propIndex: MutableMap<String, MutableList<IndexedExtensionProperty>>,
        classes: MutableMap<String, IndexedClass>
    ) {
        val reader = try {
            ClassReader(bytes)
        } catch (_: Exception) {
            return
        }
        val className = reader.className.replace('/', '.')
        if (!indexedClasses.add(className)) return

        val metadata = try {
            readMetadata(reader)?.let { KotlinClassMetadata.readLenient(it) }
        } catch (_: Exception) {
            null
        } ?: return

        if (metadata is KotlinClassMetadata.Class) {
            val kmClass = metadata.kmClass
            // local and anonymous classes have names starting with '.'
            if (kmClass.name.startsWith('.')) return
            val isNested = kmClass.name.substringAfterLast('/').contains('.')
            // the member extensions of classes can be called only in the scope of their dispatch receiver,
            // while the ones of objects can be imported, which is decided once all the enclosing classes are known
            val isObject = kmClass.kind == ClassKind.OBJECT || kmClass.kind == ClassKind.COMPANION_OBJECT
            classes.getOrPut(kmClass.name) {
                IndexedClass(
                    name = kmClass.name,
                    binaryName = className,
                    scope = kmClass.scope,
                    isSearchable = !isNested || isListedNestedClass(kmClass),
                    extensions = if (isObject) {
                        kmClass.functions.filter { it.receiverParameterType != null }
                    } else emptyList(),
                    extensionProperties = if (isObject) {
                        kmClass.properties.filter { it.receiverParameterType != null }
                    } else emptyList()
                )
            }
            return
        }

        val kmPackage = when (metadata) {
            is KotlinClassMetadata.FileFacade -> metadata.kmPackage
            is KotlinClassMetadata.MultiFileClassPart -> metadata.kmPackage
            else -> return
        }
        indexExtensions(
            kmPackage.functions,
            kmPackage.properties,
            className,
            // the facade class name for the default package, which has no name
            source = className.substringBeforeLast('.'),
            isObjectMember = false,
            funcIndex,
            propIndex
        )
    }

    private fun indexExtensions(
        functions: List<KmFunction>,
        properties: List<KmProperty>,
        className: String,
        source: String,
        isObjectMember: Boolean,
        funcIndex: MutableMap<String, MutableList<IndexedExtension>>,
        propIndex: MutableMap<String, MutableList<IndexedExtensionProperty>>
    ) {
        // Only the extensions of the API are indexed. Once imported, even the members of an object
        // are shown to all the users of the receiver type, like the top-level ones, so only the public ones are.
        for (function in functions.filter { it.visibility.isApiIn(emptyList()) }) {
            val receiverType = function.receiverParameterType ?: continue
            val receiverName = getReceiverTypeName(receiverType)

            funcIndex.getOrPut(receiverName) { mutableListOf() }
                .add(IndexedExtension(function, className, source, isObjectMember))
        }

        for (property in properties.filter { it.visibility.isApiIn(emptyList()) }) {
            val receiverType = property.receiverParameterType ?: continue
            val receiverName = getReceiverTypeName(receiverType)

            propIndex.getOrPut(receiverName) { mutableListOf() }
                .add(IndexedExtensionProperty(property, className, source, isObjectMember))
        }
    }

    /**
     * Whether the nested class is listed in its outer class, and found by the [search],
     * provided that it is part of the API, as well as all its enclosing classes.
     * Companion objects are not, as they are already rendered within their outer class,
     * neither are the synthetic classes, like the `$serializer` generated by kotlinx.serialization.
     */
    private fun isListedNestedClass(kmClass: KmClass): Boolean =
        kmClass.kind != ClassKind.COMPANION_OBJECT &&
            !kmClass.name.substringAfterLast('/').contains('$')

    /**
     * Reads the `@Metadata` annotation from the class bytes, without loading the class.
     */
    private fun readMetadata(reader: ClassReader): Metadata? {
        var metadata: Metadata? = null
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                if (descriptor == METADATA_DESCRIPTOR) MetadataVisitor { metadata = it } else null
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return metadata
    }

    private class MetadataVisitor(
        private val onMetadata: (Metadata) -> Unit
    ) : AnnotationVisitor(Opcodes.ASM9) {

        private val values = mutableMapOf<String, Any>()

        override fun visit(name: String, value: Any) {
            values[name] = value
        }

        // arrays of strings, and empty arrays of any type
        override fun visitArray(name: String): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
            private val elements = mutableListOf<Any>()
            override fun visit(name: String?, value: Any) {
                elements += value
            }
            override fun visitEnd() {
                values[name] = elements
            }
        }

        override fun visitEnd() {
            onMetadata(
                jvmMetadata(
                    kind = values["k"] as? Int,
                    metadataVersion = when (val version = values["mv"]) {
                        is IntArray -> version
                        is List<*> -> version.filterIsInstance<Int>().toIntArray()
                        else -> null
                    },
                    data1 = (values["d1"] as? List<*>)?.filterIsInstance<String>()?.toTypedArray(),
                    data2 = (values["d2"] as? List<*>)?.filterIsInstance<String>()?.toTypedArray(),
                    extraString = values["xs"] as? String,
                    packageName = values["pn"] as? String,
                    extraInt = values["xi"] as? Int
                )
            )
        }

    }

    private fun getReceiverTypeName(type: KmType): String {
        return when (val classifier = type.classifier) {
            is KmClassifier.Class -> classifier.name.replace('/', '.')
            is KmClassifier.TypeAlias -> classifier.name.replace('/', '.')
            is KmClassifier.TypeParameter -> "TypeParameter"
        }
    }

    /**
     * Get extension functions for a given fully qualified type name.
     */
    private fun getExtensionsFor(typeName: String): List<IndexedExtension> =
        extensionIndex[typeName].orEmpty()

    /**
     * Get extension properties for a given fully qualified type name.
     */
    private fun getExtensionPropertiesFor(typeName: String): List<IndexedExtensionProperty> =
        extensionPropertyIndex[typeName].orEmpty()

    private fun requirePositivePageSize(pageSize: Int) {
        require(pageSize > 0) { "pageSize must be positive, but was: $pageSize" }
    }

    override fun resolve(
        name: String,
        page: Int,
        pageSize: Int
    ): String? {
        requirePositivePageSize(pageSize)
        val content = resolve(name) ?: return null

        // Parse the content into class header, members, and extensions
        val lines = content.lines()

        // Find the class/interface declaration line (first line)
        val classDeclarationLine = lines.firstOrNull() ?: return null

        // Find closing brace of class body
        val closingBraceIndex = lines.indexOfFirst { it == "}" }
        if (closingBraceIndex < 0) {
            // No class body, return as-is
            val header = "// $name [page 1/1]"
            return "$header\n$content"
        }

        // Extract members (lines between class declaration and closing brace)
        // Preserve relative indentation by only removing the base 4-space indent
        val memberLines = lines.subList(1, closingBraceIndex)
            .filter { it.isNotBlank() }
            .map { line ->
                // Remove exactly 4 spaces if present, preserving any additional indentation
                if (line.startsWith("    ")) line.substring(4) else line.trimStart()
            }

        // A member is paginated as a whole: the lines indented deeper, and the closing brace
        // of a block like the companion object, belong to the member started above them
        val members = buildList<MutableList<String>> {
            for (line in memberLines) {
                if (isNotEmpty() && (line.first().isWhitespace() || line == "}")) {
                    last().add(line)
                } else {
                    add(mutableListOf(line))
                }
            }
        }.map { it.joinToString("\n") }

        // Enum entries are followed by a semicolon only when the members come after them
        val enumEntryCount = if (classDeclarationLine.contains("enum class ")) {
            members.indexOfFirst { it.endsWith(";") } + 1
        } else 0

        // Extract extensions (lines after closing brace), preserving package headers
        // We need to parse extensions as items with their package context
        data class ExtensionItem(val line: String, val packageHeader: String?)
        val extensionItems = mutableListOf<ExtensionItem>()
        var currentPackage: String? = null

        if (closingBraceIndex < lines.size - 1) {
            for (line in lines.subList(closingBraceIndex + 1, lines.size)) {
                if (line.isBlank()) continue
                if (line.startsWith("// from ")) {
                    currentPackage = line
                } else {
                    extensionItems.add(ExtensionItem(line, currentPackage))
                }
            }
        }

        // Combine members and extensions for pagination
        // Members are represented as items without package context
        val allItems = members.map { ExtensionItem(it, null) } + extensionItems
        val totalItems = allItems.size

        // Calculate total pages based on all items (members + extensions)
        val totalPages = if (totalItems == 0) 1 else ((totalItems + pageSize - 1) / pageSize)
        val actualPage = page.coerceIn(1, totalPages)

        // Get items for this page
        val startIndex = (actualPage - 1) * pageSize
        val endIndex = minOf(startIndex + pageSize, totalItems)
        val pageItems = if (totalItems > 0) allItems.subList(startIndex, endIndex) else emptyList()

        // Build the page content
        return buildString {
            +"// $name [page $actualPage/$totalPages]\n"

            // Separate members (no package header) from extensions (have package header)
            val pageMembers = pageItems.filter { it.packageHeader == null }
            val pageExtensions = pageItems.filter { it.packageHeader != null }

            // Only include class body if there are members on this page
            if (pageMembers.isNotEmpty()) {
                +classDeclarationLine
                +"\n"
                if (enumEntryCount > 0 && startIndex >= enumEntryCount) {
                    // without the entries on this page, the semicolon still has to precede the members
                    +"    ;\n"
                }
                for (item in pageMembers) {
                    for (line in item.line.lines()) {
                        +"    "
                        +line
                        +"\n"
                    }
                }
                +"}"
                if (pageExtensions.isNotEmpty()) {
                    +"\n"
                }
            } else if (members.isEmpty() && actualPage == 1) {
                // A class without members still needs its declaration, e.g. to show the constructor
                +classDeclarationLine.removeSuffix(" {")
                +"\n"
            }

            // Add extensions that are on this page
            if (pageExtensions.isNotEmpty()) {
                var lastPackage: String? = null
                for ((line, packageHeader) in pageExtensions) {
                    if (packageHeader != lastPackage) {
                        +packageHeader!! // filtered above to be non-null
                        +"\n"
                        lastPackage = packageHeader
                    }
                    +line
                    +"\n"
                }
            }

            trimLastNewLine()
        }

    }

    override fun search(query: String, page: Int, pageSize: Int): String? {
        requirePositivePageSize(pageSize)
        val queryLower = query.lowercase()

        // Find all matching classes: classes whose simple name contains the query (case-insensitive)
        val matchingClasses = classIndex.entries
            .filter { (simpleName, _) -> simpleName.contains(queryLower) }
            .flatMap { (_, fqns) -> fqns }
            .sorted()  // Sort alphabetically by fully qualified name

        if (matchingClasses.isEmpty()) {
            return null
        }

        // Calculate pagination
        val totalItems = matchingClasses.size
        val totalPages = (totalItems + pageSize - 1) / pageSize
        val actualPage = page.coerceIn(1, totalPages)

        // Get items for this page
        val startIndex = (actualPage - 1) * pageSize
        val endIndex = minOf(startIndex + pageSize, totalItems)
        val pageItems = matchingClasses.subList(startIndex, endIndex)

        // Build the result
        return buildString {
            +"// search results for \"$query\" [page $actualPage/$totalPages]\n"
            for (className in pageItems) {
                +className
                +"\n"
            }
            trimLastNewLine()
        }
    }
}
