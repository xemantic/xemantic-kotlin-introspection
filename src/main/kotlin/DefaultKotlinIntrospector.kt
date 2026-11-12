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

import com.xemantic.kotlin.core.text.unaryPlus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.Type as AsmType
import java.io.File
import java.io.IOException
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.Type as JavaType
import java.lang.reflect.WildcardType
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.metadata.*
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.moduleName
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.jvm.syntheticMethodForAnnotations
import kotlin.metadata.jvm.annotations
import kotlin.jvm.optionals.getOrNull
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.time.measureTime
import kotlin.metadata.jvm.Metadata as jvmMetadata

/**
 * Represents an indexed extension function with its source location.
 */
internal data class IndexedExtension(
    val function: KmFunction,
    override val sourceClass: String,  // fully qualified class name where it's defined
    override val source: String,  // the package, or the object, to import it from
    val isObjectMember: Boolean,  // whether the source is an object
    val deprecation: Deprecation? = null,  // never a hidden one, as the hidden extensions are not indexed
    // annotated with @kotlin.internal.HidesMembers, called instead of a member, see jvmOnlyFunctionsNotHidden
    val hidesMembers: Boolean = false,
    override val receiverBounds: Set<String> = emptySet(),
    override val isBuiltinExtension: Boolean = false,  // whether it is shown with the built-in types, see isBuiltinExtensionSource
    // the deprecation of the companion object it extends, when listed with its class, see withCompanionDeprecation
    val companionDeprecation: Deprecation? = null,
    // whether it is also called on an instance of the class, not reporting the companionDeprecation then
    val isAlsoCalledOnInstance: Boolean = false
) : ReceiverBounded

/**
 * An indexed extension listed only for the classes which are subtypes of all its [receiverBounds].
 */
internal sealed interface ReceiverBounded {

    /**
     * The bounds of a type parameter receiver with multiple bounds, see [receiverBoundsOf], or none.
     */
    val receiverBounds: Set<String>

    /**
     * Whether it is shown with the built-in types, see [isBuiltinExtensionSource].
     */
    val isBuiltinExtension: Boolean

    /**
     * The package, or the object, to import it from.
     */
    val source: String

    /**
     * The binary name of the class declaring it, like `com.example.UtilsKt`, or `com.example.Outer$Helpers` of an object.
     */
    val sourceClass: String

}

/**
 * Represents an indexed extension property with its source location.
 */
internal data class IndexedExtensionProperty(
    val property: KmProperty,
    override val sourceClass: String,  // fully qualified class name where it's defined
    override val source: String,  // the package, or the object, to import it from
    val isObjectMember: Boolean,  // whether the source is an object
    val setterVisibility: Visibility?,  // when its setter can be called, see apiSetterVisibility
    // never a hidden one, as the hidden extensions are not indexed, but maybe hiding an accessor, see Deprecation.accessors
    val deprecation: Deprecation? = null,
    override val receiverBounds: Set<String> = emptySet(),
    override val isBuiltinExtension: Boolean = false,  // whether it is shown with the built-in types, see isBuiltinExtensionSource
    // the deprecation of the companion object it extends, when listed with its class, see withCompanionDeprecation
    val companionDeprecation: Deprecation? = null,
    // whether it is also called on an instance of the class, not reporting the companionDeprecation then
    val isAlsoCalledOnInstance: Boolean = false
) : ReceiverBounded

/**
 * A Kotlin class found on the classpath, by its metadata name, like `com/example/Outer.Nested`,
 * with the member extensions, in case of an object, which can be imported from it.
 */
internal class IndexedClass(
    val name: String,
    val binaryName: String,
    val scope: ClassScope,
    val extensions: List<IndexedExtension>,
    val extensionProperties: List<IndexedExtensionProperty>
) {
    /**
     * The metadata name in the package of the binary name, see [locatedNameOf], by which the classes are indexed.
     */
    val locatedName: String get() = locatedNameOf(binaryName, name)
}

// the visibilities of the constructors which do not make a class subclassable, see classScope
private val PRIVATE_VISIBILITIES = setOf(Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS, Visibility.LOCAL)

/**
 * The [ClassScope] of the [kmClass], whose declarations have the [deprecations].
 *
 * @param isUsable whether the class can be used in the source code, see [ClassScope.isUsable].
 * @param located the located name, see [locatedNameOf], of a class in the package of the [kmClass] by its metadata name,
 *   like of its sealed subclasses, see [ClassDeclarations.located].
 */
private fun classScope(
    kmClass: KmClass,
    isUsable: Boolean,
    deprecations: Deprecations,
    located: (String) -> String = { it }
): ClassScope {
    // a sealed class cannot be extended outside its own module, and an interface cannot have protected members
    val isOpenClass = kmClass.kind == ClassKind.CLASS &&
        (kmClass.modality == Modality.OPEN || kmClass.modality == Modality.ABSTRACT)
    return ClassScope(
        visibility = kmClass.visibility,
        kind = kmClass.kind,
        isUsable = isUsable,
        isSubclassable = isOpenClass && kmClass.constructors.any {
            it.visibility !in PRIVATE_VISIBILITIES && !deprecations.isHidden(it)
        },
        // also of a sealed interface, which the user code can implement through an open subclass
        sealedSubclasses = if (
            (kmClass.kind == ClassKind.CLASS || kmClass.kind == ClassKind.INTERFACE) && kmClass.modality == Modality.SEALED
        ) {
            kmClass.sealedSubclasses.map(located)
        } else emptyList()
    )
}

/**
 * Tells the [Deprecation] of each declaration of a class, which the metadata does not tell,
 * and so which ones are hidden, and cannot be used in the source code, like the ones deprecated with the `HIDDEN` level.
 * The one place deciding it, for listing, resolving, and indexing the declarations alike.
 */
private interface Deprecations {

    fun deprecation(function: KmFunction): Deprecation?

    fun deprecation(constructor: KmConstructor): Deprecation?

    /**
     * The deprecation of the [property], also of its accessors, like with `@set:Deprecated(level = HIDDEN)`,
     * see [Deprecation.accessors].
     */
    fun deprecation(property: KmProperty): Deprecation?

    /**
     * The deprecation of the [enumEntry] of an enum class, the one of its field, told only for a class of the classpath,
     * see `FoundClass`, as no enum class among the built-in types has a deprecated entry.
     */
    fun deprecation(enumEntry: KmEnumEntry): Deprecation? = null

}

private fun Deprecations.isHidden(function: KmFunction): Boolean = deprecation(function)?.isHidden == true

private fun Deprecations.isHidden(constructor: KmConstructor): Boolean = deprecation(constructor)?.isHidden == true

/**
 * Whether the [property] is hidden in the [scope], also when only its getter is, see [Deprecation.hidesGetter],
 * unless its setter is part of the API, so that it can still be assigned.
 * An override whose getter is hidden is dropped too, when its setter is not part of the API,
 * like a `protected` one in a final class, as it can be neither read, nor assigned,
 * while an override whose getter is visible, and whose setter is not part of the API, or hidden,
 * is still rendered as a `var`, as a `val` cannot override a `var`, see `propertyDeclaration`.
 */
private fun Deprecations.isHidden(property: KmProperty, scope: DeclarationScope): Boolean {
    val deprecation = deprecation(property) ?: return false
    return deprecation.isHidden || (deprecation.hidesGetter && property.apiSetterVisibility(scope, deprecation) == null)
}

/**
 * The functions which are part of the API in the [scope], see [DeclarationScope.isApi], unless hidden,
 * when the [deprecations] of the declarations of their declaring class are known.
 */
@JvmName("apiFunctions")
private fun List<KmFunction>.inApi(scope: DeclarationScope, deprecations: Deprecations?): List<KmFunction> =
    filter { scope.isApi(it.visibility) && deprecations?.isHidden(it) != true }

/**
 * The properties which are part of the API, like the functions, see [inApi].
 */
@JvmName("apiProperties")
private fun List<KmProperty>.inApi(scope: DeclarationScope, deprecations: Deprecations?): List<KmProperty> =
    filter { scope.isApi(it.visibility) && deprecations?.isHidden(it, scope) != true }

/**
 * The visibility of the setter of this property, when it is a `var` whose setter is part of the API,
 * like the property itself, see [inApi], or else `null`, telling that the property cannot be assigned
 * from the user code, and is rendered as a `val`, like with `private set`, or the `protected set` of a final class,
 * or `@set:Deprecated(level = HIDDEN)`, told by the [deprecation] of this property.
 */
private fun KmProperty.apiSetterVisibility(scope: DeclarationScope, deprecation: Deprecation?): Visibility? =
    declaredSetterVisibility?.takeIf { scope.isApi(it) && deprecation?.hidesSetter != true }

/**
 * The visibility of the setter of this property as declared, when it is a `var`, or else `null`.
 */
private val KmProperty.declaredSetterVisibility: Visibility? get() = if (isVar) setter?.visibility ?: visibility else null

/**
 * Whether the user code might override the members of the [owner], unless told otherwise, see [ApiClass.isOpen],
 * never for a final one, like an object.
 */
private fun isOpenForOverridesByDefault(owner: KmClass?): Boolean = owner != null && owner.modality != Modality.FINAL

/**
 * Whether the user code can override the members of the [kmClass], the [apiClass] of the API:
 * the ones of an interface which it can implement, unless sealed, or else through an open subclass,
 * or the ones of a class which it can subclass, see [ApiClass.isOpen].
 */
private fun isOpenForOverrides(kmClass: KmClass, apiClass: ApiClass): Boolean =
    (kmClass.kind == ClassKind.INTERFACE && kmClass.modality != Modality.SEALED) || apiClass.isOpen

/**
 * Whether this is a data class, or a value class, which cannot be declared without a property for each parameter
 * of its primary constructor, never a data object, which cannot have a constructor.
 */
private val KmClass.isDataOrValueClass: Boolean get() = kind == ClassKind.CLASS && (isData || isValue)

internal class DefaultKotlinIntrospector(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    scanEntireClasspath: Boolean = true,
    // the declarations of the built-in types, see readKotlinBuiltinClasses, replaced only by the tests
    private val readBuiltinClasses: () -> Map<String, KmClass> = { readKotlinBuiltinClasses() }
) : KotlinIntrospector {

    private val logger = KotlinLogging.logger {}

    // Index of extension functions: receiver type name -> list of extensions
    internal val extensionIndex: Map<String, List<IndexedExtension>>

    // Index of extension properties: receiver type name -> list of extension properties
    internal val extensionPropertyIndex: Map<String, List<IndexedExtensionProperty>>

    // the extensions on a type parameter with multiple bounds of the extension indexes, under each of them, see listedFor
    private val multipleBoundsExtensionIndex by lazy { extensionIndex.withMultipleBounds() }

    private val multipleBoundsExtensionPropertyIndex by lazy { extensionPropertyIndex.withMultipleBounds() }

    // the names of all the extensions of the extension indexes, telling whether any is named like a member resolved,
    // see listedExtensions
    private val extensionNames: Set<String> by lazy {
        extensionIndex.values.flatMapTo(HashSet()) { extensions -> extensions.map { it.function.name } }.also { names ->
            extensionPropertyIndex.values.forEach { extensions -> extensions.mapTo(names) { it.property.name } }
        }
    }

    // Index of all Kotlin classes: simple name (lowercase) -> list of fully qualified names
    internal val classIndex: Map<String, List<String>>

    /**
     * The typealiases of the API, by their fully qualified names, like `kotlin.collections.ArrayList`,
     * with the binary names of the file facade classes declaring them, see [resolveTypeAlias].
     */
    private val typeAliasIndex: Map<String, String>

    /**
     * The version of the standard library of the classpath, telling whether a declaration
     * annotated with `@DeprecatedSinceKotlin` is deprecated, and with which level, see [isSince].
     * Declared before the classpath scan, which reads it.
     */
    private val stdlibVersion: KotlinVersion by lazy {
        try {
            // the KotlinVersion.CURRENT of the standard library the class loader uses, which is the same
            // as the one of this library, unless the class loader prefers its own classes, like an isolated one,
            // regardless of how the standard library is packaged, like in a directory, a fat jar, or a jlink image
            Class.forName(KotlinVersion::class.java.name, true, classLoader)
                .getField("CURRENT")
                .get(null)
                ?.let { parseKotlinVersion(it.toString()) }
        } catch (_: Exception) {
            null
        } catch (e: LinkageError) {
            logger.debug(e) { "Cannot load KotlinVersion of the class loader" }
            null
        } ?: KotlinVersion.CURRENT // like for a standard library relocated by shading
    }

    // Declarations of the built-in types, by their Kotlin class name, like kotlin/collections/List
    private val builtinClasses: Map<String, KmClass> by lazy { readBuiltinClasses() }

    /**
     * The failures of the class lookups, and of the class file reads of the [overrideResolver], which might be transient,
     * retried once per resolve, so that the [overrideResolver] does not cache what it computes from them meanwhile.
     * Every entry point looking classes up is to run as an operation, see [TransientFailures.operation], like [resolve],
     * as an attempt outside an operation is retried on every access, never deemed final.
     */
    private val transientFailures = TransientFailures()

    private val overrideResolver = OverrideResolver(
        classLoader = classLoader,
        loadClassByMetadataName = { name -> findClassByMetadataName(name)?.clazz },
        loadKmClassByMetadataName = ::loadKmClass,
        // read directly, without loading the class, like a DefaultImpls class which an interface might have
        readClassBytesByMetadataName = { name -> readClassBytes(binaryNameOf(name)) },
        unloadableClassBytesByMetadataName = { name -> lookUpClassByBinaryName(binaryNameOf(name)).unloadableClassBytes },
        transientFailures = transientFailures,
        builtinClasses = { builtinClasses },
        builtinTypes = builtinTypes
    )

    companion object {

        /**
         * Kotlin built-in type names mapped to their KClass.
         * These types map to JVM types and don't have Kotlin metadata.
         */
        @OptIn(ExperimentalUnsignedTypes::class)
        internal val builtinTypes: Map<String, KClass<*>> = mapOf(
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

        /**
         * The Java classes implementing the mutable collection types whose JVM classes declare members of their own,
         * which kotlin-reflect describes as members of the mutable types, see [mutableCollectionFunctions],
         * declaring the type parameters of the same names.
         */
        private val mutableCollectionImplementations: Map<String, KClass<*>> = mapOf(
            "kotlin.collections.MutableCollection" to java.util.AbstractCollection::class,
            "kotlin.collections.MutableList" to java.util.AbstractList::class,
            "kotlin.collections.MutableSet" to java.util.AbstractSet::class,
            "kotlin.collections.MutableMap" to java.util.AbstractMap::class
        )

        private const val ANY_CLASS_NAME = "kotlin/Any"

        private const val NOTHING_CLASS_NAME = "kotlin/Nothing"

        // a star projection, as the projection it stands for, see ReceiverMatcher.projected, never modified,
        // told by its instance from the type given explicitly, see ReceiverMatcher.isTrivialStarConstraint
        private val STAR = KmVariance.OUT to classType(ANY_CLASS_NAME, nullable = true)

        // a star projection of a contravariant type parameter, see STAR
        private val STAR_OF_CONTRAVARIANT = KmVariance.IN to classType(NOTHING_CLASS_NAME)

        /**
         * The first of the ids standing for the type parameters of a class inheriting the members of its supertypes,
         * see [inheritingTypeParameterId].
         */
        private const val INHERITING_TYPE_PARAMETER_IDS = 1_000_000

        // also told by the OverrideResolver
        internal const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

        private const val DEPRECATED_DESCRIPTOR = "Lkotlin/Deprecated;"

        private const val HIDES_MEMBERS_DESCRIPTOR = "Lkotlin/internal/HidesMembers;"

        private const val DEPRECATED_SINCE_KOTLIN_DESCRIPTOR = "Lkotlin/DeprecatedSinceKotlin;"


        // the supertype of the serializers generated by kotlinx.serialization
        private const val GENERATED_SERIALIZER = "kotlinx/serialization/internal/GeneratedSerializer"

        /**
         * A Kotlin version, like `1.6` of `@DeprecatedSinceKotlin(hiddenSince = "1.6")`, or `2.4.21`,
         * also of a pre-release, like `2.5.0-Beta1`, or `null` if malformed.
         */
        private fun parseKotlinVersion(version: String): KotlinVersion? {
            val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
            if (parts.size !in 2..3) return null
            return try {
                KotlinVersion(parts[0], parts[1], parts.getOrElse(2) { 0 })
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Marks a parameter declaring a default value, which is not present in the metadata,
         * telling that the argument can be omitted.
         */
        private const val DEFAULT_VALUE_MARKER = "…"

        /**
         * Follows the name of a class without any constructor callable from the user code,
         * which therefore can neither be instantiated, nor subclassed there,
         * rendered regardless of the actual visibility and parameters of its constructors,
         * like the internal or protected ones, which cannot be called either,
         * except for a data class, or a value class, rendered with the parameters of its primary constructor.
         */
        private const val NOT_CONSTRUCTIBLE_MARKER = " private constructor()"

        private const val DEFAULT_COMPANION_NAME = "Companion"

        private const val ENUM_CLASS_NAME = "kotlin/Enum"

        /**
         * Follows the sealed subclasses listed in a sealed class, when some of them are not part of the API,
         * like the internal ones, which a `when` expression of the user code cannot name, and still has to cover.
         */
        private const val SEALED_SUBCLASSES_OMITTED = "// sealed subclasses not part of the API omitted"

        /**
         * Marks the legacy context receivers of a class, see [formatClassContextReceivers].
         */
        private const val LEGACY_CONTEXT_RECEIVERS = "/* legacy context receivers, not compiled by Kotlin 2.2+ */"

        private const val DEFAULT_IMPLS = "DefaultImpls"

        /**
         * The simple names of the classes the Kotlin compiler generates as nested in a Kotlin class,
         * without declaring them in its metadata, nor with the class metadata, see [NestedClassFiles]:
         * the bodies of the interface members compiled without the JVM default methods,
         * and the mappings of the enum entries used by a `when` expression, or by the `entries` property.
         */
        private val GENERATED_NESTED_CLASSES = setOf(DEFAULT_IMPLS, "WhenMappings", "EntriesMappings")

        private const val ANNOTATIONS_METHOD_SUFFIX = "\$annotations"

    }

    init {
        val scanTime = measureTime {
            if (scanEntireClasspath) {
                val scan = scanClasspath()
                // sorted once, so that the output does not depend on the classpath scan order
                extensionIndex = scan.extensionIndex.sortedByNameAndSourceClass()
                extensionPropertyIndex = scan.extensionPropertyIndex.sortedByNameAndSourceClass()
                classIndex = scan.classIndex.withFunctionTypeClasses()
                typeAliasIndex = scan.typeAliasIndex
                logger.debug {
                    "Classpath scan read ${scan.readClassFileCount} class files, " +
                        "skipped ${scan.skippedClassFileCount} nested ones"
                }
            } else {
                extensionIndex = emptyMap()
                extensionPropertyIndex = emptyMap()
                classIndex = emptyMap()
                typeAliasIndex = emptyMap()
            }
        }
        // an extension on a type parameter with multiple bounds is indexed under each of them, but counted once
        val extensionsFound = extensionIndex.values.flatMapTo(HashSet()) { it }.size +
            extensionPropertyIndex.values.flatMapTo(HashSet()) { it }.size
        logger.info { "Classpath scan completed in $scanTime, found $extensionsFound extensions" }
    }

    /**
     * The declaration of the [name], or `null` if not found,
     * reached through the [typeAliases] already, see [resolveTypeAlias], not to follow them again.
     *
     * @param aliasedClass the class the last of the [typeAliases] expands to, see [enclosingContext],
     *   the [name] then being the one of its member, so that only the class itself is looked up,
     *   not a class nested in it, which Kotlin does not resolve through the typealias.
     * @param isAliasedWithTypeArguments whether the typealias expands to the [aliasedClass] with type arguments,
     *   so that only what is called on the name of the class is resolved, see [resolveTypeAlias].
     */
    private fun resolve(
        name: String,
        typeAliases: Set<String> = emptySet(),
        aliasedClass: String? = null,
        isAliasedWithTypeArguments: Boolean = false
    ): Resolved<*>? {
        val parts = unescapeQualifiedName(name).split(".")

        // Try to find where the class name ends and member name begins
        // by progressively trying to load the class
        for (i in parts.size downTo 1) {
            if (aliasedClass != null && i != parts.size - 1) continue
            val className = parts.take(i).joinToString(".")
            val memberName = if (i < parts.size) parts.drop(i).joinToString(".") else null

            // a function type class, synthesized by the compiler, like kotlin.Function1
            FunctionTypeClass.of(className)?.let { functionTypeClass ->
                // nothing of a function type is called on its name, like of typealias Handler = (Int) -> String
                if (isAliasedWithTypeArguments) return null
                return if (memberName != null) {
                    resolveFunctionTypeMember(functionTypeClass, className, memberName)
                } else {
                    formatFunctionTypeClass(functionTypeClass, className)
                }
            }

            // a built-in type, like kotlin.Int, or its companion object, like kotlin.Int.Companion,
            // rendered like any other class, but with the extensions of the built-ins, see listedExtensions
            if (className in builtinTypes || isBuiltinCompanion(className)) {
                val declarations = classDeclarations(kotlinClassName(className))
                val kmClass = declarations?.kmClass ?: return null
                val apiClass = apiClasses.apiClass(kmClass.name) ?: return null
                return if (memberName != null) {
                    resolveMember(
                        kmClass, apiClass, className, declarations, memberName, isBuiltin = true,
                        isOnlyCalledOnClassName = isAliasedWithTypeArguments
                    )
                } else {
                    formatClass(kmClass, apiClass, className, declarations, isBuiltin = true)
                }
            }

            val foundClass = findClassByName(className)
            if (foundClass == null) {
                val typeAlias = resolveTypeAlias(className, memberName, typeAliases)
                if (typeAlias != null) return typeAlias
                continue
            }
            val metadata = foundClass.metadata ?: continue

            val resolved = when (metadata) {

                is KotlinClassMetadata.Class -> {
                    val kmClass = metadata.kmClass
                    // neither a class which is not part of the API, nor anything declared within it, is resolved,
                    // while its name might still be a member of the outer class, like an extension property,
                    // the class told by its own scope, and the enclosing ones by its located name,
                    // as it might not be the one of its metadata name, like when relocated by shading
                    val apiClass = foundClass.scope?.let {
                        apiClasses.apiClass(foundClass.located(kmClass.name), it)
                    } ?: continue
                    if (memberName != null) {
                        resolveMember(
                            kmClass, apiClass, className, foundClass, memberName, aliasedClass = aliasedClass,
                            isOnlyCalledOnClassName = isAliasedWithTypeArguments
                        )
                    } else {
                        formatClass(kmClass, apiClass, className, foundClass)
                    }
                }

                is KotlinClassMetadata.FileFacade -> memberName?.let {
                    resolvePackageMember(listOf(metadata.kmPackage to foundClass), it)
                }

                is KotlinClassMetadata.MultiFileClassPart -> memberName?.let {
                    resolvePackageMember(listOf(metadata.kmPackage to foundClass), it)
                }

                // the facade only lists the parts holding the actual declarations
                is KotlinClassMetadata.MultiFileClassFacade -> memberName?.let {
                    resolvePackageMember(
                        metadata.partClassNames.mapNotNull { partName ->
                            val part = findClassByMetadataName(partName)
                            (part?.metadata as? KotlinClassMetadata.MultiFileClassPart)?.let { it.kmPackage to part }
                        },
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
     * The typealias of the API named [name], see [typeAliasIndex], rendered as its declaration,
     * like `typealias ArrayList<E> = java.util.ArrayList<E>`, as a Java class it might expand to is not rendered,
     * or, for a [memberName], the member of the class it expands to, as resolved by the name of that class,
     * but neither a class nested in it, nor its companion object by its name, nor anything within them,
     * like `Alias.Nested`, or `Alias.Companion.create`, which Kotlin does not resolve, unlike `Alias.create`,
     * following the declaration of the typealias, when deprecated, telling that it cannot be used as is,
     * but of a class with type arguments, like of `typealias Names = List<String>`, or `typealias Box<T> = Holder<T>`,
     * only what is called on the name of the class, like `Box.create()` of its companion object, or of an extension on it,
     * which Kotlin resolves through the typealias, see [resolveMember], as the type arguments would not be substituted
     * in the members of the class,
     * or `null` if there is no such typealias, like one deprecated as hidden, or one of the [typeAliases] already followed.
     * Only found when the classpath is scanned, as nothing else tells the file facade class declaring it.
     */
    private fun resolveTypeAlias(name: String, memberName: String?, typeAliases: Set<String>): Resolved<*>? {
        if (name in typeAliases) return null
        val binaryName = typeAliasIndex[name] ?: return null
        val foundClass = findClassByName(binaryName) ?: return null
        val kmPackage = when (val metadata = foundClass.metadata) {
            is KotlinClassMetadata.FileFacade -> metadata.kmPackage
            is KotlinClassMetadata.MultiFileClassPart -> metadata.kmPackage
            else -> return null
        }
        val simpleName = name.substringAfterLast('.')
        val typeAlias = kmPackage.typeAliases.firstOrNull { it.name == simpleName && it.visibility == Visibility.PUBLIC }
            ?: return null
        val deprecation = typeAliasDeprecation(typeAlias)
        if (deprecation?.isHidden == true) return null
        val contextPackage = kotlinPackageOf(binaryName, foundClass.kotlinPackageName)
        val typeParameters = TypeParameterScope.EMPTY.withDeclared(typeAlias.typeParameters)
        val declaration = formatDeprecation(deprecation) + "typealias " + escapeIdentifier(typeAlias.name) +
            formatTypeParameters(typeAlias.typeParameters, typeParameters, contextPackage) + " = " +
            formatTypeAliasTarget(typeAlias, name, contextPackage, typeParameters)
        if (memberName != null) {
            if ('.' in memberName) return null
            val expanded = typeAlias.expandedType
            if (expanded.isNullable) return null
            val className = (expanded.classifier as? KmClassifier.Class)?.name ?: return null
            val member = resolve(
                "${escapeQualifiedName(className.replace('/', '.'))}.${escapeQualifiedName(memberName)}", typeAliases + name,
                aliasedClass = className, isAliasedWithTypeArguments = expanded.arguments.isNotEmpty()
            ) ?: return null
            return if (deprecation == null) member else member.withContext("// through $declaration")
        }
        return MemberListing.of(listOf(ListingItem.Declaration(listOf(declaration))))
    }

    /**
     * The type the [typeAlias] named [name] stands for, as declared, see [formatType],
     * but the class, or the typealias, it names qualified, when its simple name would read as another one,
     * like `kotlin.jvm.Throws` of `typealias Throws` in `kotlin`, which would read as itself,
     * or a typealias of another package, which is always written by its simple name.
     */
    private fun formatTypeAliasTarget(
        typeAlias: KmTypeAlias,
        name: String,
        contextPackage: String,
        typeParameters: TypeParameterScope
    ): String {
        val type = typeAlias.underlyingType
        val formatted = formatType(type, contextPackage, typeParameters)
        val (targetName, isTypeAlias) = when (val classifier = type.classifier) {
            is KmClassifier.Class -> classifier.name to false
            is KmClassifier.TypeAlias -> classifier.name to true
            is KmClassifier.TypeParameter -> return formatted
        }
        if (type.outerType != null) return formatted
        val qualifiedName = targetName.replace('/', '.')
        val simpleName = targetName.substringAfterLast('/')
        val targetPackage = targetName.substringBeforeLast('/', "").replace('/', '.')
        val isAmbiguous = (simpleName.substringBefore('.') == typeAlias.name && qualifiedName != name) ||
            (isTypeAlias && targetPackage != contextPackage)
        val written = escapeQualifiedName(simpleName)
        val rest = formatted.removePrefix(written)
        return if (isAmbiguous && rest.length < formatted.length && (rest.isEmpty() || rest[0] == '<' || rest[0] == '?')) {
            escapeQualifiedName(qualifiedName) + rest
        } else {
            formatted
        }
    }

    /**
     * The deprecation of the [typeAlias], told by the annotations of its metadata, as it has no JVM declaration of its own,
     * by the same rule as the one of the annotations read from the class files, see [deprecationOf].
     */
    private fun typeAliasDeprecation(typeAlias: KmTypeAlias): Deprecation? {
        val annotations = DeprecationAnnotations()
        fun KmAnnotation.string(name: String) = (arguments[name] as? KmAnnotationArgument.StringValue)?.value
        typeAlias.annotations.forEach { annotation ->
            when (annotation.className) {
                "kotlin/Deprecated" -> {
                    annotations.isDeprecated = true
                    annotations.message = annotation.string("message")
                    annotations.replaceWith = (annotation.arguments["replaceWith"] as? KmAnnotationArgument.AnnotationValue)
                        ?.annotation?.string("expression")
                    annotations.level = (annotation.arguments["level"] as? KmAnnotationArgument.EnumValue)?.enumEntryName
                }
                "kotlin/DeprecatedSinceKotlin" -> {
                    annotations.isDeprecatedSinceKotlin = true
                    annotations.warningSince = annotation.string("warningSince")
                    annotations.errorSince = annotation.string("errorSince")
                    annotations.hiddenSince = annotation.string("hiddenSince")
                }
            }
        }
        return annotations.deprecationOf(::isSince)
    }

    /**
     * The declarations of a class to render, see [formatClass], with the [Deprecations] of its declarations:
     * either a class found on the classpath, or a built-in type declared only in the `.kotlin_builtins` resources.
     */
    private interface ClassDeclarations : Deprecations {

        /**
         * The Kotlin declaration of the class, or `null` if it is not a Kotlin class, or its metadata cannot be read.
         */
        val kmClass: KmClass?

        /**
         * The JVM class, if loaded, telling its package, and the default values of the parameters of an annotation class.
         */
        val clazz: Class<*>?

        /**
         * The deprecation of the class itself.
         */
        val classDeprecation: Deprecation?

        /**
         * The scope of the class, see [ApiClasses], or `null` if it cannot be told.
         */
        val scope: ClassScope?

        /**
         * The name, by which it is found, of the class of the [metadataName] in the package of this class,
         * like this class itself, its nested classes, its companion object, its sealed subclasses, and its outer classes,
         * which is the located name, see [locatedNameOf], differing from the [metadataName]
         * when this class is relocated, like by shading, which does not rewrite its metadata.
         */
        fun located(metadataName: String): String

        /**
         * This class as the owner of the members checked for overrides, see [OverrideResolver.Owner],
         * by its located name, see [located], telling its relocation, if any,
         * or `null` if it is not a Kotlin class, or its metadata cannot be read.
         */
        val overrideOwner: OverrideResolver.Owner?

    }

    /**
     * A class of the classpath, by its JVM [binaryName], either loaded, or, when it cannot be loaded,
     * like when it extends a class missing from the classpath, only read from its class file.
     */
    private inner class FoundClass(
        val binaryName: String,
        override val clazz: Class<*>?,
        readFile: ClassFile? = null
    ) : ClassDeclarations {

        /**
         * The class file, read once, and only when needed: already when the class cannot be loaded,
         * otherwise for the flags telling whether a listed class is usable, see [isUsableClass],
         * and for the deprecations of the declarations, see [deprecation],
         * read again after a failure which might be transient, see [FinalOnce].
         */
        val classFile: ClassFile? get() = classFileRead.value

        private val classFileRead = FinalOnce {
            if (readFile != null) TransientFailures.Outcome(readFile, isFinal = true) else readClassFile(binaryName)
        }

        // the flags reflected once, and only when the class file cannot be read, see reflectedClassFile
        val reflectedClassFile: ClassFile by lazy { reflectedClassFile(clazz) }

        // the deprecations of the members reflected once, and only when needed, see reflectedMembersFile
        private val reflectedMembersFile: ClassFile by lazy { reflectedMembersFile(clazz) }

        // the ones of the DefaultImpls class of an interface, when its class file cannot be read either
        private val reflectedDefaultImplsFile: ClassFile by lazy {
            val defaultImpls = reflectedValue(default = null) {
                Class.forName("$binaryName\$$DEFAULT_IMPLS", false, clazz?.classLoader ?: classLoader)
            }
            reflectedMembersFile(defaultImpls)
        }

        /**
         * The deprecation of the class itself, read from its class file,
         * or reflected, when the class file cannot be read, see [reflectedClassFile].
         */
        override val classDeprecation: Deprecation? get() = (classFile ?: reflectedClassFile).classDeprecation

        private val metadataAnnotation: Metadata? by lazy {
            if (clazz != null) clazz.getAnnotation(Metadata::class.java) else classFile?.metadata
        }

        // parsed once, and only when needed, as some classes are loaded only for the Java reflection
        val metadata: KotlinClassMetadata? by lazy { metadataAnnotation?.let { parseMetadata(it) } }

        /**
         * The Kotlin package of a file facade, when it differs from its JVM package,
         * like `kotlin.collections` of `kotlin.collections.jdk8.CollectionsJDK8Kt`, compiled with `@JvmPackageName`,
         * or else an empty string, see [packageSourceOf].
         */
        val kotlinPackageName: String get() = metadataAnnotation?.packageName.orEmpty()

        override val kmClass: KmClass? get() = (metadata as? KotlinClassMetadata.Class)?.kmClass

        /**
         * The declarations of this class, if it is a loaded Java class, without Kotlin metadata,
         * as kotlin-reflect describes it, see [JavaClass], described once, and only when needed,
         * or `null` if kotlin-reflect cannot describe it.
         */
        val javaDeclarations: JavaClass? by lazy {
            clazz?.takeIf { metadataAnnotation == null }?.let { reflectedJavaClass(it) }
        }

        /**
         * The type parameters and the supertypes of this class, if it is a loaded Java class, without Kotlin metadata,
         * as kotlin-reflect describes them, see [toKmJavaClassHeader], without describing its members, unlike [javaDeclarations],
         * or `null` if kotlin-reflect cannot describe them.
         */
        val javaClassHeader: KmClass? by lazy {
            clazz?.takeIf { metadataAnnotation == null }?.let { clazz ->
                reflectedValue(default = null) {
                    // kotlin-reflect tells ? extends Object like ?, unlike the generic signature of the class file
                    val objectWildcards = classFile?.signature
                        ?.takeIf { OBJECT_WILDCARD in it }
                        ?.let(::objectWildcardsOf)
                        .orEmpty()
                    clazz.kotlin.toKmJavaClassHeader(
                        kotlinClassName(clazz),
                        objectWildcards = { supertype -> objectWildcards[supertype.name.replace('.', '/')].orEmpty() },
                        kmClassOf = ::kmClassOf,
                        supertypeName = ::javaSupertypeMetadataName
                    )
                }
            }
        }

        /**
         * The `DefaultImpls` class file of an interface, or of an annotation class, holding the synthetic methods
         * for the annotations of its properties, unless compiled with `-jvm-default=no-compatibility`,
         * read only when needed, and again after a failure which might be transient, see [FinalOnce].
         */
        private val defaultImplsFile: ClassFile? get() = defaultImplsFileRead.value

        private val defaultImplsFileRead = FinalOnce { readClassFile("$binaryName\$$DEFAULT_IMPLS") }

        // the deprecation of a declaration of this class, see ClassFile, reflected when the class file cannot be read,
        // see reflectedMembersFile, like the one of a hidden constructor, telling whether the class can be subclassed,
        // but the hidden one of an override of toString() or hashCode() of kotlin.Any, which the compiler does not report,
        // see isUnreportedHiddenAnyMember, not of a top-level function of the same signature, of a file facade

        override fun deprecation(function: KmFunction): Deprecation? =
            (classFile ?: reflectedMembersFile).deprecation(function)?.takeUnless {
                it.isHidden && function.isUnreportedHiddenAnyMember && kmClass != null
            }

        override fun deprecation(constructor: KmConstructor): Deprecation? =
            (classFile ?: reflectedMembersFile).deprecation(constructor)

        override fun deprecation(property: KmProperty): Deprecation? {
            val classFile = classFile ?: reflectedMembersFile
            // the synthetic method for the annotations of an interface property is rather in its DefaultImpls,
            // reflected as well when its class file cannot be read
            val annotationsFile = if (classFile.hasAnnotationsInDefaultImpls(property)) {
                defaultImplsFile ?: reflectedDefaultImplsFile
            } else {
                classFile
            }
            return classFile.deprecation(property, annotationsDeprecation = annotationsFile.annotationsDeprecation(property))
        }

        override fun deprecation(enumEntry: KmEnumEntry): Deprecation? =
            (classFile ?: reflectedMembersFile).deprecation(enumEntry)

        /**
         * The scope of the class, see [ApiClasses], where the usability of a class whose class file cannot be read
         * is told by the metadata and the reflection of the class, see [isUsableClass] and [reflectedClassFile],
         * told again after a failure to read the class file which might be transient, see [FinalOnce].
         */
        override val scope: ClassScope? get() = scopeRead.value

        private val scopeRead = FinalOnce {
            val classFile = classFileRead.outcome
            TransientFailures.Outcome(
                kmClass?.let { classScope(it, isUsableClass(it, classFile.value ?: reflectedClassFile), this, ::located) },
                classFile.isFinal
            )
        }

        override fun located(metadataName: String): String = locatedNameOf(binaryName, metadataName)

        // made once, and only when needed, as it is needed for each member rendered
        override val overrideOwner: OverrideResolver.Owner? by lazy { kmClass?.let { OverrideResolver.Owner(it, located(it.name)) } }

    }

    /**
     * The classes of the API, see [ApiClasses], found by the class loader, like the classes to resolve.
     */
    private val apiClasses = ApiClasses(
        scopeOf = { name -> classDeclarations(name)?.scope },
        isAbsent = { name -> isAbsent(name) },
        transientFailures = transientFailures
    )

    /**
     * Whether the class named like `com/example/Outer.Nested` is absent from the classpath:
     * neither found, see [findClassByMetadataName], nor its class file served, like when it is missing,
     * unlike a class found without readable metadata, or one which cannot be loaded, without readable metadata either,
     * or one whose class file cannot be read, maybe only for now, kept with the lookup, see [ClassLookup.isAbsent].
     */
    private fun isAbsent(name: String): Boolean = lookUpClassByBinaryName(binaryNameOf(name)).isAbsent

    /**
     * Whether the class file of the class of this JVM [binaryName], which is not found, is absent from the classpath,
     * not when it cannot be read, maybe only for now, see [readClassBytesOutcome],
     * nor when it is there, but cannot be opened, like by a class loader swallowing the `IOException` of opening it.
     */
    private fun classFileAbsence(binaryName: String): FinalOnce<Boolean> = FinalOnce {
        val bytes = readClassBytesOutcome(binaryName)
        TransientFailures.Outcome(bytes.value == null && bytes.isFinal && !classLoader.hasClassFile(binaryName), bytes.isFinal)
    }

    /**
     * A built-in type without a class file of its own, like `kotlin/Int`, or `kotlin/collections/List`,
     * or its companion object, like `kotlin/Int.Companion`, named [name],
     * declared in the `.kotlin_builtins` resources by the [kmClass],
     * and described by kotlin-reflect through its JVM class, the [kClass], like `java.lang.Integer`,
     * which tells the deprecations of its declarations, as kotlin-metadata-jvm does not read the annotations of the built-ins,
     * and its JVM-only members, like `String.chars()`, see [jvmOnlyFunctions].
     *
     * A mutable collection type has no [kClass], as kotlin-reflect describes the read-only counterpart,
     * sharing the same JVM type, instead, so that whether one of its declarations is deprecated cannot be told,
     * and none of them is, which `KotlinBuiltinsTest` guards,
     * while its JVM-only members, like `MutableList.replaceAll`, are reflected on a Java class implementing it,
     * see [mutableCollectionFunctions].
     * Neither the constructors, nor the class itself, are deprecated.
     */
    private inner class BuiltinClass(private val name: String, override val kmClass: KmClass) : ClassDeclarations {

        // reflected only when needed, see reflectedBuiltinClass
        private val kClass: KClass<*>? by lazy { reflectedBuiltinClass(name) }

        override val clazz: Class<*>? get() = null

        override val classDeprecation: Deprecation? get() = null

        override val scope: ClassScope by lazy { classScope(kmClass, isUsable = true, deprecations = this) }

        override fun located(metadataName: String): String = metadataName

        override val overrideOwner: OverrideResolver.Owner = OverrideResolver.Owner(kmClass)

        private val typeParameterNames = kmClass.typeParameters.namesById()

        /**
         * The functions declared by the [kClass], also the JVM-only ones, by their signatures, see [signatureOf],
         * or else the JVM-only ones of a mutable collection type, see [mutableCollectionFunctions],
         * converted to the Kotlin metadata, see [toKmFunction].
         */
        private val reflectedFunctions: Map<String, ReflectedFunction> by lazy {
            val typeParameters = ReflectedTypeParameters.classTypeParameters(kmClass.typeParameters)
            (kClass?.declaredMemberFunctions ?: mutableCollectionFunctions(name.replace('/', '.'), kmClass))
                // neither a synthetic method, nor an accessor of a JVM field
                .filter { '$' !in it.name }
                .map { function ->
                    val kmFunction = function.toKmFunction(typeParameters).also { it.modality = it.modality.finalIn(kmClass) }
                    ReflectedFunction(function, kmFunction)
                }
                .associateBy { signatureOf(it.kmFunction) }
        }

        /**
         * The properties declared by the [kClass], by their names, converted to the Kotlin metadata, see [toKmProperty].
         */
        private val reflectedProperties: Map<String, ReflectedProperty> by lazy {
            val typeParameters = ReflectedTypeParameters.classTypeParameters(kmClass.typeParameters)
            kClass?.declaredMemberProperties.orEmpty()
                .filter { '$' !in it.name }
                .associate { property ->
                    val kmProperty = property.toKmProperty(typeParameters).also { it.modality = it.modality.finalIn(kmClass) }
                    property.name to ReflectedProperty(property, kmProperty)
                }
        }

        /**
         * The functions of the JVM class only, not declared in the [kmClass], like `String.chars()`,
         * sorted by their signatures, as kotlin-reflect does not guarantee their order,
         * also the Java-only ones, like `Throwable.initCause(arg0: Throwable?)`, or `Throwable.fillInStackTrace()`,
         * which a built-in type described by kotlin-reflect leaves out of the [kmClass] too, see [toKmBuiltinClass],
         * as Kotlin calls them all the same, and never lets a call to a Java method name its arguments anyway,
         * listed and resolved alike, unless hidden by an extension, see [jvmOnlyFunctionsNotHidden],
         * but the ones Kotlin does not call, like `String.indent(Int)`, see [isUnsupportedByCompiler].
         */
        val jvmOnlyFunctions: List<KmFunction> by lazy {
            val declared = kmClass.functions.mapTo(HashSet()) { signatureOf(it) }
            reflectedFunctions.filterKeys { it !in declared }.toSortedMap().values
                .filter { !isUnsupportedByCompiler(it.kFunction) }
                .map { it.kmFunction }
        }

        /**
         * The properties of the JVM class only, not declared in the [kmClass], like the [jvmOnlyFunctions].
         */
        val jvmOnlyProperties: List<KmProperty> by lazy {
            val declared = kmClass.properties.mapTo(HashSet()) { it.name }
            reflectedProperties.filterKeys { it !in declared }.toSortedMap().values
                .filter { !isUnsupportedByCompiler(it.kProperty) }
                .map { it.kmProperty }
        }

        /**
         * The signature telling apart the overloads of a [function], like `plus(Int)`,
         * the same for its declaration in the [kmClass], and the one reflected from the [kClass].
         */
        private fun signatureOf(function: KmFunction): String = signatures.computeIfAbsent(function) {
            val receiver = function.receiverParameterType?.let {
                formatType(
                    it,
                    typeParameters = TypeParameterScope.ignoringDefinitelyNotNull(typeParameterNames + function.typeParameters.namesById())
                ) + "."
            }
            receiver.orEmpty() + overloadSignature(function, typeParameterNames, ignoringDefinitelyNotNull = true)
        }

        // the signatures, see signatureOf, computed once per function, like for each deprecation asked
        private val signatures = ConcurrentHashMap<KmFunction, String>()

        override fun deprecation(function: KmFunction): Deprecation? =
            reflectedFunctions[signatureOf(function)]?.let { builtinDeprecation(it.kFunction) }

        /**
         * The JVM method of the [function], of a JVM-only one telling its overrides, see [inheritedMembers],
         * of any other one telling the JVM types of its value parameters, see [isMergedJvmParameter].
         */
        fun jvmMethodOf(function: KmFunction): JvmMethod? = jvmMethods[signatureOf(function)]

        // the JVM methods of the reflected functions, by their signatures, see jvmMethodOf, reflected once per class
        private val jvmMethods: Map<String, JvmMethod?> by lazy {
            reflectedFunctions.mapValues { (_, function) -> jvmMethodOf(function.kFunction) }
        }

        override fun deprecation(constructor: KmConstructor): Deprecation? = null

        override fun deprecation(property: KmProperty): Deprecation? =
            reflectedProperties[property.name]?.let { builtinDeprecation(it.kProperty) }

    }

    /**
     * The functions of the JVM class of the mutable collection type of the [qualifiedName] only,
     * like `replaceAll` of `java.util.List` for `kotlin.collections.MutableList`,
     * not of its read-only counterpart, like `spliterator()`, which kotlin-reflect describes in place of the mutable type,
     * nor inherited from another JVM class, like `removeIf` of `java.util.Collection`, the one of `MutableCollection`,
     * nor mapped to a member which the [kmClass] of the mutable type declares, of the same name and erased parameter types,
     * see [erasedJavaClass], like `remove(key: K, value: V)` of `MutableMap`, which kotlin-reflect describes anew,
     * with the platform types,
     * reflected on a Java class implementing the mutable type, see [mutableCollectionImplementations],
     * declaring the type parameters of the same names, like `E` of `java.util.AbstractList<E>`.
     */
    private fun mutableCollectionFunctions(qualifiedName: String, kmClass: KmClass): List<KFunction<*>> {
        val implementation = mutableCollectionImplementations[qualifiedName] ?: return emptyList()
        val readOnly = builtinTypes[qualifiedName.replace(".Mutable", ".")] ?: return emptyList()
        val declared = kmClass.functions.groupBy({ it.name }) { function ->
            function.valueParameters.map { erasedJavaClass(it.type) }
        }
        // an erased type which cannot be told matches any
        fun isDeclared(method: java.lang.reflect.Method) = declared[method.name].orEmpty().any { parameterTypes ->
            parameterTypes.size == method.parameterCount &&
                parameterTypes.zip(method.parameterTypes).all { (declaredType, type) -> declaredType == null || declaredType == type }
        }
        return reflectedValue(default = emptyList()) {
            val readOnlyMethods = readOnly.declaredMemberFunctions.mapNotNullTo(HashSet()) {
                reflectedValue(default = null) { it.javaMethod }
            }
            implementation.memberFunctions.filter { function ->
                reflectedValue(default = false) {
                    val method = function.javaMethod
                    method != null && method.declaringClass == readOnly.java && method !in readOnlyMethods && !isDeclared(method)
                }
            }
        }
    }

    /**
     * The JVM class the [type] of a parameter of a member of a mutable collection type is erased to,
     * see [mutableCollectionFunctions]: `Object` of a type parameter, unbounded in these types,
     * the primitive one of a not nullable primitive type, like `int` of `Int`, the JVM class of another built-in type,
     * like `java.util.Collection` of `Collection<E>`, or `null`, if it cannot be told.
     */
    private fun erasedJavaClass(type: KmType): Class<*>? = when (val classifier = type.classifier) {
        is KmClassifier.TypeParameter -> Any::class.java
        is KmClassifier.Class -> builtinTypes[classifier.name.replace('/', '.')]?.let { kClass ->
            (if (type.isNullable) null else kClass.javaPrimitiveType) ?: kClass.java
        }
        is KmClassifier.TypeAlias -> null
    }

    /**
     * A function reflected by kotlin-reflect, as the [kmFunction] of the Kotlin metadata.
     */
    private class ReflectedFunction(val kFunction: KFunction<*>, val kmFunction: KmFunction)

    /**
     * A property reflected by kotlin-reflect, as the [kmProperty] of the Kotlin metadata.
     */
    private class ReflectedProperty(val kProperty: KProperty<*>, val kmProperty: KmProperty)

    /**
     * A Java class, the [clazz], declared by the [kmClass] as kotlin-reflect describes it, see [toKmJavaClassHeader],
     * with the members it declares, the [functions] and the [properties], by their declarations in the [kmClass],
     * so that its members are inherited like the ones of a Kotlin supertype, see [inheritedMembers],
     * and told deprecated like the members of a built-in type, see [builtinDeprecation].
     * It is not a class of the API, as a Java class is not rendered.
     */
    private inner class JavaClass(
        override val clazz: Class<*>,
        override val kmClass: KmClass,
        private val functions: Map<KmFunction, JavaFunction>,
        private val properties: Map<KmProperty, JavaProperty>
    ) : ClassDeclarations {

        override val classDeprecation: Deprecation? get() = null

        override val scope: ClassScope? get() = null

        // described by kotlin-reflect of the loaded class, by its very name, never relocated
        override fun located(metadataName: String): String = metadataName

        override val overrideOwner: OverrideResolver.Owner = OverrideResolver.Owner(kmClass)

        override fun deprecation(function: KmFunction): Deprecation? =
            functions[function]?.let { javaFunction ->
                javaFunction.kFunction?.let { builtinDeprecation(it) } ?: javaDeprecation { javaFunction.method }
            }

        override fun deprecation(constructor: KmConstructor): Deprecation? = null

        override fun deprecation(property: KmProperty): Deprecation? =
            properties[property]?.let { javaProperty ->
                javaProperty.kProperty?.let { builtinDeprecation(it) } ?: javaDeprecation { javaProperty.field }
            }

        /**
         * The keys of the members of the Kotlin supertypes which the [member] of this class overrides,
         * see [OverrideResolver.overriddenMembers], regardless of the nullability of the platform types of Java,
         * like `E!`, of its value parameters, but by the primitive types of its JVM method, see [jvmDescriptorOf].
         */
        fun overriddenMembers(member: Member): Set<String> =
            member.overridden(overrideResolver, overrideOwner, (member as? Member.Function)?.let { jvmDescriptorOf(it.declaration) })

        /**
         * The JVM descriptor of the Java method of the [function], telling the overrides of the function by its primitive types,
         * see [OverrideResolver.overriddenMembers], or `null` if it cannot be reflected.
         */
        fun jvmDescriptorOf(function: KmFunction): String? = jvmMethodOf(function)?.signature?.descriptor

        /**
         * The JVM method of the [function], telling its overrides, see [inheritedMembers].
         */
        fun jvmMethodOf(function: KmFunction): JvmMethod? = functions[function]?.jvmMethod

        /**
         * Whether the [function] returns a platform type of Java, like the `V!` of `call()` of `java.util.concurrent.Callable`,
         * unlike the ones which Kotlin enhances, like the `T` of `get()` of `java.util.function.Supplier`, see [ENHANCED_RETURN_JDK_METHODS],
         * unless the enhancement [isEnhancementLost], see [InheritedCandidate.isEnhancementLost].
         */
        fun returnsPlatformType(function: KmFunction, isEnhancementLost: Boolean = false): Boolean =
            reflectedValue(default = false) {
                functions[function]?.let { javaFunction ->
                    javaFunction.kFunction?.returnsPlatformType(isEnhancementLost)
                        ?: javaFunction.method?.returnsPlatformType(isEnhancementLost)
                } == true
            }

        /**
         * The Java method of the [function], or `null` if it cannot be reflected.
         */
        fun javaMethodOf(function: KmFunction): Method? =
            functions[function]?.method

        /**
         * The Java getter of the [property], like `size()` of the `size` of a `java.util.HashSet`,
         * or `null` if it is a field, or it cannot be reflected.
         */
        fun javaGetterOf(property: KmProperty): Method? =
            properties[property]?.kProperty?.let { reflectedValue(default = null) { it.javaGetter } }

        /**
         * The metadata names of the raw supertypes of the class, of type parameters, but given no type arguments,
         * like `com/example/Base` of `class Mid extends Base` of a `Base<T>`, whose members the compiler sees erased,
         * as the ones of their own supertypes, see [erasedFunctionOf].
         */
        val rawSupertypeNames: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            reflectedValue(default = emptySet()) {
                (listOfNotNull(clazz.genericSuperclass) + clazz.genericInterfaces)
                    .filterIsInstance<Class<*>>()
                    .filter { it.typeParameters.isNotEmpty() }
                    .mapTo(HashSet()) { javaSupertypeMetadataName(it) }
            }
        }

        /**
         * The [function] as the compiler sees it on a class reaching this one through a raw supertype, see [rawSupertypeNames],
         * of the erasures of its types, see [toKmJavaFunction], of its own modality and visibility,
         * and of the nullability of its own types, like the `V?` of an override of the `get` of `java.util.HashMap`, erased to `Any?`,
         * or `null` if it is no method of this class, like a member of a built-in type described by kotlin-reflect,
         * or a method of a JDK class, which stays matched with the members of the built-in types it overrides,
         * like the `compute` of a raw `java.util.HashMap` with the one of `MutableMap`, see [inheritedCandidates].
         */
        fun erasedFunctionOf(function: KmFunction): KmFunction? = reflectedValue(default = null) {
            if (clazz.isJdkClass) return@reflectedValue null
            val method = functions[function]?.method?.takeIf { it.declaringClass == clazz } ?: return@reflectedValue null
            val typeParameters = ReflectedTypeParameters(clazz.typeParameters.map { it.name }.zip(kmClass.typeParameters.map { it.id }).toMap())
            method.toKmJavaFunction(typeParameters, isFinalClass = false, isErased = true).also { erased ->
                erased.modality = function.modality
                erased.visibility = function.visibility
                erased.returnType.isNullable = function.returnType.isNullable
                erased.valueParameters.zip(function.valueParameters) { erasedParameter, parameter ->
                    erasedParameter.type.isNullable = parameter.type.isNullable
                    parameter.varargElementType?.let { erasedParameter.varargElementType?.isNullable = it.isNullable }
                }
            }
        }

    }

    /**
     * A function of a Java class, see [JavaClass], reflected by kotlin-reflect as the [kFunction],
     * or converted from the Java [method], see [toKmJavaFunction], when kotlin-reflect cannot describe it,
     * whose value parameters of the platform types of Java, like `E!`, accepting `null`, are nullable, see [toKmFunction],
     * while a Kotlin override declares them either way, like `equals(other: Any?)`, or `add(element: E)`,
     * so that its overrides are matched regardless of their nullability, see [JavaClass.overriddenMembers].
     */
    private class JavaFunction(val kFunction: KFunction<*>?, private val javaMethod: Method?) {

        /**
         * The Java method of the function, the one it is converted from, see [toKmJavaFunction],
         * or the one of the [kFunction], or `null` if it cannot be reflected.
         */
        val method: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
            javaMethod ?: reflectedValue(default = null) { kFunction?.javaMethod }
        }

        /**
         * The JVM method of the function, see [JavaClass.jvmMethodOf], told once, as asked for by each of its overrides.
         */
        val jvmMethod: JvmMethod? by lazy(LazyThreadSafetyMode.PUBLICATION) { JvmMethod.of(method) }

    }

    /**
     * A property of a Java class, see [JavaClass], reflected by kotlin-reflect as the [kProperty],
     * or converted from the Java [field], see [toKmJavaProperty].
     */
    private class JavaProperty(val kProperty: KProperty<*>?, val field: Field?)

    /**
     * The method overriding the Java [method] in a JVM class between the [inheriting] class, if loaded, and the class declaring it,
     * or `null`: one of its superclasses declaring a method of the same name and parameter types,
     * which kotlin-reflect might describe as another member, like the `size()` of `java.util.Hashtable`,
     * seen in Kotlin as the `size` of `Map`, overriding the abstract one of `java.util.Dictionary`,
     * but the one of a class which does not implement the interface declaring the [method] only merges with it
     * in a Kotlin subclass implementing both, like the `size()` of `java.util.HashSet` with the one of an unrelated Java interface,
     * which stays abstract in Kotlin, unlike in a Java subclass implementing both,
     * unless the method of the interface returns a type of the nullability its annotations tell, see [javaNullabilityOf],
     * which K2 picks of the merged ones, like the `@Nullable String get()` of an interface over the `String get()` of a superclass,
     * or, of a public [method], the one resolved on the [inheriting] class being declared by a subtype of its class,
     * as a JVM class, whose superclasses can be told, tells it, unlike the Kotlin view of its methods.
     * Neither a bridge, like the one javac generates in a public class for a public method of its package-private superclass,
     * nor a private, or a static, method of the same signature overrides it.
     */
    private fun overridingBelow(method: Method, inheriting: Class<*>?): Method? = reflectedValue(default = null) {
        if (inheriting == null) return@reflectedValue null
        val declaring = method.declaringClass
        val parameterTypes = method.parameterTypes
        fun Method.overrides() = !isBridge && !Modifier.isPrivate(modifiers) && !Modifier.isStatic(modifiers) &&
            name == method.name && this.parameterTypes.contentEquals(parameterTypes)
        // the method of a superclass not implementing the interface declaring the method is merged with it, not overriding it,
        // by a Kotlin class, implementing both, but implements it in a Java class between them, implementing the interface,
        // unless the method of the interface returns a type its nullability annotations tell, which K2 picks among the merged ones
        var isImplementedByJava = false
        val isMerged = lazy(LazyThreadSafetyMode.NONE) { !method.returnType.isPrimitive && javaNullabilityOf(method)?.returnType != null }
        generateSequence(inheriting.superclass) { it.superclass }.takeWhile { it != declaring }.firstNotNullOfOrNull { superclass ->
            val isImplementing = declaring.isAssignableFrom(superclass)
            if (isImplementing && !superclass.isAnnotationPresent(Metadata::class.java)) isImplementedByJava = true
            superclass.takeIf { isImplementing || (isImplementedByJava && !isMerged.value) }
                ?.declaredMethods?.firstOrNull { it.overrides() }
        } ?: if (!Modifier.isPublic(method.modifiers)) null else inheriting.getMethod(method.name, *parameterTypes).takeIf {
            it.overrides() && it.declaringClass != declaring && declaring.isAssignableFrom(it.declaringClass)
        }
    }

    /**
     * The modality of the getter of the nearest JVM superclass of the [inheriting] class, if loaded, below the [declaring] one,
     * implementing the abstract Kotlin [property], if it is declared by a Java class, like `getName()` of `val name: String`,
     * which Kotlin sees as the property, or `null`, also when a Kotlin class declares it first, overriding the property itself.
     * Its return type is not compared, as a Java getter might return a more specific type, javac bridging the one of the property.
     */
    private fun javaGetterModality(property: KmProperty, declaring: Class<*>?, inheriting: Class<*>?): Modality? =
        reflectedValue(default = null) {
            val getter = property.getterSignature ?: return@reflectedValue null
            val parameters = getter.descriptor.substringBefore(')') + ')'
            generateSequence(inheriting?.superclass) { it.superclass }.takeWhile { it != declaring }.firstNotNullOfOrNull { superclass ->
                superclass.declaredMethods.firstOrNull { method ->
                    !method.isBridge && !Modifier.isPrivate(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                        method.name == getter.name && AsmType.getMethodDescriptor(method).startsWith(parameters)
                }?.let { superclass to it }
            }?.let { (superclass, method) ->
                when {
                    superclass.isAnnotationPresent(Metadata::class.java) -> null
                    Modifier.isAbstract(method.modifiers) -> Modality.ABSTRACT
                    Modifier.isFinal(method.modifiers) -> Modality.FINAL
                    else -> Modality.OPEN
                }
            }
        }

    /**
     * The one of the [unlistedCandidates], which are never listed, whose JVM method is the [method], or `null`:
     * a hidden one, see [InheritedCandidate.isHidden], which hides the Java method it overrides only when the compiler picks it,
     * or a visible one of a supertype which is not part of the API, see [InheritedCandidate.isApi], which the Java method stands for,
     * see [inheritedCandidates], unlike the override of a JVM class, see [overridingBelow].
     */
    private fun unlistedOverride(method: Method, unlistedCandidates: List<InheritedCandidate>): InheritedCandidate? =
        reflectedValue(default = null) {
            val signature = JvmMethodSignature(method.name, AsmType.getMethodDescriptor(method))
            unlistedCandidates.firstOrNull { candidate ->
                candidate.declarations.clazz == method.declaringClass &&
                    (candidate.member as? Member.Function)?.declaration?.signature == signature
            }
        }

    /**
     * The JVM method of the [kFunction], see [JvmMethod], telling its overrides, see [inheritedMembers].
     */
    private fun jvmMethodOf(kFunction: KFunction<*>?): JvmMethod? = jvmMethodOf(reflectedValue(default = null) { kFunction?.javaMethod })

    /**
     * The JVM method of the Java [method], see [JvmMethod].
     */
    private fun jvmMethodOf(method: Method?): JvmMethod? = JvmMethod.of(method)

    // whether kotlin-reflect describes the Java class as a built-in type, like java.util.ListIterator as ListIterator
    private fun isDescribedAsBuiltin(clazz: Class<*>): Boolean =
        reflectedValue(default = false) { clazz.kotlin.qualifiedName != clazz.canonicalName }

    /**
     * The [JavaClass] of the Java [clazz], with its members, which Kotlin calls, see [isUnsupportedByCompiler],
     * each described on its own, not to lose the others when one of them cannot be,
     * like when its signature refers to a class missing from the classpath,
     * or `null` if kotlin-reflect cannot describe the class itself.
     * When kotlin-reflect fails on all of its members at once, like on the ones of `java.util.LinkedHashSet` since JDK 21,
     * on its inherited `clone()`, they are converted from the Java reflection, see [javaMembersOf],
     * and so are the members of a Java class which kotlin-reflect describes as a built-in type,
     * like `java.util.ListIterator` as `ListIterator`, named as a supertype, whose own members Kotlin sees then,
     * which the built-in type does not tell, like `set(E)`, see [javaMembersOf],
     * or all of them, instead of the ones of the built-in type, see [JAVA_VIEWED_BUILTIN_INTERFACES].
     */
    private fun reflectedJavaClass(clazz: Class<*>): JavaClass? = reflectedValue(default = null) {
        val kClass = clazz.kotlin
        val isBuiltin = isDescribedAsBuiltin(clazz)
        val isJavaViewed = isBuiltin && clazz.name in JAVA_VIEWED_BUILTIN_INTERFACES
        val kmClass = kClass.toKmJavaClassHeader(kotlinClassName(clazz), kmClassOf = ::kmClassOf, supertypeName = ::javaSupertypeMetadataName)
        // the type parameters of the outer classes of an inner class, which its members might refer to, like T of Outer<T>.Inner
        val outerTypeParameterNames = clazz.outerTypeParameterNames()
        val typeParameters = ReflectedTypeParameters.javaClassTypeParameters(outerTypeParameterNames, kmClass.typeParameters)
        // kotlin-reflect tells no visibility of a protected Java member, which is also visible within its package
        fun Visibility.orProtected(modifiers: Int?): Visibility =
            if (this == Visibility.LOCAL && modifiers != null && Modifier.isProtected(modifiers)) Visibility.PROTECTED else this
        val functions = IdentityHashMap<KmFunction, JavaFunction>()
        // the overloads ordered by their numbers of parameters, and their JVM descriptors,
        // as kotlin-reflect tells them in no particular order
        val reflectedFunctions = if (isJavaViewed) emptyList() else reflectedValue(default = null) { kClass.declaredMemberFunctions }
        reflectedFunctions.orEmpty()
            .map { function ->
                val arity = reflectedValue(default = 0) { function.parameters.size }
                val descriptor = reflectedValue(default = "") { function.javaMethod?.let(AsmType::getMethodDescriptor).orEmpty() }
                Triple(function, arity, descriptor)
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .forEach { (function) ->
                reflectedValue(default = null) {
                    function.takeIf { '$' !in it.name && !isUnsupportedByCompiler(it) }
                        ?.toKmFunction(typeParameters)
                        ?.also {
                            it.modality = it.modality.finalIn(kmClass)
                            it.visibility = it.visibility.orProtected(function.javaMethod?.modifiers)
                        }
                }?.let { kmFunction ->
                    kmClass.functions += kmFunction
                    functions[kmFunction] = JavaFunction(function, javaMethod = null)
                }
            }
        val properties = IdentityHashMap<KmProperty, JavaProperty>()
        val reflectedProperties = if (isJavaViewed) emptyList() else reflectedValue(default = null) { kClass.declaredMemberProperties }
        reflectedProperties.orEmpty().forEach { property ->
            reflectedValue(default = null) {
                property.takeIf { '$' !in it.name && !isUnsupportedByCompiler(it) }
                    ?.toKmProperty(typeParameters)
                    ?.also {
                        it.modality = it.modality.finalIn(kmClass)
                        val modifiers = property.javaField?.modifiers
                        it.visibility = it.visibility.orProtected(modifiers)
                        it.setter?.let { setter -> setter.visibility = setter.visibility.orProtected(modifiers) }
                    }
            }?.let { kmProperty ->
                kmClass.properties += kmProperty
                properties[kmProperty] = JavaProperty(property, field = null)
            }
        }
        // the type variables of the Java class by their positions, named otherwise by the built-in type, like E of T
        val javaTypeParameters by lazy(LazyThreadSafetyMode.NONE) {
            ReflectedTypeParameters(
                outerTypeParameterNames.withIndex().associate { (id, name) -> name to id } +
                    clazz.typeParameters.map { it.name }.zip(kmClass.typeParameters.map { it.id }).toMap()
            )
        }
        // the ones Kotlin hides of the built-in type are seen of the Java interface, like isEmpty() of java.lang.CharSequence
        val javaMembers by lazy(LazyThreadSafetyMode.NONE) { javaMembersOf(clazz, isKeepingHidden = isJavaViewed) }
        if (reflectedFunctions == null || isBuiltin) {
            // the names of the members the built-in type tells, like the hasNext() of ListIterator, or the size of java.util.List,
            // which Kotlin sees instead of its size() then, while the getters of the properties are seen too, like getKey() of key
            val described = if (!isBuiltin) emptySet() else buildSet {
                reflectedFunctions.orEmpty().mapTo(this) { it.name }
                reflectedProperties.orEmpty().mapTo(this) { it.name }
            }
            javaMembers.methods.filter { it.name !in described }.forEach { method ->
                reflectedValue(default = null) {
                    method.toKmJavaFunction(javaTypeParameters, isFinalClass = kmClass.modality == Modality.FINAL)
                        .also { it.visibility = it.visibility.orProtected(method.modifiers) }
                }?.let { kmFunction ->
                    kmClass.functions += kmFunction
                    functions[kmFunction] = JavaFunction(kFunction = null, method)
                }
            }
        }
        if (reflectedProperties == null) {
            javaMembers.fields.forEach { field ->
                reflectedValue(default = null) {
                    field.toKmJavaProperty(javaTypeParameters).also { property ->
                        property.visibility = property.visibility.orProtected(field.modifiers)
                        property.setter?.let { it.visibility = property.visibility }
                    }
                }?.let { kmProperty ->
                    kmClass.properties += kmProperty
                    properties[kmProperty] = JavaProperty(kProperty = null, field)
                }
            }
        }
        JavaClass(clazz, kmClass, functions, properties)
    }

    /**
     * The Java interfaces described as built-in types by kotlin-reflect, see [reflectedJavaClass],
     * which Kotlin sees as Java ones, when named as a supertype, like `size()` of `java.util.Collection`,
     * with none of the members of their built-in types, like the `size` of `Collection`,
     * unlike `java.util.List`, and `java.util.Set`, of the `size` of their built-in types, but no `size()`,
     * as the compiler tells, see `CLAUDE.md`.
     */
    private val JAVA_VIEWED_BUILTIN_INTERFACES = setOf("java.util.Collection", "java.util.Map", "java.lang.CharSequence")

    /**
     * The members of a Java class converted from the Java reflection, see [reflectedJavaClass],
     * the [methods], see [toKmJavaFunction], and the [fields], see [toKmJavaProperty].
     */
    private class JavaMembers(val methods: List<Method>, val fields: List<Field>)

    /**
     * The members of the Java [clazz] which kotlin-reflect would describe, see [JavaMembers], each read on its own:
     * its instance methods, but the synthetic ones, like the bridges, and the ones which Kotlin hides, see [HIDDEN_JDK_METHODS],
     * unless [isKeepingHidden],
     * and the ones overriding a method of a Java class described as a built-in type, see [overridesBuiltinJavaMethod],
     * like the `contains(Object)` of `java.util.Set`, whose own declarations in the built-in types stand for them,
     * like `contains(element: E)` of `MutableSet`, which Kotlin calls instead, ordered like the ones of kotlin-reflect,
     * by their numbers of parameters, and their JVM descriptors, and its instance fields, but the synthetic ones.
     */
    private fun javaMembersOf(clazz: Class<*>, isKeepingHidden: Boolean = false): JavaMembers {
        val methods = reflectedValue(default = emptyList()) { clazz.declaredMethods.toList() }.filter { method ->
            reflectedValue(default = false) {
                !method.isSynthetic && !Modifier.isStatic(method.modifiers) && '$' !in method.name &&
                    (isKeepingHidden || jvmSignature(method) !in HIDDEN_JDK_METHODS) && !overridesBuiltinJavaMethod(method)
            }
        }.sortedWith(compareBy({ it.parameterCount }, { AsmType.getMethodDescriptor(it) }))
        val fields = reflectedValue(default = emptyList()) { clazz.declaredFields.toList() }.filter { field ->
            !field.isSynthetic && !Modifier.isStatic(field.modifiers) && '$' !in field.name
        }
        return JavaMembers(methods, fields)
    }

    /**
     * The built-in types without a class file of their own, by their Kotlin class names, see [BuiltinClass].
     */
    private val builtinClassDeclarations: Map<String, BuiltinClass> by lazy {
        if (builtinClasses.isEmpty()) {
            logger.warn {
                "Cannot read the declarations of the Kotlin built-in types, they are described by kotlin-reflect instead, " +
                    "approximately, without the mutable collection types"
            }
        }
        builtinClasses.mapValues { (name, kmClass) -> BuiltinClass(name, kmClass) }
    }

    /**
     * The built-in types without a class file of their own, by their Kotlin class names, see [BuiltinClass],
     * described by kotlin-reflect when their declarations in the `.kotlin_builtins` resources cannot be read,
     * see [toKmBuiltinClass], like when the standard library is a named module, whose resources are encapsulated,
     * except for the mutable collection types, which kotlin-reflect describes as their read-only counterparts.
     */
    private val reflectedBuiltinClassDeclarations: Map<String, Lazy<BuiltinClass?>> by lazy {
        builtinTypes.filterKeys { it !in mutableBuiltinTypes }.entries.associate { (qualifiedName, kClass) ->
            val name = kotlinClassName(qualifiedName)
            name to lazy { reflectedValue(default = null) { BuiltinClass(name, kClass.toKmBuiltinClass(name)) } }
        }
    }

    /**
     * The JVM class of the built-in type named like `kotlin/Int`, or of its companion object, like `kotlin/Int.Companion`,
     * as described by kotlin-reflect, or `null` if it is a mutable collection type,
     * or its companion object cannot be reflected.
     */
    private fun reflectedBuiltinClass(name: String): KClass<*>? {
        val qualifiedName = name.replace('/', '.')
        if (qualifiedName in mutableBuiltinTypes) return null
        builtinTypes[qualifiedName]?.let { return it }
        if (!isBuiltinCompanion(qualifiedName)) return null
        return reflectedValue(default = null) { builtinTypes[qualifiedName.substringBeforeLast('.')]?.companionObject }
    }

    /**
     * Whether the class of the fully qualified [name] is the companion object of a built-in type, like `kotlin.Int.Companion`.
     */
    private fun isBuiltinCompanion(name: String): Boolean {
        val outerName = name.substringBeforeLast('.')
        if (outerName !in builtinTypes) return false
        val outer = classDeclarations(kotlinClassName(outerName))?.kmClass ?: return false
        return outer.companionObject == name.substringAfterLast('.')
    }

    /**
     * The declarations of the class named like `kotlin/collections/Map.Entry`:
     * the class found on the classpath, see [findClassByMetadataName], if it is a Kotlin class,
     * like `kotlin/Unit`, or `kotlin/UInt`, whose metadata tells more than its declaration in the `.kotlin_builtins` resources,
     * or else the built-in type without a class file of its own, see [BuiltinClass],
     * declared in the `.kotlin_builtins` resources, or else described by kotlin-reflect,
     * see [reflectedBuiltinClassDeclarations], or else the class found, if any, like a Java class.
     */
    private fun classDeclarations(name: String): ClassDeclarations? {
        val foundClass = findClassByMetadataName(name)
        if (foundClass?.kmClass != null) return foundClass
        return builtinClassDeclarations[name] ?: reflectedBuiltinClassDeclarations[name]?.value ?: foundClass
    }

    /**
     * The flags of a class whose class file cannot be read, like one an in-memory class loader does not serve,
     * reflected from the [clazz], if loaded: [Class.isSynthetic], reporting the flags of the `InnerClasses` attribute
     * for a nested class, and its deprecation, see [reflectedDeprecationOf],
     * but not the deprecations of its members, see [reflectedMembersFile], never failing the resolve.
     */
    private fun reflectedClassFile(clazz: Class<*>?): ClassFile = ClassFile(
        metadata = null,
        isSynthetic = clazz?.isSynthetic == true,
        classDeprecation = clazz?.let { reflectedDeprecationOf(it) }
    )

    /**
     * The deprecations of the methods and constructors of a class whose class file cannot be read, see [reflectedClassFile],
     * reflected from the [clazz], if loaded, by their JVM signatures, like the ones read from a class file,
     * see [ClassFile.deprecatedMethods], each member reflected on its own,
     * while none of the methods, or of the constructors, is, when a type of any of them cannot be loaded,
     * as the reflection loads the types of all of them at once, also to look one up by its parameter types,
     * and, of an interface, the synthetic methods for the annotations of its properties, which it declares itself,
     * see [ClassFile.hasAnnotationsInDefaultImpls], also the deprecations of the entries of an enum class,
     * by the names of their fields, see [ClassFile.deprecatedEnumEntries].
     */
    private fun reflectedMembersFile(clazz: Class<*>?): ClassFile {
        if (clazz == null) return ClassFile(metadata = null, isSynthetic = false)
        val methods = reflectedValue(default = emptyList()) { clazz.declaredMethods.toList() }.map { method ->
            method to { method.name + AsmType.getMethodDescriptor(method) }
        }
        val constructors = reflectedValue(default = emptyList()) { clazz.declaredConstructors.toList() }.map {
            constructor -> constructor to { "<init>" + AsmType.getConstructorDescriptor(constructor) }
        }
        return ClassFile(
            metadata = null,
            isSynthetic = clazz.isSynthetic,
            isInterface = clazz.isInterface,
            deprecatedMethods = (methods + constructors).mapNotNull { (member, signature) ->
                reflectedValue(default = null) { reflectedDeprecationOf(member)?.let { signature() to it } }
            }.toMap(),
            annotationsMethods = if (clazz.isInterface) {
                methods.mapNotNullTo(HashSet()) { (method, signature) ->
                    if (method.name.endsWith(ANNOTATIONS_METHOD_SUFFIX)) reflectedValue(default = null) { signature() } else null
                }
            } else emptySet(),
            deprecatedEnumEntries = if (!clazz.isEnum) emptyMap() else {
                reflectedValue(default = emptyList()) { clazz.declaredFields.filter { it.isEnumConstant } }.mapNotNull { field ->
                    reflectedValue(default = null) { reflectedDeprecationOf(field)?.let { field.name to it } }
                }.toMap()
            }
        )
    }

    /**
     * The deprecation of a class, or of its member, reflected, see [reflectedDeprecation],
     * each annotation read on its own.
     */
    private fun reflectedDeprecationOf(element: AnnotatedElement): Deprecation? = reflectedDeprecation(
        listOfNotNull(
            reflectedValue(default = null) { element.getAnnotation(Deprecated::class.java) },
            reflectedValue(default = null) { element.getAnnotation(DeprecatedSinceKotlin::class.java) }
        )
    )

    /**
     * The lookups of the recently found classes by their binary names, also of the ones not found,
     * so that neither a class is loaded, nor its metadata parsed, nor a missing class looked up, more than once,
     * like for every nested class listed on every page of a paged resolve,
     * except for a lookup by a name given to resolve, see [lookUpClassByName], which is not shared with a concurrent lookup
     * of the same class by its binary name, so that, until either is cached, the class might be loaded by each of them.
     * It is bounded, as a found class keeps its parsed metadata, so that a long-lived introspector
     * does not keep the metadata of every class ever looked up,
     * while the [overrideResolver] keeps the declarations it needs in its own bounded caches.
     * The outcome of a lookup which might be transient is not cached, see [lookUpClass],
     * nor a miss of a lookup by a name given to resolve, see [lookUpClassByName].
     */
    private val foundClasses = BoundedCache<String, ClassLookup>(capacity = 1024)

    /**
     * The lookups by a name given to resolve in progress, see [lookUpClassByName], each removed once computed,
     * so that the concurrent lookups of the same name share one, rather than each loading the class.
     */
    private val classLookupsByName = ConcurrentHashMap<String, Lazy<ClassLookup>>()

    /**
     * Finds a class by its binary name, like `com.example.Outer${'$'}Nested`, cached also when it is not found.
     */
    private fun findClassByBinaryName(binaryName: String): FoundClass? = lookUpClassByBinaryName(binaryName).foundClass

    /**
     * The lookup of a class by its binary name, see [findClassByBinaryName],
     * shared with the concurrent lookups of the same class by its binary name, never with the ones by name,
     * see [lookUpClassByName], while counted as the same failing attempt as all of them, see [attemptLookUpClass].
     */
    private fun lookUpClassByBinaryName(binaryName: String): ClassLookup = foundClasses.getOrCompute(binaryName) {
        val lookup = attemptLookUpClass(binaryName, isMissProbed = true)
        BoundedCache.Computed(lookup, isCacheable = lookup.isFinal)
    }.value.markedUnlessFinal()

    /**
     * The lookup of a class by a binary name which is not known to be exact, like one given to resolve,
     * taken from the cache, if any, see [foundClasses], or else looked up outside of it,
     * shared only with the concurrent lookups by the same name, see [classLookupsByName],
     * so that a lookup whose miss is cached never receives a miss which is not probed, telling nothing.
     * A final outcome is cached, unless it is such a miss, as such names come from the user input:
     * a class found over any lookup cached meanwhile, as a class once found stays loadable,
     * and a miss which is probed, like of a class failing to load, see [lookUpClass], only if none is,
     * as it might be older than the one cached meanwhile, which is taken instead, if final,
     * while a transient outcome of a concurrent lookup by binary name, which is not cached, gives way to this final miss.
     */
    private fun lookUpClassByName(binaryName: String): ClassLookup {
        foundClasses.getIfCached(binaryName)?.let { return it }
        val inProgress = lazy {
            val lookup = attemptLookUpClass(binaryName, isMissProbed = false)
            when {
                !lookup.isFinal || !lookup.isMissProbed -> lookup
                lookup.foundClass != null -> lookup.also { foundClasses.put(binaryName, it) }
                else -> foundClasses.getOrCompute(binaryName) { BoundedCache.Computed(lookup, isCacheable = true) }
                    .value.takeIf { it.isFinal } ?: lookup
            }
        }
        val shared = classLookupsByName.putIfAbsent(binaryName, inProgress) ?: inProgress
        return try {
            shared.value
        } finally {
            classLookupsByName.remove(binaryName, shared)
        }.markedUnlessFinal()
    }

    /**
     * The [lookUpClass] of the class, as the attempt of the class, see [TransientFailures.attempt],
     * of which a miss which is not probed tells nothing, see [TransientFailures.Outcome.isDecisive].
     */
    private fun attemptLookUpClass(binaryName: String, isMissProbed: Boolean): ClassLookup {
        val outcome = transientFailures.attempt("class $binaryName") {
            val lookup = lookUpClass(binaryName, isMissProbed)
            TransientFailures.Outcome(lookup, lookup.isFinal, isDecisive = lookup.isMissProbed)
        }
        return if (outcome.isFinal) outcome.value.asFinal() else outcome.value
    }

    private fun ClassLookup.markedUnlessFinal(): ClassLookup = also { if (!isFinal) transientFailures.mark() }

    /**
     * Finds a class by its metadata name, like `com/example/Outer.Nested`.
     */
    private fun findClassByMetadataName(name: String): FoundClass? = findClassByBinaryName(binaryNameOf(name))

    /**
     * Finds a class by its fully qualified name as given to [resolve], where nested classes are separated
     * either with `.`, or with the `$` of the JVM binary name, trying every possible binary name.
     * The classes found are cached, but not the names which are not found, as they come from the user input.
     */
    private fun findClassByName(name: String): FoundClass? {
        var binaryName = name
        while (true) {
            lookUpClassByName(binaryName).foundClass?.let { return it }
            val lastDot = binaryName.lastIndexOf('.')
            if (lastDot < 0) return null
            binaryName = binaryName.substring(0, lastDot) + "$" + binaryName.substring(lastDot + 1)
        }
    }

    /**
     * @param isFinal whether the outcome is final, unlike after a failure which might be transient.
     * @param unloadableClassBytes the class file of a Java class which cannot be loaded, if read,
     *   kept for the [overrideResolver], see [OverrideResolver.Declaration].
     * @param classFileAbsence whether the class file of a class which is not found is absent, see [isAbsent],
     *   told only when needed, `null` when its class file is known to be there.
     * @param isMissProbed whether the class is found, or its miss told apart from a class failing to load, see [lookUpClass],
     *   unlike a miss which is not probed, unless it is caused by an `IOException` while its class file is there.
     */
    private class ClassLookup(
        val foundClass: FoundClass?,
        val isFinal: Boolean,
        val unloadableClassBytes: ByteArray? = null,
        private val classFileAbsence: FinalOnce<Boolean>? = null,
        val isMissProbed: Boolean = true
    ) {
        val isAbsent: Boolean get() = foundClass == null && classFileAbsence?.value == true

        fun asFinal(): ClassLookup =
            if (isFinal) this else ClassLookup(foundClass, isFinal = true, unloadableClassBytes, classFileAbsence, isMissProbed)
    }

    /**
     * @param isMissProbed whether a class which is not found is told apart from one whose class file is there,
     *   but cannot be loaded, maybe only for now, like after an `IOException`,
     *   which is worth a resource lookup only if the miss is cached.
     *   A class loader hiding a class while serving its class file, like a filtering one of an OSGi bundle,
     *   is then indistinguishable from a transient failure, so that such a miss costs a resource lookup,
     *   and nothing depending on it is cached, until its failure is deemed final, see [TransientFailures].
     */
    private fun lookUpClass(binaryName: String, isMissProbed: Boolean): ClassLookup = try {
        val clazz = classLoader.loadClass(binaryName)
        ClassLookup(FoundClass(binaryName, clazz), isFinal = true)
    } catch (e: ClassNotFoundException) {
        // also thrown when the class file is there, but cannot be read, like by a URLClassLoader after an IOException,
        // which, when kept as the cause, is worth the probe also when the miss is not cached,
        // as it is also thrown for a class file which is not there, like a NoSuchFileException
        val hasClassFile = (isMissProbed || isCausedByIOException(e)) && classLoader.hasClassFile(binaryName)
        ClassLookup(
            null,
            isFinal = !hasClassFile,
            classFileAbsence = classFileAbsence(binaryName),
            isMissProbed = isMissProbed || hasClassFile
        )
    } catch (e: LinkageError) {
        // the class file is there, but refers to a class which is not, like its supertype,
        // or is of a newer JVM version, or is malformed, while its declarations can still be read from its metadata;
        // the class is to be loaded again only after a linkage error which might be transient, see isTransientLinkageError,
        // or when its class file is there, but cannot be read, maybe only for now,
        // unlike one which is not served at all, like by an in-memory class loader
        val bytes = classLoader.readClassBytes(binaryName)
        val classFile = bytes?.let { readClassFile(binaryName, it) }
        ClassLookup(
            classFile?.takeIf { it.metadata != null }?.let { FoundClass(binaryName, clazz = null, it) },
            isFinal = !isTransientLinkageError(e) && (bytes != null || !classLoader.hasClassFile(binaryName)),
            unloadableClassBytes = bytes?.takeIf { classFile?.metadata == null },
            classFileAbsence = if (bytes == null) classFileAbsence(binaryName) else null
        )
    }

    /**
     * Whether the linkage error [e] of loading a class might not happen again, so that the class is loaded again:
     * a plain [LinkageError], like of two threads defining the same class at once,
     * a [ClassCircularityError], which is spurious when the classes are loaded in parallel,
     * while a genuine one is deemed final after a few attempts anyway, see [TransientFailures],
     * and one caused by an `IOException`, like a [NoClassDefFoundError] of a supertype
     * whose class file a `URLClassLoader` cannot read for now, throwing it wrapped in a `ClassNotFoundException`.
     * Any other one, like a [NoClassDefFoundError] of a missing supertype, an [UnsupportedClassVersionError],
     * a [ClassFormatError], or an [IncompatibleClassChangeError], is told by the class file itself, so it is final,
     * also when the class loader does not keep the `IOException` as a cause, which then cannot be told.
     */
    private fun isTransientLinkageError(e: LinkageError): Boolean =
        e.javaClass == LinkageError::class.java || e is ClassCircularityError || isCausedByIOException(e)

    /**
     * Whether the failure [e] is caused by an `IOException`, maybe transient, like of a class file which cannot be read for now.
     * A permanent one, like a `ZipException` of a corrupt jar, cannot be told apart from a transient one,
     * like of a jar being replaced, so it costs one more attempt, until its failure is deemed final, see [TransientFailures].
     */
    private fun isCausedByIOException(e: Throwable): Boolean =
        // bounded, as the causes might form a cycle
        generateSequence(e.cause) { it.cause }.take(16).any { it is IOException }

    /**
     * Reads the class file of a class by its JVM [binaryName] for the [overrideResolver],
     * telling a failure which might be transient, an `IOException`, retried once per resolve, see [transientFailures].
     */
    private fun readClassBytes(binaryName: String): ByteArray? = readClassBytesOutcome(binaryName).value

    private fun readClassBytesOutcome(binaryName: String): TransientFailures.Outcome<ByteArray?> {
        val outcome = transientFailures.attempt("class file $binaryName") {
            var failure: Exception? = null
            val bytes = classLoader.readClassBytes(binaryName) { failure = it }
            TransientFailures.Outcome(bytes, isFinal = failure !is IOException)
        }
        if (!outcome.isFinal) transientFailures.mark()
        return outcome
    }

    /**
     * Reads the class file of a class by its JVM [binaryName], whether it can be loaded or not, to tell the flags,
     * which neither [Class.isSynthetic] tells for a nested class, as it reports the flags of the `InnerClasses` attribute,
     * nor the reflection of annotations, which would load the classes of all of them,
     * and the metadata of a class which cannot be loaded,
     * unless the file is of another class, like the one found for the name `com/example/Outer`,
     * or for a name differing only in case.
     *
     * @param bytes the class file, if read.
     */
    private fun readClassFile(binaryName: String, bytes: ByteArray?): ClassFile? = try {
        bytes
            ?.let { ClassReader(it) }
            ?.takeIf { it.className.replace('/', '.') == binaryName }
            ?.let { readClassFile(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Reads the class file of a class by its JVM [binaryName], see [readClassFile],
     * telling a failure to read it which might be transient, see [readClassBytes].
     */
    private fun readClassFile(binaryName: String): TransientFailures.Outcome<ClassFile?> {
        val bytes = readClassBytesOutcome(binaryName)
        return TransientFailures.Outcome(readClassFile(binaryName, bytes.value), bytes.isFinal)
    }

    private fun parseMetadata(metadataAnnotation: Metadata): KotlinClassMetadata? = try {
        KotlinClassMetadata.readLenient(metadataAnnotation)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * The declaration of the Kotlin [clazz], the one already read of a class found, see [findClassByBinaryName],
     * not to parse its metadata again, or else read from it, see [readKmClass], or `null` if it has none, like a Java class.
     */
    private fun kmClassOf(clazz: Class<*>): KmClass? {
        val foundClass = findClassByBinaryName(clazz.name) ?: return readKmClass(clazz)
        return foundClass.kmClass
    }

    /**
     * Loads the metadata of a class by its metadata name, like `com/example/Outer.Nested`,
     * also of a class which cannot be loaded, but whose class file can still be read.
     */
    private fun loadKmClass(name: String): KmClass? = findClassByMetadataName(name)?.kmClass

    /**
     * The package in which the types are referred to by their simple names when rendering the [kmClass].
     * For nested classes, it is the actual Java package, not the outer class,
     * also of a class which cannot be loaded, taken then from its metadata name.
     */
    private fun contextPackageOf(foundClass: ClassDeclarations, kmClass: KmClass): String =
        foundClass.clazz?.`package`?.name
            ?: kmClass.name.substringBeforeLast('/', "").replace('/', '.')

    /**
     * Resolves the members named [memberName] which the listing of the class shows, in the same order:
     * the entry of an enum class, and the members generated for it, which its listing implies, see [enumMembersNamed],
     * the properties, the functions, the members of the companion object, within its declaration,
     * as they are called on the class name, like `Foo.create()`,
     * and the extensions of the class and of its supertypes, under the `// from …` header
     * of the package, or the object, to import them from, see [listedExtensions].
     * A member of a class which only the subclasses of an enclosing class can use, like a protected companion object,
     * follows the comment telling it, and one called on the name of a deprecated class, or of one within a deprecated class,
     * like `Foo.create()`, but not one called on its instance, like `foo.bar()`, the comments telling them, see [enclosingContext].
     * So do the extensions on the supertypes of the companion object, called on the name of the class too,
     * which its listing leaves out, see [companionSupertypeExtensions].
     * The JVM-only members of a built-in type, like `String.chars()`, follow its functions, see [BuiltinClass],
     * but the ones hidden by an extension, which its listing leaves out too, see [jvmOnlyFunctionsNotHidden].
     * Then the members it inherits from its supertypes, and does not override, see [inheritedMembers],
     * which its listing leaves out, not to be flooded with them, rendered before the companion object members, and the extensions.
     *
     * @param apiClass the [kmClass] as a class of the API.
     * @param isBuiltin whether the class is a built-in type, or its companion object, see [listedExtensions].
     * @param aliasedClass the class a typealias the member is resolved through expands to, see [enclosingContext].
     * @param isOnlyCalledOnClassName whether only what is called on the name of the class is resolved,
     *   like the members of its companion object, and the extensions on it, see [ListingItem.Extension.isCalledOnClassName],
     *   as through a typealias expanding to the class with type arguments, see [resolveTypeAlias].
     */
    private fun resolveMember(
        kmClass: KmClass,
        apiClass: ApiClass,
        className: String,
        declarations: ClassDeclarations,
        memberName: String,
        isBuiltin: Boolean = false,
        aliasedClass: String? = null,
        isOnlyCalledOnClassName: Boolean = false
    ): MemberListing? {
        val contextPackage = contextPackageOf(declarations, kmClass)
        // like in the listing of the class, also the extensions a built-in type lists in its body, with their receivers,
        // only the ones of the name, which alone can hide its JVM-only members of the name, see jvmOnlyFunctionsNotHidden
        // the deprecation of a companion object, reported for its extensions called on its name, like Foo.Companion.of(),
        // is told before them when resolved directly, like the one of any class, see enclosingContext
        val listedExtensions = listedExtensions(kmClass, className, apiClass, declarations, isBuiltin, memberName)
        // also the JVM-only members hidden by an extension, which are not listed, but still override the inherited ones
        // but no member of the class through a typealias expanding to it with type arguments, which they would not tell
        val members = if (isOnlyCalledOnClassName) emptyList() else {
            val apiMembers = apiMembersNamed(kmClass, declarations, memberName, apiClass)
            val hiddenJvmOnlyFunctions = (declarations as? BuiltinClass)?.jvmOnlyFunctions.orEmpty() -
                jvmOnlyFunctionsNotHidden(kmClass, declarations, listedExtensions.functions).toSet()
            declaredMembers(
                kmClass, declarations,
                apiMembers.filter { member -> (member as? Member.Function)?.function !in hiddenJvmOnlyFunctions },
                apiClass, contextPackage
            )
        }

        val apiCompanion = apiCompanionOf(kmClass, declarations, apiClass)
        val companionMembers = apiCompanion?.let { companion ->
            val signatures = companion.propertiesNamed(memberName).map {
                formatCompanionProperty(it, companion, contextPackage)
            } + companion.functionsNamed(memberName).map {
                listOf(formatCompanionFunction(it, companion, contextPackage))
            }
            // the header is formatted only when there are signatures to render within it
            val header by lazy { formatCompanionHeader(kmClass, companion, contextPackage) }
            signatures.map { ListingItem.CompanionMember(header, it) }
        }.orEmpty()

        // a member always wins over an extension, so the inherited members are looked up also when an extension,
        // or a companion object member, is found, and the inherited overloads also next to the declared ones
        val hidingExtensions = mutableListOf<IndexedExtension>()
        val inherited = if (isOnlyCalledOnClassName) emptyList() else inheritedMembers(
            kmClass, declarations, memberName, apiClass, contextPackage, hidingExtensions
        )

        // a member, or an extension, of an object is called on its name, like Obj.ext(), as is one on the companion object
        // of a class, like Foo.of(), or one of an enum class, usually called on its entry, like Color.RED.ext(),
        // unlike one of the class, called on its instance, like foo.ext()
        val isObject = kmClass.kind == ClassKind.OBJECT || kmClass.kind == ClassKind.COMPANION_OBJECT ||
            kmClass.kind == ClassKind.ENUM_CLASS
        val companionName = kmClass.companionObject?.let { "${kmClass.name}.$it" }
        // also called on the name of the class, when also listed for the class, like on a supertype of both,
        // as told with the deprecation of the companion object, reported for Foo.ext()
        val companionSupertypeExtensions = if (isBuiltin || apiCompanion == null) null else companionSupertypeExtensions(
            kmClass, className, apiCompanion, memberName
        )
        // the ones listed for the class too, like on a supertype of both, but not on the companion object itself,
        // are also called on its instance, like foo.ext(), for which Kotlin does not report the deprecation of the companion object
        fun KmType?.isOnCompanion() = companionName != null && (this?.classifier as? KmClassifier.Class)?.name == companionName
        val classFunctionDeclarations = listedExtensions.functions
            .filter { !it.function.receiverParameterType.isOnCompanion() }.mapTo(HashSet()) { it.function }
        val classPropertyDeclarations = listedExtensions.properties
            .filter { !it.property.receiverParameterType.isOnCompanion() }.mapTo(HashSet()) { it.property }
        // which Kotlin does not report the deprecations of the classes its name goes through for either, told with each of them,
        // unless called on an entry of an enum class, like Color.RED.ext(), writing its name too
        val context = enclosingContext(kmClass, apiClass, declarations, includingClass = true, isBuiltin, aliasedClass)
        val classDeprecations = if (isObject) "" else context.joinToString("") { it.deprecationOnClassName.orEmpty() }
        val companionFunctions = companionSupertypeExtensions?.functions.orEmpty().map {
            if (it.function in classFunctionDeclarations) it.copy(isAlsoCalledOnInstance = true) else it
        }
        val companionProperties = companionSupertypeExtensions?.properties.orEmpty().map {
            if (it.property in classPropertyDeclarations) it.copy(isAlsoCalledOnInstance = true) else it
        }
        val companionFunctionDeclarations = companionFunctions.mapTo(HashSet()) { it.function }
        val companionPropertyDeclarations = companionProperties.mapTo(HashSet()) { it.property }
        val extensions = extensionSignatures(
            (listedExtensions.functions + hidingExtensions).distinct().filter { it.function !in companionFunctionDeclarations } +
                companionFunctions,
            listedExtensions.properties.filter { it.property !in companionPropertyDeclarations } + companionProperties,
            contextPackage,
            classDeprecations = classDeprecations,
            isAlsoCalledOnInstance = { !isObject && it }
        ) { extension, receiver ->
            // told by their declarations, the very instances of the index, as two receivers may look the same, like T
            val isOnCompanion = when (extension) {
                is IndexedExtension -> extension.function in companionFunctionDeclarations
                is IndexedExtensionProperty -> extension.property in companionPropertyDeclarations
            }
            // the ones on the companion object itself are listed for the class only, when it extends Any only
            isObject || isOnCompanion || receiver.isOnCompanion()
        }
        val enumMembers = if (isOnlyCalledOnClassName) emptyList() else {
            enumMembersNamed(kmClass, declarations, memberName, contextPackage)
        }
        return MemberListing.of(
            (enumMembers + members).map { ListingItem.Declaration(it) } + inherited + companionMembers +
                // through a typealias expanding to the class with type arguments, only the ones called on the name of the class
                extensions.filter { !isOnlyCalledOnClassName || it.isCalledOnClassName },
            context = context,
            areMembersCalledOnClassName = isObject
        )
    }

    /**
     * The entry of the [kmClass], if it is an enum class, named [name], rendered like in its listing, see [formatEnumEntry],
     * and the member of the name which the compiler generates for every enum class:
     * `entries`, unless an entry of the name shadows it, also of an enum class compiled before Kotlin 1.9,
     * whose metadata does not tell it has one, as Kotlin resolves it for any enum class,
     * `valueOf`, called instead of a function of the same signature of its companion object, and `values`,
     * all of them called on the name of the enum class, like `Color.RED`, or `Color.valueOf("RED")`,
     * and none of them listed with the class, which `enum class` implies.
     */
    private fun enumMembersNamed(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        name: String,
        contextPackage: String
    ): List<List<String>> {
        if (kmClass.kind != ClassKind.ENUM_CLASS) return emptyList()
        val entry = kmClass.kmEnumEntries.firstOrNull { it.name == name }
        val isEntry = entry != null
        val type by lazy(LazyThreadSafetyMode.NONE) { formatType(classType(kmClass.name), contextPackage) }
        return listOfNotNull(
            entry?.let { formatEnumEntry(it, declarations, withHidden = false) },
            when (name) {
                "entries" -> if (!isEntry) "val entries: EnumEntries<$type>" else null
                "valueOf" -> "fun valueOf(value: String): $type"
                "values" -> "fun values(): Array<$type>"
                else -> null
            }
        ).map { listOf(it) }
    }

    /**
     * The [entry] of an enum class rendered as its name, following its deprecation, if any, see [formatDeprecation],
     * like `@Deprecated("Use NEW") OLD`, which Kotlin reports for `Color.OLD`,
     * also one deprecated as hidden, when [withHidden], like in the listing of the enum class,
     * like `@Deprecated("Gone", level = DeprecationLevel.HIDDEN) OLD`, telling it cannot be used,
     * while a `when` expression still has to cover it, or else `null`, as Kotlin does not resolve `Color.OLD` then.
     */
    private fun formatEnumEntry(entry: KmEnumEntry, declarations: ClassDeclarations, withHidden: Boolean): String? {
        val deprecation = declarations.deprecation(entry)
        return if (deprecation?.isHidden == true && !withHidden) null else {
            formatDeprecation(deprecation, withHidden) + escapeIdentifier(entry.name)
        }
    }

    /**
     * The extensions named [memberName] on the supertypes of the [companion] object of the [kmClass], named [className],
     * called on the name of the class too, like `Foo.ext()` of `fun Bar.ext()` for `companion object : Bar`,
     * which its listing leaves out, see [extendedCompanionOf], as listed for the companion object, see [listedExtensions],
     * but the ones shadowed by its members, see [withoutShadowedByMembers],
     * also the ones listed for the class too, like on the companion object itself, or on a supertype of both,
     * telling the deprecation of the companion object, which Kotlin reports for them, like for `Foo.ext()`,
     * but not for the ones on a supertype of both called on an instance, like `foo.ext()`, see [IndexedExtension.isAlsoCalledOnInstance],
     * or `null` if the companion object extends `Any` only, whose extensions on itself are listed for the class,
     * see [listedExtensions], or is protected itself, as nothing would tell that only the subclasses can call them,
     * while kotlinc 2.4.21 compiles `Foo.ext()` anywhere, failing elsewhere with an `IllegalAccessError`.
     * Within a protected class, they are called on its name, like its members, see [EnclosingClass.lineOn].
     */
    private fun companionSupertypeExtensions(
        kmClass: KmClass,
        className: String,
        companion: ApiCompanion,
        memberName: String
    ): ListedExtensions? {
        if (companion.apiClass.scopes.last().visibility == Visibility.PROTECTED) return null
        if (companion.kmClass.supertypes.all { (it.classifier as? KmClassifier.Class)?.name == ANY_CLASS_NAME }) return null
        val extensions = listedExtensions(
            companion.kmClass, "$className.${kmClass.companionObject}", companion.apiClass, companion.foundClass,
            isBuiltin = false, memberName
        )
        val deprecation = companion.foundClass.classDeprecation
        return ListedExtensions(
            extensions.functions.map { it.copy(companionDeprecation = deprecation) },
            extensions.properties.map { it.copy(companionDeprecation = deprecation) }
        )
    }

    /**
     * The [members] of the [kmClass], each on its lines, like in its listing, see [apiMembersNamed].
     */
    private fun declaredMembers(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        members: List<Member>,
        apiClass: ApiClass,
        contextPackage: String
    ): List<List<String>> {
        val isOpenForOverrides = isOpenForOverrides(kmClass, apiClass)
        val typeParameters = typeParameterScopeOf(kmClass, declarations)
        return members.map { member ->
            formatMember(member, kmClass, declarations, apiClass, contextPackage, isOpenForOverrides, typeParameters = typeParameters)
        }
    }

    /**
     * A property, or a function, of a class, as rendered,
     * maybe with the type arguments of an inheriting class substituted, see [substituted],
     * while its declaration tells its key, its overrides, and its deprecation.
     */
    private sealed interface Member {

        val returnType: KmType

        val visibility: Visibility

        val modality: Modality

        /**
         * This member of the [modality], like an abstract member implemented by a Java class.
         */
        fun withModality(modality: Modality): Member

        /**
         * The key of this member of the [owner], see [memberKey].
         */
        fun key(owner: KmClass): String

        /**
         * The keys of the members of the supertypes of the [owner] which this member of it overrides,
         * see [OverrideResolver.overriddenMembers], a function of a Java method of the [jvmDescriptor], if told.
         */
        fun overridden(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?, jvmDescriptor: String? = null): Set<String>

        /**
         * Whether this member of the [owner] is an override, see [OverrideResolver.isOverride],
         * a function of a Java method of the [jvmDescriptor], if told, see [overridden].
         */
        fun isOverride(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?, jvmDescriptor: String? = null): Boolean

        /**
         * The signature of this member as rendered, see [memberSignature], the type parameters of its class
         * named by the [classTypeParameters], by their ids, and its own ones by their positions,
         * as the supertypes declaring a member of the same signature might name them differently.
         */
        fun signature(classTypeParameters: Map<Int, String>): String

        /**
         * This member with the type arguments of the [substitution] substituted, see [KmType.substituted],
         * its own type parameters renamed apart from the ones of the [names] the type arguments refer to.
         */
        fun substituted(substitution: Map<Int, KmType>, names: Map<Int, String>): Member

        /**
         * This member with each of its types given by the [type].
         */
        fun withTypes(type: (KmType) -> KmType): Member

        /**
         * @param declaration the [property] as declared, unless it is rendered with substituted type arguments.
         */
        class Property(val property: KmProperty, val declaration: KmProperty = property) : Member {

            override val returnType: KmType get() = property.returnType

            override val visibility: Visibility get() = property.visibility

            override val modality: Modality get() = property.modality

            override fun withModality(modality: Modality): Member = Property(property.withModality(modality), declaration)

            override fun key(owner: KmClass): String = memberKey(owner, declaration)

            override fun overridden(
                overrideResolver: OverrideResolver,
                owner: OverrideResolver.Owner?,
                jvmDescriptor: String?
            ): Set<String> = owner?.let { overrideResolver.overriddenMembers(it, declaration) }.orEmpty()

            override fun isOverride(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?, jvmDescriptor: String?): Boolean =
                owner != null && overrideResolver.isOverride(owner, declaration)

            override fun signature(classTypeParameters: Map<Int, String>): String =
                memberSignature(property, classTypeParameters + positionalNames(property.typeParameters))

            override fun substituted(substitution: Map<Int, KmType>, names: Map<Int, String>): Member =
                Property(property.substituted(substitution, names), declaration)

            override fun withTypes(type: (KmType) -> KmType): Member = Property(property.withTypes(type), declaration)

        }

        /**
         * @param declaration the [function] as declared, unless it is rendered with substituted type arguments.
         */
        class Function(val function: KmFunction, val declaration: KmFunction = function) : Member {

            override val returnType: KmType get() = function.returnType

            override val visibility: Visibility get() = function.visibility

            override val modality: Modality get() = function.modality

            override fun withModality(modality: Modality): Member = Function(function.withModality(modality), declaration)

            override fun key(owner: KmClass): String = memberKey(owner, declaration)

            override fun overridden(
                overrideResolver: OverrideResolver,
                owner: OverrideResolver.Owner?,
                jvmDescriptor: String?
            ): Set<String> = owner?.let { overrideResolver.overriddenMembers(it, declaration, jvmDescriptor) }.orEmpty()

            override fun isOverride(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?, jvmDescriptor: String?): Boolean =
                owner != null && overrideResolver.isOverride(owner, declaration, jvmDescriptor)

            override fun signature(classTypeParameters: Map<Int, String>): String =
                memberSignature(function, classTypeParameters + positionalNames(function.typeParameters))

            override fun substituted(substitution: Map<Int, KmType>, names: Map<Int, String>): Member =
                Function(function.substituted(substitution, names), declaration)

            override fun withTypes(type: (KmType) -> KmType): Member = Function(function.withTypes(type), declaration)

        }

        private companion object {

            /**
             * The [typeParameters] of a member named by their positions, like `#0`, by their ids.
             */
            fun positionalNames(typeParameters: List<KmTypeParameter>): Map<Int, String> =
                typeParameters.withIndex().associate { (index, typeParameter) -> typeParameter.id to "#$index" }

        }

    }

    /**
     * The members named [memberName] which the [kmClass] declares, which are part of the API in the [scope], see [inApi],
     * but the ones deprecated as hidden, see [apiMembersIncludingHidden].
     */
    private fun apiMembersNamed(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        memberName: String,
        scope: ApiClass
    ): List<Member> = apiMembersIncludingHidden(kmClass, declarations, memberName, scope).mapNotNull { apiMember ->
        apiMember.member.takeUnless { apiMember.isHidden }
    }

    /**
     * A [member] of a class, see [apiMembersIncludingHidden], telling whether it [isHidden], deprecated as hidden,
     * told only when asked, not to look up the deprecations, through kotlin-reflect for a built-in type,
     * of the declared members, which hide the inherited ones they override, hidden or not, see [inheritedCandidates].
     */
    private class ApiMember(val member: Member, isHidden: () -> Boolean) {
        val isHidden: Boolean by lazy(LazyThreadSafetyMode.NONE, isHidden)
    }

    /**
     * The members named [memberName] which the [kmClass] declares, which are part of the API in the [scope], see [inApi]:
     * the properties, and the functions, followed by the JVM-only members of a built-in type, see [BuiltinClass],
     * also the ones deprecated as hidden, like the overrides, which hide the members they override, see [inheritedMembers],
     * each telling whether it is hidden.
     *
     * @param scope the class of the API telling which of the members are part of it,
     *   the [kmClass] itself, or its subclass inheriting them, see [inheritedMembers].
     * @param jvmOnlyFunctions the JVM-only functions of a built-in type, see [jvmOnlyFunctionsNotHidden].
     */
    private fun apiMembersIncludingHidden(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        memberName: String,
        scope: ApiClass,
        jvmOnlyFunctions: List<KmFunction> = (declarations as? BuiltinClass)?.jvmOnlyFunctions.orEmpty()
    ): List<ApiMember> {
        val builtinClass = declarations as? BuiltinClass
        val properties = kmClass.properties.filter { it.name == memberName } +
            builtinClass?.jvmOnlyProperties.orEmpty().filter { it.name == memberName }
        val functions = kmClass.functions.filter { it.name == memberName } +
            jvmOnlyFunctions.filter { it.name == memberName }
        return properties.inApi(scope, deprecations = null).map {
            ApiMember(Member.Property(it)) { declarations.isHidden(it, scope) }
        } + functions.inApi(scope, deprecations = null).map {
            ApiMember(Member.Function(it)) { declarations.isHidden(it) }
        }
    }

    /**
     * Format a [member] of the [kmClass] on its lines, the setter visibility of a property decided in the [scope].
     *
     * @param inheritedBy the kind of the class inheriting the [member], see [inheritedMembers],
     *   or `null` if it is listed as a member of the [kmClass] itself.
     * @param typeParameters the type parameters the [member] refers to.
     * @param inheriting the class inheriting the [member], and its declarations, see [inheritedMembers],
     *   or `null` if it is listed as a member of the [kmClass] itself,
     *   the class whose members tell the synthetic property a getter is also read as, see [syntheticPropertyOf],
     *   hinted on a line of its own above it, like the other comments on what follows them,
     *   like `// also property: var name` above `fun getName(): String`.
     */
    private fun formatMember(
        member: Member,
        kmClass: KmClass,
        declarations: ClassDeclarations,
        scope: ApiClass,
        contextPackage: String,
        isOwnerOpenForOverrides: Boolean,
        inheritedBy: ClassKind? = null,
        typeParameters: TypeParameterScope,
        isJvmOnlyOverride: Boolean = false,
        inheriting: Pair<KmClass, ClassDeclarations>? = null
    ): List<String> {
        // a member inherited by an interface is rendered as within it, like the ones of kotlin.Any
        val isInterface = kmClass.kind == ClassKind.INTERFACE || inheritedBy == ClassKind.INTERFACE
        // also the open member of a class, unless inherited by an interface,
        // where it is rendered as open, like its own members, telling it apart from an abstract one,
        // like the member with a body of an interface, rendered as within it
        val isInheritedByFinalClass = inheritedBy != null && !isOwnerOpenForOverrides && !isInterface
        val owner = declarations.overrideOwner
        return when (member) {
            is Member.Property -> formatApiProperty(
                member.property, scope, declarations,
                inInterface = isInterface, owner = owner, contextPackage = contextPackage, classTypeParameters = typeParameters,
                isOwnerOpenForOverrides = isOwnerOpenForOverrides, isInheritedByFinalClass = isInheritedByFinalClass,
                declaration = member.declaration
            )
            is Member.Function -> listOfNotNull(
                syntheticPropertyOf(member, declarations, inheriting?.first ?: kmClass, inheriting?.second ?: declarations, scope)
                    ?.let { "// also property: $it" },
                formatFunction(
                    member.function, inInterface = isInterface, owner = owner, contextPackage = contextPackage,
                    classTypeParameters = typeParameters,
                    deprecation = declarations.deprecation(member.declaration),
                    isOwnerOpenForOverrides = isOwnerOpenForOverrides, isInheritedByFinalClass = isInheritedByFinalClass,
                    declaration = member.declaration,
                    isJvmOnlyOverride = isJvmOnlyOverride,
                    jvmDescriptor = (declarations as? JavaClass)?.jvmDescriptorOf(member.declaration)
                )
            )
        }
    }

    /**
     * The synthetic property which Kotlin also reads the getter [member] of the [memberDeclarations] as, on the [kmClass],
     * rendered as `val`, or as `var` when the [kmClass] has a setter of it, like `var name` of `getName()` of `java.lang.Thread`,
     * see [formatMember], or `null` if there is none, as verified with kotlinc 2.4.21.
     * It is not listed as a member of its own, as Kotlin does not let it be referenced, like `Thread::name`,
     * and it would repeat every getter.
     * Kotlin reads so a function named like a getter, see [syntheticPropertyName], taking no parameters, nor type parameters,
     * and returning a value, when one of its topmost declarations, the ones overriding nothing, is a Java one, see [isJavaRootMethod]:
     * a method of a Java class, or a JVM-only one of a built-in type, see [BuiltinClass.jvmOnlyFunctions],
     * like `getStackTrace()` of `kotlin.Throwable`, also when the getter is a Kotlin function overriding one,
     * see [OverrideResolver.overriddenJavaMembers], or when the [kmClass] inherits one too,
     * like the `getW()` of both a Java and a Kotlin interface, unlike any other Kotlin function,
     * and unlike a Java method overriding a Kotlin function, also a member of a built-in type,
     * like the `isEmpty()` of `java.util.Collection` of the one of `Collection`,
     * but not when the [kmClass] has a property of the name, declared, or inherited, like `key` of `java.util.Map.Entry` of `getKey()`,
     * also of a Java field, which Kotlin reads instead, unless it is less visible than the getter, like a private one.
     * The setter is a function named like it, see [syntheticPropertySetterName], declared, or inherited, of any origin,
     * as visible as the getter, or public, so that the property can be assigned where it can be read,
     * taking the type the getter returns, regardless of its nullability, like `Boolean` of both `boolean` and `java.lang.Boolean`,
     * but no other type, like a supertype.
     *
     * @param scope the class of the API telling which members of the [kmClass] are part of it, see [apiMembersNamed].
     */
    private fun syntheticPropertyOf(
        member: Member,
        memberDeclarations: ClassDeclarations,
        kmClass: KmClass,
        declarations: ClassDeclarations,
        scope: ApiClass
    ): String? {
        val getter = (member as? Member.Function)?.function ?: return null
        if (!getter.isAccessorShaped(parameterCount = 0) || getter.returnType.isUnit) return null
        val name = syntheticPropertyName(getter.name) ?: return null
        val isJavaRoot = when (memberDeclarations) {
            is JavaClass -> memberDeclarations.overriddenMembers(member).isEmpty()
            is BuiltinClass -> member.declaration in memberDeclarations.jvmOnlyFunctions
            else -> false
        }
        // the Java methods the getter overrides, also as a member of the kmClass inheriting it, so that a getter inherited
        // from a Kotlin interface stands for the one of a Java interface too, like the getW() of both
        fun overriddenJavaMembers(owner: OverrideResolver.Owner?, function: KmFunction): Set<String> =
            owner?.let { overrideResolver.overriddenJavaMembers(it, function) }.orEmpty()
        val hasJavaRoot = isJavaRoot ||
            (overriddenJavaMembers(memberDeclarations.overrideOwner, member.declaration) +
                overriddenJavaMembers(declarations.overrideOwner, getter)).any(::isJavaRootMethod)
        if (!hasJavaRoot) return null
        // the types of the members the kmClass declares refer to its type parameters by the ids of the inherited ones,
        // like the T of getValue(): T of Holder<T> to the one of setValue(T) inherited from Box<T>, see inheritedCandidates
        val inheritingTypes = typeParameterScopeOf(kmClass, declarations).names.keys
            .associateWith { typeParameterType(inheritingTypeParameterId(it)) }
        fun membersNamed(memberName: String): List<Member> =
            apiMembersNamed(kmClass, declarations, memberName, scope).map { it.withTypes { type -> type.substituted(inheritingTypes) } } +
                inheritedCandidates(kmClass, declarations, memberName, scope, hidingExtensions = mutableListOf()).map { it.member }
        val isShadowed = membersNamed(name).any { property ->
            property is Member.Property && (property.visibility == Visibility.PUBLIC || property.visibility == getter.visibility)
        }
        if (isShadowed) return null
        // the getter rendered as a member of the kmClass itself, unless inherited from another class
        val getterType = if (memberDeclarations === declarations) getter.returnType.substituted(inheritingTypes) else getter.returnType
        val isVar = membersNamed(syntheticPropertySetterName(getter.name)).any { setter ->
            setter is Member.Function && setter.function.isAccessorShaped(parameterCount = 1) &&
                (setter.visibility == Visibility.PUBLIC || setter.visibility == getter.visibility) &&
                setter.function.valueParameters.single().type.isSameIgnoringNullability(getterType)
        }
        return (if (isVar) "var " else "val ") + escapeIdentifier(name)
    }

    /**
     * Whether the Java method of the [key], see [javaMemberKey], is a topmost declaration, see [syntheticPropertyOf]:
     * a JVM-only member of the built-in type its class is mapped to, see [BuiltinClass.jvmOnlyFunctions],
     * like `java.lang.Throwable#getStackTrace()`, unlike the one mapped to a member of the built-in type,
     * like `java.util.Collection#isEmpty()` to the `isEmpty()` of `Collection`,
     * or else a method of a Java class overriding no Kotlin function, see [JavaClass.overriddenMembers].
     */
    private fun isJavaRootMethod(key: String): Boolean {
        val className = key.substringBefore('#')
        overrideResolver.builtinNameOfJavaClass(className)?.let { builtinName ->
            val builtinClass = classDeclarations(builtinName) as? BuiltinClass ?: return false
            return builtinClass.jvmOnlyFunctions.any { builtinClass.jvmMethodOf(it)?.key == key }
        }
        val javaClass = findClassByName(className)?.javaDeclarations ?: return false
        val function = javaClass.kmClass.functions.firstOrNull { javaClass.jvmMethodOf(it)?.key == key } ?: return false
        return javaClass.overriddenMembers(Member.Function(function)).isEmpty()
    }

    /**
     * The JVM method of a JVM-only member of a built-in type, see [BuiltinClass.jvmMethodOf], or of a member of a Java class, see [JavaClass.jvmMethodOf]:
     * its [key], see [javaMemberKey], like `java.lang.Throwable#printStackTrace(Ljava/io/PrintStream;)V`,
     * matched with the Java members a function overrides, see [OverrideResolver.overriddenJavaMembers],
     * and its [signature], like `printStackTrace(Ljava/io/PrintStream;)V`, the one of a function overriding it on the JVM.
     */
    private class JvmMethod(val key: String, val signature: JvmMethodSignature) {

        companion object {

            /**
             * The JVM method of the Java [method], or `null` if it cannot be reflected.
             */
            fun of(method: Method?): JvmMethod? = reflectedValue(default = null) {
                method?.let {
                    val descriptor = AsmType.getMethodDescriptor(it)
                    JvmMethod(javaMemberKey(it.declaringClass.name, it.name, descriptor), JvmMethodSignature(it.name, descriptor))
                }
            }

        }

    }

    /**
     * A value parameter of the JVM method of an [InheritedCandidate], of the JVM [type],
     * of a member of a Java class, or of a JVM-only member of a built-in type, when [isJava], see [JvmMethod],
     * or of a Kotlin member, of a type which [isNullable] as substituted, see [isMergedJvmParameter].
     */
    private class JvmParameter(val type: AsmType, val isJava: Boolean, val isNullable: Boolean)

    /**
     * Whether the compiler merges two members of the same rendered signature, see [InheritedCandidate.isMergedWith],
     * taking the [parameter] and the [other] one at the same position, which it does, as K2 does, when they are
     * - of the same JVM type, by the JVM method of a member of a Java class, of a JVM-only member, see [JvmMethod],
     *   or of a member of a built-in type, see [BuiltinClass.jvmMethodOf], like `get(int)` of `java.util.List`,
     *   or else told by the metadata,
     * - of a primitive JVM type, the other one of a Kotlin member erased from a type parameter, like `put(t: T)` of `G<Int>`
     *   with `put(key: Int)`, one intersection override through a bridge, but never a nullable one, like the `Integer`
     *   of `put(key: Int?)`, nor the one of `G<Int?>`, as a Java primitive type is never a platform type, nor one of a Java member,
     *   like `put(Integer)`, or `put(T)` of a Java class given `Integer`, nor of a JVM-only member, like `putIfAbsent` of `MutableMap`,
     * - of arrays of reference types, like `Array<IntArray>` with the `Array<T>` of `G<IntArray>`,
     *   as an array of a primitive type is never merged with another one,
     *   like the `IntArray` of `vararg key: Int` with the `Array<out Int>` of `vararg t: T` of `G<Int>`,
     * - of reference types otherwise, like the `Object` of `put(T)` of a Java class with the `Integer` of `put(Integer)`,
     *   but a boxed type of a Java member, like `put(Integer)`, also annotated not null, with the erased one of a Kotlin member
     *   which is not nullable, like `put(t: T)` of `G<Int>`, unlike of `G<Int?>`.
     */
    private fun isMergedJvmParameter(parameter: JvmParameter, other: JvmParameter): Boolean {
        fun JvmParameter.isBoxedJavaAgainst(kotlin: JvmParameter) =
            isJava && type.internalName in BOXED_JVM_NAMES && !kotlin.isJava && !kotlin.isNullable
        return when {
            parameter.type == other.type -> true
            parameter.type.isPrimitive || other.type.isPrimitive -> {
                val reference = if (parameter.type.isPrimitive) other else parameter
                !reference.isJava && !reference.isNullable && reference.type.sort == AsmType.OBJECT
            }
            parameter.type.sort == AsmType.ARRAY && other.type.sort == AsmType.ARRAY ->
                !parameter.type.isPrimitiveArray && !other.type.isPrimitiveArray
            else -> !parameter.isBoxedJavaAgainst(other) && !other.isBoxedJavaAgainst(parameter)
        }
    }

    /**
     * A class of a supertype, named [name], reached from the inheriting class by the [path] of the positions
     * among the direct supertypes of each class, passing a Java class giving platform type arguments, or not, [isPlatform],
     * see [inheritedCandidates].
     */
    private class Reach(val name: String, val path: List<Int>, val isPlatform: Boolean)

    /**
     * The member contributed by a direct supertype, reached by the [reach], which [takesPlatformTypes],
     * or [returnsPlatformType], see [inheritedCandidates].
     */
    private class Contribution(val reach: Reach, val takesPlatformTypes: Boolean, val returnsPlatformType: Boolean)

    /**
     * A [member] named like the one looked up, declared by a supertype, the [kmClass] of the [declarations],
     * which is inherited, unless a member of another supertype overrides it, see [inheritedMembers],
     * rendered with the type arguments of the inheriting class substituted, see [Member.substituted].
     *
     * @param name the name the inheriting class, or its supertypes, refer to the [kmClass] by,
     *   like the name the Kotlin metadata refers to a relocated Java class by, whose [kmClass] is named by its JVM name,
     *   see [inheritedName].
     * @param key the key of the member, see [memberKey].
     * @param overridden the keys of the members of the supertypes of the [kmClass] which the [member] overrides.
     * @param signature the signature of the [member] as rendered, see [Member.signature],
     *   the same for the members of the same signature of unrelated supertypes.
     * @param rivalrySignatures the [signature] of the [member] as the compiler compares it with the one of a member of a Java class,
     *   see [isMergedWith], with the types of its value parameters not nullable, also their type arguments, see [rivalrySignatures],
     *   like the platform types of Java, like the `K!` of `computeIfAbsent` of a Java subclass of `java.util.HashMap<K, V>`,
     *   of the same signature for the compiler as a Kotlin member taking `K`, or `K?`, also of mixed nullability,
     *   like `take(first: String, second: String?)` and the `take(String, String)` of Java,
     *   or `take(list: List<String?>)` and the `take(List<String>)` of Java,
     *   and with their redundant projections invariant, see [withoutRedundantProjections],
     *   like the `Collection<out Int>` of a `Collection<? extends Integer>`, the `Collection<Int>` of a Kotlin member,
     *   of a member of a Java class either for each of its collection types, read-only or mutable,
     *   like the `MutableList<out Int>` of a Kotlin member, the `List<? extends Integer>` of Java, unlike `MutableList<Int>`,
     *   told only when needed, while a boxed parameter, like the `Int?` of `java.lang.Integer`, which becomes `Int`,
     *   is told apart from a primitive one by its JVM type, see [isMergedJvmParameter].
     * @param typeParameters the type parameters the [member] refers to, the not nullable ones, see [TypeParameterScope],
     *   also like the `U` of a `U : Any` of the inheriting class, given for the `T` of a `T & Any` of the [member].
     * @param isHidden whether the [member] is deprecated as hidden, so that it is not inherited,
     *   while it still hides the members it overrides, and the members merged with it,
     *   when the compiler picks it of them, see [inheritedCandidates].
     * @param isApi whether the [kmClass] is part of the API, see [ApiClasses], so that the [member] can be inherited,
     *   unlike a visible one of a supertype which is not, which only stops the hidden one it overrides,
     *   when the compiler picks it, the member it overrides standing for it, see [inheritedCandidates].
     * @param jvmMethod the JVM method of the [member], see [JvmMethod],
     *   of a JVM-only member of a built-in type, see [BuiltinClass], or of a member of a Java class, see [JavaClass].
     */
    private inner class InheritedCandidate(
        val name: String,
        val kmClass: KmClass,
        val declarations: ClassDeclarations,
        val member: Member,
        val key: String,
        overridden: Set<String>,
        val signature: String,
        rivalrySignatures: Lazy<Set<String>>,
        val typeParameters: TypeParameterScope,
        val isHidden: Boolean,
        val jvmMethod: JvmMethod?,
        val isApi: Boolean
    ) {

        val rivalrySignatures: Set<String> by rivalrySignatures

        /**
         * The name of the class declaring the [member], like `com/example/Base`: the [name] of a Java class,
         * whose [kmClass] is named by its JVM name, or else the name of the [kmClass], of its metadata,
         * like of a Kotlin class reached by its relocated name, never translated back from the root package.
         */
        val className: String get() = if (declarations is JavaClass) name else kmClass.name

        /**
         * The keys of the members the [member] overrides, of a Java method by its primitive types, see [JavaClass.overriddenMembers].
         */
        val overridden: Set<String> = overridden

        /**
         * Whether the [member], of a JDK class, is inherited only through a Java class of the user code,
         * which the compiler sees returning a platform type, though it enhances the return type of the JDK method,
         * like `get()` of `java.util.function.Supplier`, unless reached also directly, or through the JDK, see [inheritedCandidates].
         */
        var isEnhancementLost: Boolean = false

        /**
         * The modality of the method of the nearest Java class implementing the [member], which is inherited in its place,
         * like `intValue()` of `java.util.concurrent.atomic.AtomicInteger` of the abstract `toInt()` of `kotlin.Number`,
         * or the abstract `hashCode()` of `java.security.Permission`, declaring the one of `kotlin.Any` abstract again,
         * or of the getter of a Java class implementing an abstract property,
         * or of the nearest visible override of a supertype which is not part of the API, which the [member] stands for,
         * like a final one, or one implementing it with a body, see [inheritedCandidates], or `null`.
         */
        var implementationModality: Modality? = null

        /**
         * The Java class whose method the [implementationModality] is of, if told, which the visible override
         * of a supertype which is not part of the API below it wins over, see [inheritedCandidates], or `null`,
         * also implementing a member merged with the [member], see [inheritedCandidates].
         */
        var implementingClass: Class<*>? = null

        /**
         * The JVM method of the Java class implementing the [member], see [implementingClass], if told,
         * like the `getOrDefault(Object, Object)` of `java.util.HashMap` of the `getOrDefault` of `Map`,
         * which the [member] is merged by, like a member of a Java class, see [isMergedJvmParameter], or `null`.
         */
        var implementingJvmMethod: JvmMethod? = null

        /**
         * The modality of the method of the nearest Java interface declaring the [member] again, like abstract,
         * which the [implementationModality] of a Java class wins over, wherever walked, see [inheritedCandidates], or `null`.
         */
        var interfaceModality: Modality? = null

        /**
         * Whether the [member] stands for a Java method of the user code overriding it, which returns a platform type,
         * like the `computeIfAbsent` of a Java subclass of `java.util.HashMap` for the JVM-only member of `MutableMap`,
         * as the JDK enhancement is not inherited by such an override, see [ENHANCED_RETURN_JDK_METHODS].
         */
        var standsForPlatformType: Boolean = false

        /**
         * Whether the [member] takes platform types of Java where its declaration takes a type parameter of its class,
         * at the [classTypeParameterPositions], as the type argument a Java class gives, like the `E!` of `add(element: E)`
         * of `MutableList` for a subclass of `java.util.Vector<String>`, or of the `add(E)` of `java.util.ArrayList`
         * for a subclass of a Java `Names extends ArrayList<String>`, see [inheritedCandidates].
         */
        var takesPlatformTypes: Boolean = false

        /**
         * The positions of the value parameters of the [member] whose declared types are type parameters of its class,
         * neither nullable, nor definitely not nullable, like the `E` of `add(element: E)` of `MutableList`, see [takesPlatformTypes],
         * unless substituted by a nullable type argument.
         */
        val classTypeParameterPositions: Set<Int> by lazy(LazyThreadSafetyMode.NONE) {
            val function = member as? Member.Function ?: return@lazy emptySet()
            val own = function.declaration.typeParameters.mapTo(HashSet()) { it.id }
            function.declaration.valueParameters.indices.filterTo(HashSet()) { index ->
                val parameter = function.declaration.valueParameters[index]
                val type = parameter.type
                (type.classifier as? KmClassifier.TypeParameter)?.id?.let { it !in own } == true &&
                    !type.isNullable && !type.isDefinitelyNonNull && parameter.varargElementType == null &&
                    function.function.valueParameters.getOrNull(index)?.type?.isNullable == false
            }
        }

        // whether the declared return type of the member is a type parameter of its class, a platform type when given by Java
        val returnsClassTypeParameter: Boolean by lazy(LazyThreadSafetyMode.NONE) {
            val declaration = (member as? Member.Function)?.declaration ?: return@lazy false
            val type = declaration.returnType
            (type.classifier as? KmClassifier.TypeParameter)?.id?.let { id -> declaration.typeParameters.none { it.id == id } } == true &&
                !type.isNullable && !type.isDefinitelyNonNull
        }

        /**
         * The [member] as the compiler sees it, taking `null` at the [classTypeParameterPositions] where it [takesPlatformTypes].
         */
        val effectiveMember: Member
            get() = if (takesPlatformTypes && classTypeParameterPositions.isNotEmpty()) platformMember else member

        private val platformMember: Member by lazy(LazyThreadSafetyMode.NONE) {
            val function = member as Member.Function
            val platform = function.function.withValueParameterTypes { it }
            classTypeParameterPositions.forEach { index ->
                platform.valueParameters[index].type = platform.valueParameters[index].type.copy().also { it.isNullable = true }
            }
            Member.Function(platform, function.declaration)
        }

        /**
         * Whether the Kotlin [member] is seen by the compiler of the types declared in Kotlin, not taken over by a Java method
         * overriding it, nor taking platform types of Java, see [inheritedCandidates].
         */
        var keepsKotlinTypes: Boolean = false

        /**
         * Whether the [member], of a Java class, overrides a JVM-only member of a built-in type on the JVM,
         * like the `addFirst(String)` of a Java subclass of `java.util.AbstractList<String>`, through the bridge javac generates,
         * the one of `java.util.List` since JDK 21, see [BuiltinClass.jvmOnlyFunctions], which K2 sees as an override,
         * though the override resolver does not tell, see [inheritedCandidates].
         */
        var isJvmOnlyOverride: Boolean = false

        // the function returning a type which is not nullable, which might be a platform type, see returnsPlatformType
        private val platformCandidate: KmFunction? by lazy(LazyThreadSafetyMode.NONE) {
            (member as? Member.Function)?.declaration?.takeUnless { member.returnType.isNullable }
        }

        // whether the member, of a Java class, returns a platform type, as kotlin-reflect tells, see returnsPlatformType
        private val returnsJavaPlatformType: Boolean by lazy(LazyThreadSafetyMode.NONE) {
            platformCandidate?.let { (declarations as? JavaClass)?.returnsPlatformType(it) } == true
        }

        // whether the member, of a Java class, returns a platform type without the JDK enhancement, see isEnhancementLost
        private val returnsPlatformTypeWithoutEnhancement: Boolean by lazy(LazyThreadSafetyMode.NONE) {
            platformCandidate?.let { (declarations as? JavaClass)?.returnsPlatformType(it, isEnhancementLost = true) } == true
        }

        /**
         * Whether the [member], of a Java class, returns a platform type of Java, see [JavaClass.returnsPlatformType],
         * as substituted, unlike the `V!` of `call()` of `java.util.concurrent.Callable<String?>`, which is `String?`,
         * also when the JDK enhancement of its return type is lost, see [isEnhancementLost],
         * or of a JVM-only member, which [standsForPlatformType], told once the inherited candidates are walked.
         */
        val returnsPlatformType: Boolean
            get() = returnsJavaPlatformType || (platformCandidate != null && standsForPlatformType) ||
                (isEnhancementLost && returnsPlatformTypeWithoutEnhancement)

        /**
         * Whether the return type of the [member] is more specific than the one of the [other] candidate, a positive number,
         * or less, a negative one, or equally, `0`, or `null` if they cannot be compared, see [compareSpecificity],
         * a platform type, see [returnsPlatformType], being as specific as either nullability, like the `String!` of `call()`
         * of `java.util.concurrent.Callable<String>`, as `String`, or `String?`, though less specific than the type
         * equally specific otherwise, as the compiler prefers the one which is not a platform type, of the merged members.
         * So the result is not `0` when exactly one of them is a platform type, which tells the pick of the compiler
         * among the merged members, see [inheritedCandidates], also of two Java members, like a JDK method whose return type
         * K2 enhances, see [ENHANCED_RETURN_JDK_METHODS], merged with one returning a platform type,
         * while for an override, see [refines], the overridden member is always a Kotlin one.
         */
        fun compareReturnType(other: InheritedCandidate, supertypeNamesOf: (String) -> Set<String>): Int? {
            val isPlatformRivalry = returnsPlatformType != other.returnsPlatformType
            val specificity = compareSpecificity(
                member.returnType, other.member.returnType, supertypeNamesOf, ignoreNullability = isPlatformRivalry
            )
            return if (specificity == 0 && isPlatformRivalry) (if (returnsPlatformType) -1 else 1) else specificity
        }

        /**
         * The [member] as rendered, of the modality of the Java method implementing it, see [implementationModality],
         * like abstract when a Java class declares it abstract again, or open when a Java class implements an abstract one.
         * A member of an interface is rendered as within it, so that an abstract one implemented by a Java class
         * is rendered as a member with a body, `open`, or else as `final`, see [formatModalityModifiers],
         * only when [isInheritedByOpenClass], as otherwise no member is rendered open, nor final.
         */
        fun renderedMember(isInheritedByOpenClass: Boolean): Member {
            val member = effectiveMember
            val modality = (implementationModality ?: interfaceModality)?.takeUnless { it == member.modality } ?: return member
            val isRendered = modality == Modality.ABSTRACT || kmClass.kind != ClassKind.INTERFACE || isInheritedByOpenClass
            return if (isRendered) member.withModality(modality) else member
        }

        /**
         * Whether the [member], of a Java class, overrides a JDK method of the JVM type of a built-in type,
         * which the built-in type does not expose, as one of its JVM-only members, see [BuiltinClass.jvmOnlyFunctions],
         * by the keys of the [exposedJdkMethods], see [javaMemberKey], like the `sort(Comparator)` of `java.util.List`,
         * which `MutableList` does not have, as Kotlin calls its `MutableList<T>.sort(comparator)` extension instead.
         */
        fun overridesHiddenJdkMethod(exposedJdkMethods: Set<String>): Boolean {
            val function = (member as? Member.Function)?.declaration ?: return false
            val owner = declarations.overrideOwner ?: return false
            return overrideResolver.overriddenJavaMembers(owner, function, javaDescriptor).any { key ->
                overrideResolver.builtinNameOfJavaClass(key.substringBefore('#')) != null && key !in exposedJdkMethods
            }
        }

        /**
         * Whether the [member] overriding the one of the [overridden] candidate makes it more visible,
         * like a public override of a protected member, or returns a more specific type, see [compareReturnType],
         * the [overridden] candidate being a Kotlin member, which never returns a platform type,
         * so that a platform type of the [member], as specific otherwise, never refines it.
         */
        fun refines(overridden: InheritedCandidate, supertypeNamesOf: (String) -> Set<String>): Boolean =
            raisesVisibility(overridden) || (compareReturnType(overridden, supertypeNamesOf) ?: 0) > 0

        /**
         * Whether the [member], of a Java class, overriding the one of the [overridden] candidate, a Kotlin member,
         * takes `null` where it does not, as a value parameter of a platform type of Java,
         * like the `E!` of `add` of a Java subclass of `java.util.ArrayList<E>`, or of `java.util.Vector<E>`,
         * which is no pure implementation of `MutableList`, see [isNotNullClassTypeParameter],
         * unlike the `add(element: E)` of `MutableList`, so that K2 takes it as the member, see [inheritedCandidates].
         */
        fun acceptsNullUnlike(overridden: InheritedCandidate): Boolean {
            if (declarations !is JavaClass) return false
            val parameters = (effectiveMember as? Member.Function)?.function?.valueParameters ?: return false
            val overriddenParameters = (overridden.member as? Member.Function)?.function?.valueParameters ?: return false
            return parameters.size == overriddenParameters.size && parameters.indices.any { index ->
                parameters[index].type.isNullable && !overriddenParameters[index].type.isNullable
            }
        }

        /**
         * Whether the [member], of a Java class, is of the same signature as the [jvmOnly] member of a built-in type,
         * see [BuiltinClass.jvmOnlyFunctions], which stands for it, see [inheritedCandidates],
         * or, of a JDK class, of the same JVM method name and descriptor, and of the same signature regardless of nullability,
         * see [rivalrySignatures], like the `computeIfAbsent(K!, …)` of `java.util.Hashtable`, taking platform types,
         * see [isNotNullClassTypeParameter], the one of `MutableMap` taking `K`,
         * unlike the `compute` of `java.util.Properties`, taking a `BiFunction<in Any, in Any, *>`.
         */
        fun standsFor(jvmOnly: InheritedCandidate): Boolean {
            if (jvmOnly.signature == signature) return true
            // a Java method of the user code taking platform types is listed in place of the JVM-only member, see acceptsNullUnlike
            if (!reflectedValue(default = false) { (declarations as? JavaClass)?.clazz?.isJdkClass == true }) return false
            val method = jvmMethod?.signature ?: return false
            val jvmOnlyMethod = jvmOnly.jvmMethod?.signature ?: return false
            return method.name == jvmOnlyMethod.name && method.descriptor == jvmOnlyMethod.descriptor &&
                rivalrySignatures.any { it in jvmOnly.rivalrySignatures }
        }

        /**
         * Whether the [member] overriding the one of the [overridden] candidate makes it more visible,
         * like a public override of a protected member.
         */
        fun raisesVisibility(overridden: InheritedCandidate): Boolean =
            member.visibility == Visibility.PUBLIC && overridden.member.visibility != Visibility.PUBLIC

        /**
         * Whether the [member] is merged with the one of the [other] candidate, competing with it,
         * as of the same kind, and of the same [signature], also regardless of the nullability of their value parameters,
         * see [rivalrySignatures], when one of them is a member of a Java class, taking the platform types of Java,
         * as two Kotlin members differing by it clash on the JVM,
         * unless their value parameters are of JVM types the compiler keeps apart, see [isMergedJvmParameter],
         * or overriding the same member, declared by another supertype,
         * as the members of the same supertype are its overloads.
         */
        fun isMergedWith(other: InheritedCandidate): Boolean =
            other.className != className && (other.member is Member.Property) == (member is Member.Property) &&
                (other.overridden.any { it in overridden } ||
                    (other.signature == signature || isJavaRivalry(other) && other.rivalrySignatures.any { it in rivalrySignatures }) &&
                    isMergedOnJvm(other))

        // whether one of the members is a member of a Java class, see isMergedWith
        private fun isJavaRivalry(other: InheritedCandidate): Boolean = declarations is JavaClass || other.declarations is JavaClass

        // whether the value parameters of the JVM methods of both members are merged position by position, where both are told
        private fun isMergedOnJvm(other: InheritedCandidate): Boolean {
            val parameters = jvmParameters ?: return true
            val otherParameters = other.jvmParameters ?: return true
            return parameters.size != otherParameters.size || parameters.indices.all { index ->
                val parameter = parameters[index]
                val otherParameter = otherParameters[index]
                parameter == null || otherParameter == null || isMergedJvmParameter(parameter, otherParameter)
            }
        }

        // a JVM-only member is a Java method of its own, see jvmMethod, merged like the members of a Java class,
        // and so is a member standing for the method of the Java class implementing it, see implementingJvmMethod,
        // told while the Java candidates are walked, see inheritedCandidates, which asks isMergedWith only afterwards
        private val jvmParameters: List<JvmParameter?>?
            get() = if (implementingJvmMethod == null) declaredJvmParameters else implementingJvmParameters

        // the value parameters of the JVM method of the function, see JvmParameter, by its JVM signature,
        // of a member of a Java class, or of a JVM-only member, or else told by the metadata,
        // or by kotlin-reflect of a member of a built-in type, like get(I) of java.util.List,
        // or else erased from its declared types, see erasedJavaClass,
        // each null if it cannot be told, like a declared type of a Java member which is not nullable, maybe primitive or not
        private val declaredJvmParameters: List<JvmParameter?>? by lazy(LazyThreadSafetyMode.NONE) {
            val declaration = (member as? Member.Function)?.declaration ?: return@lazy null
            jvmParametersOf(
                jvmMethod?.signature ?: declaration.signature ?: (declarations as? BuiltinClass)?.jvmMethodOf(declaration)?.signature,
                isJava = jvmMethod != null || declarations is JavaClass
            )
        }

        // the value parameters of the Java method implementing the function, see implementingJvmMethod
        private val implementingJvmParameters: List<JvmParameter?>? by lazy(LazyThreadSafetyMode.NONE) {
            jvmParametersOf(implementingJvmMethod?.signature, isJava = true)
        }

        // the value parameters of the JVM method of the signature, see declaredJvmParameters
        private fun jvmParametersOf(signature: JvmMethodSignature?, isJava: Boolean): List<JvmParameter?>? {
            val function = member as? Member.Function ?: return null
            val declaration = function.declaration
            val valueParameters = function.function.valueParameters
            // the Continuation of a suspend function follows its value parameters, which its receivers precede
            val types = signature?.descriptor?.let { AsmType.getArgumentTypes(it) }
                ?.let { if (declaration.isSuspend) it.dropLast(1) else it.toList() }
                ?.takeLast(valueParameters.size)
                ?.takeIf { it.size == valueParameters.size }
                ?: declaration.valueParameters.map { parameter ->
                    // a primitive Java type is never nullable, unlike a boxed one, while the vararg ones are not told
                    val type = parameter.type.takeIf { parameter.varargElementType == null && (!isJava || it.isNullable) }
                    type?.let { erasedJavaClass(it) }?.let { AsmType.getType(it) }
                }
            return types.zip(valueParameters) { type, parameter -> type?.let { JvmParameter(it, isJava, parameter.type.isNullable) } }
        }

        /**
         * Whether the [member], of a Java class, is of the same signature as the one of the [other] candidate, also of a Java class,
         * or standing for the method of the Java class implementing it, see [implementingJvmMethod],
         * though taking boxed types where the [other] one takes primitive types on the JVM, never the other way round,
         * like the `put(@NotNull Integer)` next to `put(int)`, both `put(Int)` in Kotlin, which calls the latter, see [inheritedCandidates].
         */
        fun isShadowedBy(other: InheritedCandidate): Boolean {
            if (jvmMethod == null || (other.jvmMethod ?: other.implementingJvmMethod) == null || other.signature != signature) return false
            val parameters = declaredJvmParameters ?: return false
            val otherParameters = other.jvmParameters ?: return false
            if (parameters.size != otherParameters.size) return false
            val primitives = parameters.map { it?.type?.isPrimitive == true }
            val otherPrimitives = otherParameters.map { it?.type?.isPrimitive == true }
            return primitives != otherPrimitives && primitives.indices.none { primitives[it] && !otherPrimitives[it] }
        }

        /**
         * Whether the [member] overrides the one of the [other] candidate, telling it by its key,
         * or by the key of its JVM method, see [OverrideResolver.overriddenJavaMembers],
         * like the `newThread(r: Runnable)` of a Kotlin interface the `newThread(r: Runnable?)` of `java.util.concurrent.ThreadFactory`,
         * or by its JVM signature, which overrides it on the JVM, like `apply(t: String)` the `apply(s: String?)` of a Java subtype
         * of `java.util.function.Function<String, String>`,
         * unless the [other] candidate, of a Java class, overrides the [member] in turn, by its key,
         * like the method of a Java class implementing the member of an internal Kotlin class, or interface, above it.
         */
        fun overrides(other: InheritedCandidate): Boolean =
            other.key in overridden || key !in other.overridden && other.jvmMethod?.let { jvmMethod ->
                jvmMethod.key in overriddenJavaMembers ||
                    jvmMethod.signature == (member as? Member.Function)?.declaration?.signature
            } == true

        // the JVM descriptor of the member of a Java class, telling its primitive types, see OverrideResolver.overriddenJavaMembers
        private val javaDescriptor: String? get() = jvmMethod?.signature?.descriptor?.takeIf { declarations is JavaClass }

        // the keys of the JVM methods the member overrides, see overrides
        private val overriddenJavaMembers: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
            val function = (member as? Member.Function)?.declaration
            val owner = declarations.overrideOwner
            if (function == null || owner == null) emptySet() else overrideResolver.overriddenJavaMembers(owner, function, javaDescriptor)
        }

    }

    /**
     * The members named [memberName] which the [kmClass] inherits from its supertypes, and does not override,
     * each under the `// inherited from …` header of the supertype declaring it, or none.
     *
     * The supertypes are walked breadth-first through their cached declarations, and `kotlin.Any`, the supertype of every class,
     * last, also when it is reached only through Java interfaces, but by `kotlin.Nothing`, which has no members.
     * Their members of the name are merged,
     * but the ones which a member of another supertype overrides, see [OverrideResolver.overriddenMembers],
     * so that the nearest declaration of each signature wins, like an override declared by an intermediate class,
     * while an overload which it does not override is still inherited from further up.
     * An override deprecated as hidden, declared or inherited, is never listed, but it still overrides,
     * so that the compiler resolves neither it, nor the member it overrides, on the [kmClass],
     * unless it picks the member it overrides of another direct supertype, see below.
     * The members are rendered with the type arguments which the [kmClass] gives for the type parameters of the supertypes
     * substituted, see [supertypeSubstitution], like `fun get(index: Int): String` of `kotlin.collections.List`
     * for a subclass of `java.util.AbstractList<String>`, as the type parameters of the supertypes are not in its scope,
     * while its own ones, given as type arguments, are, like `E` of `class Container<E> : List<E>`,
     * see [inheritingTypeParameterId], the type parameters of a member renamed apart from them,
     * like `fun <U1> thenApply(arg0: Function<in U, out U1>?)` of `CompletableFuture<T>`
     * for `class Future<U> : CompletableFuture<U>()`.
     * Of the members of the same signature as rendered, see [Member.signature], or overriding the same member,
     * declared by unrelated supertypes, the one of the most specific return type wins, see [compareSpecificity],
     * like the `MutableIterator<E>` of `MutableCollection.iterator()` over the `Iterator<E>` of `List.iterator()`
     * for `MutableList`, as of the intersection override the compiler infers,
     * or the first one, when they are equally specific, while all of them are inherited when they cannot be compared.
     * None of them is inherited when the compiler picks a hidden one among the members which the direct supertypes contribute,
     * the one of the most specific return type, or else the one of the first direct supertype in the order of declaration,
     * also when another direct supertype overrides it, like `f()` of `interface Join : Left, Right`, hidden by `Left`,
     * while a member overridden only by hidden ones is still inherited when the compiler picks it,
     * like `f()` of `interface Join : Root, Left`, which `Left` overrides as hidden,
     * or the `run()` of `java.lang.Runnable` next to a hidden Kotlin member of the same JVM signature,
     * also when overridden by a visible member which a hidden one overrides in turn,
     * like `f()` of `interface Join : Root, Left`, whose `Left : Mid` overrides as hidden the override of `Mid : Root`.
     * Inherited by a class which the user code cannot subclass, see [ApiClass.isOpen], a member is neither `open`, nor `final`,
     * like its own members, but a member with a body of an interface, which is rendered as within its interface,
     * telling it apart from an abstract one.
     * A Java supertype, which is not rendered, contributes the members it declares, as kotlin-reflect describes them,
     * see [JavaClass], like `ensureCapacity(arg0: Int)` of `java.util.ArrayList`, or converted alike, see [reflectedJavaClass],
     * but the ones overriding a member of a Kotlin supertype, like its `get(index: Int)`,
     * for which the Kotlin member is inherited instead, declared with the names of its parameters, which the JDK does not tell,
     * unless the Java method makes it more visible, like the public `clone()` of the protected one of `kotlin.Cloneable`,
     * or returns a more specific type, see [InheritedCandidate.refines],
     * or takes `null` where the Kotlin member does not, as a platform type, which K2 takes as the member,
     * like the `add(E)` of `java.util.Vector`, or of a Java subclass of `java.util.ArrayList`,
     * unless a Kotlin member it overrides is reached through Kotlin classes only too, see [InheritedCandidate.acceptsNullUnlike],
     * and the ones overriding a JDK method which a built-in type does not expose, like the `sort(Comparator)` of `java.util.List`,
     * see [InheritedCandidate.overridesHiddenJdkMethod].
     * Its methods are matched with the members of the Kotlin supertypes they override
     * regardless of the nullability of the platform types of Java, see [JavaClass.overriddenMembers],
     * and an abstract Kotlin member a Java method implements is not abstract, see [InheritedCandidate.implementationModality].
     * A Java class of the user code reached through a raw supertype, like `Base` of `class Mid extends Base`, contributes its methods erased,
     * as the compiler sees them, see [JavaClass.erasedFunctionOf].
     * A member of a Java class which the user code cannot name, like a package-private one, is inherited from its public subclass,
     * see [inheritedFromName], and one annotated with `@java.lang.Deprecated` is deprecated, see [javaDeprecation].
     * A Java supertype whose members cannot be described, like one which cannot be loaded, is walked through to its supertypes,
     * as told by its class file, see [rawJavaSupertypes].
     * A function type supertype, like `(Int) -> String`, contributes the `invoke` operator of its class,
     * see [functionTypeClassDeclarations], a suspend one, like `suspend (Int) -> String`, the one of its suspend function type class,
     * like `kotlin.coroutines.SuspendFunction1`, see [withSuspendFunctionTypeClass],
     * and a function reference type, like `KFunction1<Int, String>`, the one of its function type, and the members of `KFunction`.
     * A Kotlin supertype which is not part of the API, see [ApiClasses], is walked through too,
     * its members not inherited, so that the members it overrides stand for its overrides, which the user code cannot see,
     * of their modality, see [InheritedCandidate.implementationModality],
     * also the methods of a Java supertype, like `run()` of `java.lang.Thread` overridden by an internal subclass,
     * while its hidden ones still hide the members they override, like of a supertype deprecated as hidden,
     * unless its visible ones override them again.
     * Which members are part of the API is decided by the inheriting class, the [scope],
     * like a protected member, inherited by a final class, which is not.
     * The members of the [kmClass], see [apiMembersIncludingHidden], also the hidden ones, override the ones they match like any other override,
     * so that only the inherited overloads are listed next to them, like `add(element: E)` of `MutableList`
     * next to the `add(index: Int, element: E)` which `AbstractMutableList` declares.
     * A qualified [memberName], like `Nested.member`, names no member, so the supertypes are not walked then.
     * The JVM-only members of a built-in supertype, see [BuiltinClass.jvmOnlyFunctions], and the members of a Java supertype,
     * are overridden like the Java members, see [OverrideResolver.overriddenJavaMembers], or by a function of the same JVM signature,
     * or by a declared function of the same signature as rendered, with the type arguments of the supertypes substituted,
     * like `addFirst(element: String)` of an `AbstractMutableList<String>` compiled against JDK 17, taking `String` on the JVM,
     * and a JVM-only member is never inherited when hidden by an extension, see [jvmOnlyFunctionsNotHidden],
     * while the extension called instead of a member, at least for some arguments, is added to the [hidingExtensions],
     * like `fun Throwable.addSuppressed(exception: Throwable)` for a subclass of `Exception`.
     */
    private fun inheritedMembers(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        memberName: String,
        scope: ApiClass,
        contextPackage: String,
        hidingExtensions: MutableList<IndexedExtension>
    ): List<ListingItem.InheritedMember> {
        val inherited = inheritedCandidates(kmClass, declarations, memberName, scope, hidingExtensions)
        if (inherited.isEmpty()) return emptyList()
        val isOpenForOverrides = isOpenForOverrides(kmClass, scope)
        // the JVM superclass of the kmClass, also when its own Class cannot be loaded, told by the supertypes of its metadata
        val superclass = lazy(LazyThreadSafetyMode.NONE) {
            declarations.clazz?.let { reflectedValue(default = null) { it.superclass } }
                ?: kmClass.supertypes.classNames().firstNotNullOfOrNull { name ->
                    inheritedDeclarations(inheritedName(declarations, name))?.clazz
                        ?.takeUnless { reflectedValue(default = true) { it.isInterface } }
                }
        }
        return inherited.map { candidate ->
            ListingItem.InheritedMember(
                inheritedFromName(candidate, superclass),
                formatMember(
                    candidate.renderedMember(isOpenForOverrides), candidate.kmClass, candidate.declarations, scope, contextPackage,
                    isOwnerOpenForOverrides = isOpenForOverrides,
                    inheritedBy = kmClass.kind,
                    typeParameters = candidate.typeParameters,
                    isJvmOnlyOverride = candidate.isJvmOnlyOverride,
                    inheriting = kmClass to declarations
                )
            )
        }
    }

    /**
     * The name of the class the [candidate] is inherited from, rendered in its `// inherited from …` header,
     * like `java.util.ArrayList`: the class declaring it, but of a Java class which the user code cannot name,
     * like a package-private one, or one nested in it, the public JVM class extending it, closest to it, if any,
     * among the [superclass] of the inheriting class and its superclasses,
     * which inherits its public members, like the public subclass of a package-private class, which javac bridges them in.
     */
    private fun inheritedFromName(candidate: InheritedCandidate, superclass: Lazy<Class<*>?>): String {
        // a public nested class of a package-private class cannot be named either
        fun Class<*>.isNameable() = generateSequence(this) { it.enclosingClass }.all { Modifier.isPublic(it.modifiers) }
        val declaring = (candidate.declarations as? JavaClass)?.clazz
            ?.takeUnless { reflectedValue(default = true) { it.isNameable() } }
            ?: return candidate.className.replace('/', '.')
        val nearest = reflectedValue(default = null) {
            generateSequence(superclass.value) { it.superclass }
                .takeWhile { it != declaring }
                .lastOrNull { it.isNameable() && declaring.isAssignableFrom(it) }
        }
        return (nearest?.let { kotlinClassName(it) } ?: candidate.className).replace('/', '.')
    }

    /**
     * The members named [memberName] which the [kmClass] inherits from its supertypes, and does not override,
     * as candidates rendered by [inheritedMembers], see there.
     */
    private fun inheritedCandidates(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        memberName: String,
        scope: ApiClass,
        hidingExtensions: MutableList<IndexedExtension>
    ): List<InheritedCandidate> {
        if ('.' in memberName || kmClass.name == NOTHING_CLASS_NAME) return emptyList()
        // the type parameters in scope of the inheriting class, given as the type arguments of its supertypes,
        // renumbered apart from the ones of the supertypes, see inheritingTypeParameterId
        val inheritingTypeParameters = typeParameterScopeOf(kmClass, declarations)
        val inheritingScope = inheritingTypeParameters.mapIds(::inheritingTypeParameterId)
        val inheritingNames = inheritingScope.names
        val inheriting = inheritingTypeParameters.names.keys.associateWith { typeParameterType(inheritingTypeParameterId(it)) }
        val visited = hashSetOf(kmClass.name, ANY_CLASS_NAME)
        val pending = ArrayDeque(kmClass.supertypes.map { it.substituted(inheriting) })
        val candidates = mutableListOf<InheritedCandidate>()
        // the names of the direct supertypes of each class walked, in the order of declaration, see contribution below
        val directSupertypes = hashMapOf(kmClass.name to kmClass.supertypes.classNames())
        // the names of the Java classes of the user code walked, see InheritedCandidate.isEnhancementLost
        val userJavaClasses = HashSet<String>()
        // the names of the Java classes walked, see reachedThroughKotlin
        val javaClasses = HashSet<String>()
        // the names of the classes reached through a raw supertype, see JavaClass.rawSupertypeNames
        val rawlyReached = HashSet<String>()
        // whether the classes the inheriting class refers to might be relocated, so that the types are translated back, see unlocated
        val isRelocated by lazy(LazyThreadSafetyMode.NONE) {
            declarations.overrideOwner?.let { overrideResolver.isRelocated(it) } == true
        }
        // the names of the supertypes walked which are classes, not interfaces, see reachedThroughSuperclass
        val classNames = HashSet<String>()
        fun collect(name: String, supertype: KmType) {
            val foundName = inheritedName(declarations, name)
            val superDeclarations = inheritedDeclarations(foundName)
            val superKmClass = superDeclarations?.kmClass
            if (superDeclarations == null || superKmClass == null) {
                // named by the names the metadata refers to them by, like the supertypes of a Kotlin class,
                // so that a class reached also from a Kotlin supertype is not walked again under its relocated name
                val javaSupertypes = rawJavaSupertypes(foundName) { it.unlocated(declarations) }
                javaClasses += name
                directSupertypes[name] = javaSupertypes.classNames()
                pending += javaSupertypes
                return
            }
            val substitution = supertypeSubstitution(superKmClass, supertype, declarations)
            // the supertypes of a Java class, described by kotlin-reflect by their JVM names, which might be relocated,
            // are named by the names the metadata refers to them by, like the ones of the raw Java supertypes above,
            // and so are their type arguments, substituted for the type parameters of a Kotlin supertype
            val superSupertypes = if (superDeclarations !is JavaClass) superKmClass.supertypes else {
                superKmClass.supertypes.map { it.unlocated(declarations) }
            }
            directSupertypes[name] = superSupertypes.classNames()
            if (superKmClass.kind != ClassKind.INTERFACE) classNames += name
            pending += superSupertypes.map { it.substituted(substitution) }
            // the supertypes of a class reached through a raw supertype are raw too, like the raw ones of a Java class,
            // also of a Kotlin class reached raw, like the Java superclass of `KotlinMid<T> : JavaBase<T>()`
            // of a raw `class Sub extends KotlinMid`
            if (superDeclarations is JavaClass) {
                val rawSupertypeNames = superDeclarations.rawSupertypeNames.mapTo(HashSet()) { unlocatedName(declarations, it) }
                superSupertypes.classNames().filterTo(rawlyReached) { name in rawlyReached || it in rawSupertypeNames }
            } else if (name in rawlyReached) {
                rawlyReached += superSupertypes.classNames()
            }
            if (superDeclarations is JavaClass) javaClasses += name
            if (superDeclarations is JavaClass && !reflectedValue(default = true) { superDeclarations.clazz.isJdkClass }) {
                userJavaClasses += name
            }
            // a Kotlin supertype which is not part of the API contributes members which are never inherited:
            // the hidden ones, which still hide the ones they override, like of an internal supertype, or one deprecated as hidden,
            // and the visible ones, which stop the hidden ones they override, see InheritedCandidate.isApi
            // like a function type class, which no class file declares, see functionTypeClassDeclarations
            val isApi = superDeclarations is JavaClass || apiClasses.apiClass(foundName) != null ||
                superDeclarations === functionTypeClasses[name]
            val jvmOnlyFunctions = (superDeclarations as? BuiltinClass)?.jvmOnlyFunctions.orEmpty()
            val jvmOnlyFunctionsNotHidden = if (!isApi) {
                emptyList()
            } else if (jvmOnlyFunctions.any { it.name == memberName }) {
                // only an extension on the built-in type itself reads like its member, and hides it,
                // see bodyReceiverSubstitution, never one on a type parameter, like of multiple bounds, see listedFor
                val hiding = extensionIndex[superKmClass.name.replace('/', '.')].orEmpty().filter {
                    it.hidesMembers && it.function.name == memberName && it.isBuiltinExtension
                }
                jvmOnlyFunctionsNotHidden(superKmClass, superDeclarations, hiding, hidingExtensions)
            } else {
                jvmOnlyFunctions
            }
            val apiMembers = apiMembersIncludingHidden(superKmClass, superDeclarations, memberName, scope, jvmOnlyFunctionsNotHidden)
                .ifEmpty { return }
            val typeParameters = typeParameterScopeOf(superKmClass, superDeclarations) + inheritingScope
            // the members of a Java class reached through a raw supertype are seen erased, see JavaClass.erasedFunctionOf
            val isReachedRaw = superDeclarations is JavaClass && name in rawlyReached
            // the types of a member of a Java class, named by their JVM names, which might be relocated,
            // are named by the names the metadata refers to them by, like the supertypes of the class above
            fun Member.unlocated(): Member =
                if (superDeclarations !is JavaClass || !isRelocated) this else withTypes { it.unlocated(declarations) }
            apiMembers.forEach { apiMember ->
                val declared = apiMember.member
                val overridden = (superDeclarations as? JavaClass)?.overriddenMembers(declared)
                    ?: declared.overridden(overrideResolver, superDeclarations.overrideOwner)
                val erased = if (!isReachedRaw || declared !is Member.Function) null else {
                    (superDeclarations as JavaClass).erasedFunctionOf(declared.declaration)
                }
                val member = (erased?.let { Member.Function(it, (declared as Member.Function).declaration) } ?: declared)
                    .unlocated()
                    .substituted(substitution, inheritingNames)
                val signature = member.signature(inheritingNames)
                candidates += InheritedCandidate(
                    name, superKmClass, superDeclarations, member, declared.key(superKmClass), overridden,
                    signature = signature,
                    rivalrySignatures = lazy(LazyThreadSafetyMode.NONE) {
                        (erased ?: (declared as? Member.Function)?.declaration)?.let { function ->
                            val unlocated = (Member.Function(function).unlocated() as Member.Function).function
                            rivalrySignatures(unlocated, isJava = superDeclarations is JavaClass, substitution, inheritingNames)
                        } ?: setOf(signature)
                    },
                    typeParameters = typeParameters,
                    isHidden = apiMember.isHidden,
                    // a JVM-only member, or a member of a Java class, is a Java method of its own, which the Kotlin members are not
                    jvmMethod = (declared as? Member.Function)?.function?.let { function ->
                        when {
                            superDeclarations is JavaClass -> superDeclarations.jvmMethodOf(function)
                            jvmOnlyFunctionsNotHidden.any { it === function } ->
                                (superDeclarations as? BuiltinClass)?.jvmMethodOf(function)
                            else -> null
                        }
                    },
                    isApi = isApi
                )
            }
        }
        while (pending.isNotEmpty()) {
            // a suspend function type as the type of its own class, like SuspendFunction1<Int, String>, declaring a suspend invoke
            val supertype = pending.removeFirst().withSuspendFunctionTypeClass()
            val name = (supertype.classifier as? KmClassifier.Class)?.name ?: continue
            if (visited.add(name)) collect(name, supertype)
        }
        if (kmClass.name != ANY_CLASS_NAME) collect(ANY_CLASS_NAME, classType(ANY_CLASS_NAME))
        // the members of a JDK class reached only through a Java class of the user code lose the enhancement of their return types,
        // like the get() of java.util.function.Supplier for a subclass of a Java class implementing it without declaring it again,
        // unless reached also directly, or through the JDK, or a Kotlin class not overriding them, whatever the order of the supertypes
        if (userJavaClasses.isNotEmpty()) {
            val candidatesByName = candidates.groupBy { it.name }
            candidates.filter { it.declarations is JavaClass && it.name !in userJavaClasses }.forEach { candidate ->
                val keepingEnhancement = HashSet<String>()
                val reached = ArrayDeque(directSupertypes[kmClass.name].orEmpty())
                while (reached.isNotEmpty()) {
                    val name = reached.removeFirst()
                    if (!keepingEnhancement.add(name) || name in userJavaClasses) continue
                    if (candidatesByName[name].orEmpty().none { it !== candidate && it.overrides(candidate) }) {
                        reached += directSupertypes[name].orEmpty()
                    }
                }
                candidate.isEnhancementLost = candidate.name !in keepingEnhancement
            }
        }
        // the supertypes of the return types compared, see compareSpecificity, walked once per class,
        // named by their metadata names, like the types of the members of a relocated Java class, see unlocated,
        // but found by their located names, like the supertypes of the class, see inheritedName
        val supertypeNames = HashMap<String, Set<String>>()
        val supertypeNamesOf = { name: String ->
            supertypeNames.getOrPut(name) {
                supertypeNames(name, kmClassOf = { builtinClasses[it] ?: loadKmClass(inheritedName(declarations, it)) })
            }
        }
        // a Java method overriding a member of a Kotlin supertype, like get of java.util.ArrayList, is not inherited,
        // the Kotlin member is, unless the Java method refines it, or K2 takes it over, see above, and neither is one overriding
        // a JDK method which a built-in type does not expose, see InheritedCandidate.overridesHiddenJdkMethod,
        // or which Kotlin hides, see overridesHiddenJdkMethod, like the reversed(): LinkedList<E> of java.util.LinkedList,
        // overriding the hidden reversed() of java.util.List, so that list.reversed() calls the Iterable<T>.reversed() extension,
        // nor one overriding a JVM-only member of a built-in type of the same signature, unless it makes it more visible,
        // nor one overridden by a JVM class between the inheriting class and its own, see overridingBelow,
        // unless by a hidden member, which hides it only when the compiler picks it, see below,
        // or by a visible one of a supertype which is not part of the API, which it stands for, like a Kotlin member does,
        // like size() of java.util.Dictionary, overridden by java.util.Hashtable, seen in Kotlin as the size of Map
        val kotlinCandidates = candidates.filter { it.declarations !is JavaClass && it.isApi }
        val jvmOnlyCandidates = kotlinCandidates.filter { it.declarations is BuiltinClass && it.jvmMethod != null }
        val exposedJdkMethods = jvmOnlyCandidates.mapNotNullTo(HashSet()) { it.jvmMethod?.key }
        val unlistedKotlinCandidates = candidates.filter { (it.isHidden || !it.isApi) && it.declarations !is JavaClass }
        // the keys of the members the Kotlin candidate overrides, also through the other Kotlin candidates
        val overriddenByKey = kotlinCandidates.associate { it.key to it.overridden }
        fun overriddenClosure(candidate: InheritedCandidate): Set<String> {
            val closure = HashSet<String>()
            val pending = ArrayDeque(candidate.overridden)
            while (pending.isNotEmpty()) {
                val key = pending.removeFirst()
                if (closure.add(key)) overriddenByKey[key]?.let { pending += it }
            }
            return closure
        }
        // the names of the supertypes reached from the inheriting class through Kotlin classes only, like MutableList
        // of `abstract class X : java.util.AbstractList<String>(), MutableList<String>`, whose members taking no type parameter
        // of their class keep the Kotlin types, the others picked like K2 does, see keepsKotlinTypes below
        val reachedThroughKotlin by lazy(LazyThreadSafetyMode.NONE) {
            val reached = HashSet<String>()
            val pending = ArrayDeque(if (declarations is JavaClass) emptyList() else directSupertypes[kmClass.name].orEmpty())
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                if (name !in javaClasses && reached.add(name)) pending += directSupertypes[name].orEmpty()
            }
            reached
        }
        // the Java classes giving the type arguments of their supertypes as platform types, like E! of MutableList<E!>
        // of java.util.Vector<E>, also when a Kotlin class gives them in turn, also the inheriting class,
        // but the pure implementations of the Kotlin collections, see PURE_IMPLEMENTATIONS, like java.util.ArrayList<E>
        val platformGivers = javaClasses.filterTo(HashSet()) { it !in PURE_IMPLEMENTATIONS }
        if (declarations is JavaClass && kmClass.name !in PURE_IMPLEMENTATIONS) platformGivers += kmClass.name
        // the classes of the targets reached from the inheriting class, depth-first in the order of declaration
        // of the direct supertypes, by the first path to each, by the positions among the direct supertypes,
        // once passing a platform giver, and once not, not walked through
        fun reaches(targets: Set<String>): List<Reach> {
            val reaches = ArrayList<Reach>()
            val visited = HashSet<Pair<String, Boolean>>()
            fun walk(name: String, path: List<Int>, isPlatform: Boolean) {
                directSupertypes[name].orEmpty().forEachIndexed { index, supertype ->
                    if (!visited.add(supertype to isPlatform)) return@forEachIndexed
                    if (supertype in targets) {
                        reaches += Reach(supertype, path + index, isPlatform)
                    } else {
                        walk(supertype, path + index, isPlatform || supertype in platformGivers)
                    }
                }
            }
            walk(kmClass.name, emptyList(), kmClass.name in platformGivers)
            return reaches
        }
        // the class where the paths of the reaches part, by the positions among the direct supertypes, see reaches
        fun partingClass(a: Reach, b: Reach): String {
            var name = kmClass.name
            for (index in a.path.indices) {
                if (b.path.getOrNull(index) != a.path[index]) break
                name = directSupertypes[name]?.getOrNull(a.path[index]) ?: break
            }
            return name
        }
        // the member of a Java class, which takes the type arguments of its class as platform types,
        // when reached through a platform giver, like the add(E) of java.util.ArrayList for a subclass of a Java subclass of it,
        // picked like the Kotlin members below, the first one not returning a platform type, like the apply of
        // java.util.function.Function for `X : JavaFunction<String>, Function<String, String>`, returning String! through
        // the Java `JavaFunction<T> extends Function<T, T>`, so that apply(null) does not compile, unlike accept(null)
        // of java.util.function.Consumer, returning Unit, for `Y : JavaConsumer<String>, Consumer<String>`
        candidates.filter { it.declarations is JavaClass && it.classTypeParameterPositions.isNotEmpty() }.forEach { candidate ->
            val reached = reaches(setOf(candidate.name))
            val picked = reached.firstOrNull { !it.isPlatform || !candidate.returnsClassTypeParameter } ?: reached.firstOrNull()
            candidate.takesPlatformTypes = picked?.isPlatform == true
        }
        // the compiler merges a Kotlin member with the Java methods overriding it, or a member it overrides,
        // and with itself reached through other supertypes, of other type arguments, each contributed by a direct supertype,
        // the first one reached through it, also of a direct supertype in turn,
        // and picks the one not returning a platform type, the first in the order of declaration of the direct supertypes,
        // like the add(E) of java.util.AbstractList, returning boolean, taking E!,
        // for `abstract class X : java.util.AbstractList<String>(), MutableList<String>`, so that add(null) compiles,
        // unlike for `abstract class X : MutableList<String>, java.util.AbstractList<String>()`,
        // or else the first one, like the set(int, E) of java.util.AbstractList returning E!, which set(index: Int, element: E),
        // returning E, wins over, like the getter of the most specific return type, see compareReturnType,
        // also the JVM-only member of a built-in type, like addFirst of MutableList, reached through java.util.AbstractList,
        // which gives MutableList<E!>, see platformGivers, taking E!, so that addFirst(null) compiles,
        // while the Kotlin types are kept when the paths part at a class of the standard library,
        // like at kotlin.collections.AbstractMutableList, extending java.util.AbstractList, and MutableList, as kotlinc tells,
        // so that the Java method taking over the member, see acceptsNullUnlike, or the member taking platform types,
        // see InheritedCandidate.takesPlatformTypes, is told by the pick
        kotlinCandidates.forEach { candidate ->
            if (candidate.classTypeParameterPositions.isEmpty() && !candidate.returnsClassTypeParameter) {
                candidate.keepsKotlinTypes = candidate.name in reachedThroughKotlin
                return@forEach
            }
            val closure = overriddenClosure(candidate)
            val rivalsByClass = candidates.filter { rival ->
                rival.declarations is JavaClass && !rival.isHidden && (
                    candidate.key in rival.overridden || closure.any { it in rival.overridden } ||
                        candidate.declarations is BuiltinClass && candidate.jvmMethod != null && rival.standsFor(candidate)
                    )
            }.groupBy { it.name }
            val positions = candidate.classTypeParameterPositions
            val contributions = reaches(rivalsByClass.keys + candidate.name).map { reach ->
                val rival = rivalsByClass[reach.name]?.first()
                if (rival == null) {
                    Contribution(reach, reach.isPlatform && positions.isNotEmpty(), reach.isPlatform && candidate.returnsClassTypeParameter)
                } else {
                    val parameters = (rival.member as? Member.Function)?.function?.valueParameters
                    val takesPlatformTypes = positions.any { parameters?.getOrNull(it)?.type?.isNullable == true } ||
                        (reach.isPlatform && rival.classTypeParameterPositions.isNotEmpty())
                    Contribution(reach, takesPlatformTypes, rival.returnsPlatformType)
                }
            }
            val first = contributions.firstOrNull { !it.returnsPlatformType } ?: contributions.firstOrNull()
            val picked = first?.takeIf { it.takesPlatformTypes }?.let { platform ->
                contributions.firstOrNull { kotlin ->
                    !kotlin.takesPlatformTypes && !kotlin.returnsPlatformType &&
                        partingClass(platform.reach, kotlin.reach).startsWith("kotlin/")
                }
            } ?: first
            if (picked == null) {
                candidate.keepsKotlinTypes = candidate.name in reachedThroughKotlin
            } else {
                candidate.keepsKotlinTypes = !picked.takesPlatformTypes
                candidate.takesPlatformTypes = picked.takesPlatformTypes
            }
        }
        // the Java candidates overridden by a Kotlin class below, see overridingBelow, which still override on the JVM
        // the JVM-only members they override, see javaMethodSignatures below
        val overriddenBelow = ArrayList<InheritedCandidate>()
        candidates.removeAll { candidate ->
            val javaClass = candidate.declarations as? JavaClass ?: return@removeAll false
            val overriddenKotlin = kotlinCandidates.filter { it.key in candidate.overridden }
            // the Java method K2 takes as the member, of a platform type which the overridden Kotlin member does not accept,
            // like add(null) of a Java subclass of java.util.ArrayList<String>, is inherited in its place,
            // unless the compiler keeps the Kotlin types of one of the Kotlin members it overrides, see keepsKotlinTypes
            val isTakingOver = overriddenKotlin.none { it.keepsKotlinTypes }
            fun isTakenOver(overridden: InheritedCandidate) = isTakingOver && candidate.acceptsNullUnlike(overridden)
            val implementing = overriddenKotlin.filter { !candidate.refines(it, supertypeNamesOf) }
            // a Kotlin member deprecated as hidden, like put(key: Int), does not hide the Java method implementing it, like put(int),
            // which is called in its place
            val implemented = implementing.filter { !isTakenOver(it) && !it.isHidden }
            // the Kotlin member stands for the Java method implementing it, like toInt() of kotlin.Number
            // for intValue() of java.util.concurrent.atomic.AtomicInteger, the nearest one, walked first,
            // which might declare it abstract again, like an abstract Java subclass of the implementing one,
            // or java.security.Permission the equals of kotlin.Any, unlike a Java interface, like java.util.Comparator,
            // whose implementing class still inherits the one of kotlin.Any from its superclass,
            // and so does a member of a Kotlin interface declaring again the one the method of a Java class implements,
            // like contains of List the one of Collection, which java.util.AbstractCollection implements,
            // also the ones the Java method takes over, which stand for it once it is overridden in turn,
            // like by a declared overload the override resolver does not tell, like `contains(element: String?)`
            val method = lazy(LazyThreadSafetyMode.NONE) {
                (candidate.member as? Member.Function)?.let { javaClass.javaMethodOf(it.declaration) }
            }
            // the method overriding it in a JVM class below, see overridingBelow, and the unlisted candidate declaring it, if any
            val overriding = lazy(LazyThreadSafetyMode.NONE) { method.value?.let { overridingBelow(it, declarations.clazz) } }
            val isJavaInterface = javaClass.kmClass.kind == ClassKind.INTERFACE
            val redeclared = if (isJavaInterface || implementing.isEmpty()) emptyList() else kotlinCandidates.filter { redeclaring ->
                redeclaring.kmClass.kind == ClassKind.INTERFACE && redeclaring !in implementing &&
                    overriddenClosure(redeclaring).any { it in candidate.overridden } &&
                    !candidate.refines(redeclaring, supertypeNamesOf)
            }
            val modality = candidate.member.modality
            // abstract when declared abstract again below by a Java class whose members kotlin-reflect cannot describe,
            // which has no candidate of its own, as its method overriding the member of a built-in type is not converted,
            // see javaMembersOf, like the size() of a Java subclass of java.util.LinkedHashSet, standing for the size of Set
            val implementationModality = lazy(LazyThreadSafetyMode.NONE) {
                val getter = (candidate.member as? Member.Property)?.let { javaClass.javaGetterOf(it.declaration) }
                val reabstracting = (getter?.let { overridingBelow(it, declarations.clazz) } ?: overriding.value)?.takeIf {
                    reflectedValue(default = false) {
                        Modifier.isAbstract(it.modifiers) && !it.declaringClass.isAnnotationPresent(Metadata::class.java)
                    }
                }
                if (reabstracting != null) Modality.ABSTRACT else modality
            }
            // the implementation of a Java class wins over the declaration of a Java interface, also walked before it
            (implementing + redeclared).forEach {
                when {
                    !isJavaInterface -> if (it.implementationModality == null) {
                        it.implementationModality = implementationModality.value
                        it.implementingClass = javaClass.clazz
                        it.implementingJvmMethod = candidate.jvmMethod
                    }
                    modality == Modality.ABSTRACT && it.kmClass.name == ANY_CLASS_NAME -> {}
                    it.interfaceModality == null -> it.interfaceModality = modality
                }
            }
            val unlisted = lazy(LazyThreadSafetyMode.NONE) {
                overriding.value?.let { unlistedOverride(it, unlistedKotlinCandidates) }
            }
            // the JVM-only members standing for the Java method, which does not inherit the enhancement of its return type,
            // if a Java method of the user code, like the computeIfAbsent of a Java subclass of java.util.HashMap,
            // all of them alike, like the spliterator() of Iterable, Collection, and Set, of a Java subclass of java.util.HashSet,
            // not to change which one of them is listed, also of the same JVM descriptor, see standsFor, when the compiler picks
            // the JVM-only member, see keepsKotlinTypes, like the computeIfAbsent of MutableMap for a subclass of java.util.Hashtable
            // and MutableMap, while the JDK method taking platform types is listed otherwise, like putIfAbsent of java.util.Properties
            val standingJvmOnly = jvmOnlyCandidates.filter { jvmOnly ->
                candidate.standsFor(jvmOnly) && (jvmOnly.signature == candidate.signature || jvmOnly.keepsKotlinTypes) &&
                    !candidate.raisesVisibility(jvmOnly)
            }
            if (candidate.returnsPlatformType) standingJvmOnly.forEach { it.standsForPlatformType = true }
            // of the modality of the method of the Java class, which implements them, like the putIfAbsent of java.util.HashMap
            if (!isJavaInterface) {
                standingJvmOnly.filter { it.implementationModality == null }.forEach {
                    it.implementationModality = implementationModality.value
                    it.implementingClass = javaClass.clazz
                }
            }
            val isOverriddenBelow = lazy(LazyThreadSafetyMode.NONE) {
                method.value != null && overriding.value != null && unlisted.value == null
            }
            // by a Kotlin class, unlike a JDK one, like addFirst of java.util.LinkedList the one of java.util.Deque,
            // which the JVM-only member stands for, see standingJvmOnly, whatever else removes it,
            // told only when a JVM-only member competes, see javaMethodSignatures
            if (jvmOnlyCandidates.isNotEmpty() && isOverriddenBelow.value &&
                reflectedValue(default = false) { overriding.value!!.declaringClass.isAnnotationPresent(Metadata::class.java) }
            ) {
                overriddenBelow += candidate
            }
            val isRemoved = implemented.isNotEmpty() ||
                (candidate.overridden.isEmpty() && candidate.overridesHiddenJdkMethod(exposedJdkMethods)) ||
                standingJvmOnly.isNotEmpty() ||
                method.value?.let { overridesHiddenJdkMethod(it) } == true ||
                isOverriddenBelow.value
            // the Java method kept, standing for the visible override of a supertype which is not part of the API,
            // is of its modality, like final, or open of an abstract method
            if (!isRemoved && candidate.implementationModality == null) {
                unlisted.value?.takeUnless { it.isHidden }?.let { candidate.implementationModality = it.member.modality }
            }
            isRemoved
        }
        if (candidates.isEmpty()) return emptyList()
        // the getter of a Java class implementing an abstract Kotlin property is no candidate, being named otherwise,
        // see javaGetterModality
        candidates.forEach { candidate ->
            val property = (candidate.member as? Member.Property)?.declaration
            if (property != null && property.modality == Modality.ABSTRACT && candidate.implementationModality == null &&
                candidate.declarations !is JavaClass && candidate.declarations !is BuiltinClass
            ) {
                candidate.implementationModality = javaGetterModality(property, candidate.declarations.clazz, declarations.clazz)
            }
        }
        // a member standing for the visible override of a supertype which is not part of the API is of its modality as well,
        // of the nearest one, like the f() of a Kotlin interface implemented by an internal class, open,
        // or the run() of java.lang.Runnable implemented by a member with a body of an internal interface,
        // which overridingBelow does not tell, as the compiler generates only a bridge to it in the inheriting class,
        // while the member of a class wins over the ones of the interfaces, whatever the order of the supertypes,
        // also when abstract, like the compiler picks the member of the superclass, and implements with it
        // the member of an interface it does not override, merged with it, like a redeclaration,
        // and so does the member of a public class below, overriding the one of the internal class again, like abstract,
        // which the member of the interface stands for, when listed, or the member of a public class
        // implementing the abstract one of an internal class, merged with the member of an unrelated interface,
        // and over the implementation of a Java class above it, like intValue() of java.util.concurrent.atomic.AtomicInteger,
        // while the method of a Java class below wins over it, like one implementing the abstract member of an internal class,
        // merged with the member of an unrelated interface, open then, or the member of an internal interface, see overrides
        val unlistedOverrides = unlistedKotlinCandidates.filter { !it.isHidden }
        if (unlistedOverrides.isNotEmpty()) {
            val classMembers = candidates.filter { !it.isHidden && it.kmClass.kind != ClassKind.INTERFACE }
            val unlistedClassMembers = classMembers.filter { !it.isApi }
            fun nearest(overriding: List<InheritedCandidate>) =
                overriding.firstOrNull { nearest -> overriding.none { it !== nearest && it.overrides(nearest) } }
            candidates.forEach { candidate ->
                val implementing = candidate.implementingClass
                if (candidate.isHidden || !candidate.isApi || (candidate.implementationModality != null && implementing == null)) {
                    return@forEach
                }
                val isInterfaceMember = candidate.kmClass.kind == ClassKind.INTERFACE
                fun InheritedCandidate.standsFor() =
                    this !== candidate && (overrides(candidate) || (isInterfaceMember && isMergedWith(candidate)))
                // the members of the classes, but only when one of them is not part of the API, as otherwise listed on their own
                val classOverride = if (unlistedClassMembers.none { it.standsFor() }) null else {
                    nearest(classMembers.filter { it.standsFor() })
                }
                val override = if (implementing == null) {
                    classOverride ?: nearest(
                        unlistedOverrides.filter { it.kmClass.kind == ClassKind.INTERFACE && it.overrides(candidate) }
                    )
                } else {
                    classOverride?.takeIf { override ->
                        reflectedValue(default = false) { override.declarations.clazz?.let(implementing::isAssignableFrom) == true }
                    }
                }
                override?.let { candidate.implementationModality = it.member.modality }
            }
        }

        // the declared members override the inherited ones, matched by the overridden keys,
        // or else, for a declared override whose overridden Kotlin members are not told, like of a Java supertype,
        // by the same signature, with the type arguments of the supertypes substituted,
        // and so do the visible members of the other supertypes, like an override declared by an intermediate class,
        // while the ones overridden only by hidden ones are told apart, see below
        // the Java members are overridden by the declared members, or by the ones of the other supertypes, alike,
        // also by a function of the same JVM signature, overriding the JVM method on the JVM, though its metadata does not tell,
        // like `ArrayDeque.addFirst(element: E)` of the standard library, compiled against JDK 8,
        // of `addFirst` of `java.util.List` since JDK 21, see JavaOverrides
        // the members overridden by hidden ones, told first, as they decide which of the visible ones override, see below
        val (hiddenCandidates, visibleCandidates) = candidates.partition { it.isHidden }
        val overriddenByHidden = hiddenCandidates.flatMapTo(HashSet()) { it.overridden }
        val javaOverrides = JavaOverrides()
        val hiddenJavaOverrides = JavaOverrides()
        val hasJavaCandidates = candidates.any { it.jvmMethod != null && !it.isHidden }
        fun collectJavaOverrides(overrides: JavaOverrides, candidates: List<InheritedCandidate>) {
            candidates.filter { it.declarations !is BuiltinClass }.forEach { candidate ->
                (candidate.member as? Member.Function)?.let { function ->
                    val jvmDescriptor = candidate.jvmMethod?.signature?.descriptor?.takeIf { candidate.declarations is JavaClass }
                    overrides.collect(candidate.declarations.overrideOwner, function.declaration, jvmDescriptor, candidate.key)
                }
            }
        }
        if (hasJavaCandidates) collectJavaOverrides(hiddenJavaOverrides, hiddenCandidates)
        fun isOverriddenByHidden(candidate: InheritedCandidate): Boolean = candidate.key in overriddenByHidden ||
            candidate.jvmMethod?.let { hiddenJavaOverrides.overrides(it, candidate.overridden) } == true
        // the visible members of the other supertypes overriding the members below them, and the Java members,
        // but the ones of the supertypes which are not part of the API, which override nothing, see InheritedCandidate.isApi,
        // and the ones overridden by a hidden one in turn, which stand for the members they override only on the supertype
        // declaring the hidden one, which hides them there, see below,
        // like the f() of Mid for `interface Join : Root, Left`, where `Mid : Root` overrides it, and `Left : Mid` as hidden
        val overriding = visibleCandidates.filter { it.isApi && !isOverriddenByHidden(it) }
        val overridden = overriding.flatMapTo(HashSet()) { it.overridden }
        if (hasJavaCandidates) collectJavaOverrides(javaOverrides, overriding)
        // the keys of the Kotlin members overridden by the declared members, and by the visible Kotlin members of the supertypes
        // reached through Kotlin classes only, see reachedThroughKotlin, overriding also the Java method taken as the member
        // in place of the ones it overrides, see acceptsNullUnlike, which the override resolver does not tell,
        // like the `contains(element: String)` of a subclass of java.util.AbstractList<String> the `contains(Object)` of
        // java.util.AbstractCollection, unlike the members of the Kotlin supertypes reached through it, which it takes over
        val kotlinOverridden = overriding.filter { it.declarations !is JavaClass && it.keepsKotlinTypes }
            .flatMapTo(HashSet()) { it.overridden }
        // the signatures of the declared members, collected only when there are Java candidates, which they override
        // when of the same signature as rendered, with the type arguments of the supertypes substituted,
        // though neither the metadata, nor the JVM signature, of the declared function tells, like `addFirst(element: String)`
        // of a subclass of `AbstractMutableList<String>` compiled against JDK 17, taking `String` on the JVM,
        // not the `Object` of the erased `E` of `addFirst` of `java.util.List` since JDK 21,
        // and the signatures of the declared overrides whose overridden members are not told, overriding any candidate alike
        val declaredSignatures = HashSet<String>()
        val untoldOverrideSignatures = HashSet<String>()
        val owner = declarations.overrideOwner
        // also a hidden one, hiding the member it overrides, which cannot be called on the class either
        apiMembersIncludingHidden(kmClass, declarations, memberName, scope).map { it.member }.forEach { member ->
            // the members of a Java class resolved directly, by the primitive types of their JVM methods, see JavaClass.overriddenMembers
            val jvmDescriptor = (member as? Member.Function)?.let { (declarations as? JavaClass)?.jvmDescriptorOf(it.declaration) }
            if (member is Member.Function && hasJavaCandidates) {
                javaOverrides.collect(owner, member.declaration, jvmDescriptor)
            }
            val keys = member.overridden(overrideResolver, owner, jvmDescriptor)
            overridden += keys
            kotlinOverridden += keys
            val isUntoldOverride = keys.isEmpty() && member.isOverride(overrideResolver, owner, jvmDescriptor)
            if (hasJavaCandidates || isUntoldOverride) {
                val signature = member.signature(inheritingTypeParameters.names)
                if (hasJavaCandidates) declaredSignatures += signature
                if (isUntoldOverride) untoldOverrideSignatures += signature
            }
        }
        // a JVM-only member is overridden by a method of a Java class overriding it on the JVM, of the same JVM signature,
        // or of narrower parameter types, through the bridge javac generates, see bridgeSignatures,
        // though the override resolver does not tell, like `addFirst` of a Java subclass of `java.util.ArrayList`
        // the one of `java.util.List` since JDK 21, which K2 sees as the one member, taking the platform type `E!`
        // told only when a JVM-only member competes, not to read the bridges of every Java candidate,
        // the Java candidates by the JVM signatures they override, rendered as overrides, see InheritedCandidate.isJvmOnlyOverride,
        // also the ones overridden below, like the addFirst(Comparable) of a Java subclass of java.util.ArrayList<E>
        // of an `E extends Comparable<E>`, bridged from addFirst(Object), overridden by a Kotlin subclass taking `E?`
        val javaMethodSignatures by lazy(LazyThreadSafetyMode.NONE) {
            (candidates + overriddenBelow).flatMap { candidate ->
                val javaClass = candidate.declarations as? JavaClass
                val function = (candidate.member as? Member.Function)?.declaration
                if (javaClass == null || function == null || candidate.isHidden || !candidate.isApi) return@flatMap emptyList()
                (listOfNotNull(candidate.jvmMethod?.signature) + javaClass.javaMethodOf(function)?.let(::bridgeSignatures).orEmpty())
                    .map { it to candidate }
            }.groupBy({ it.first }, { it.second })
        }
        // a Java method taken as the member is overridden like the Kotlin members it overrides, see kotlinOverridden
        fun isOverriddenAsKotlinMember(candidate: InheritedCandidate) =
            candidate.declarations is JavaClass && candidate.overridden.any { it in kotlinOverridden }
        // the declared signatures, of a Java candidate not hidden, include the ones of the untold overrides
        fun isListable(candidate: InheritedCandidate) =
            !candidate.isHidden && candidate.isApi && candidate.key !in overridden && !isOverriddenAsKotlinMember(candidate) &&
                when (candidate.jvmMethod) {
                    null -> candidate.signature !in untoldOverrideSignatures
                    else -> !javaOverrides.overrides(candidate.jvmMethod, candidate.overridden) &&
                        candidate.signature !in declaredSignatures
                }
        // the JVM-only members overridden by the Java candidates on the JVM, see javaMethodSignatures,
        // which are rendered as overrides then
        val jvmOnlyOverridden = candidates.filterTo(HashSet()) { candidate ->
            candidate.declarations is BuiltinClass && candidate.jvmMethod != null && isListable(candidate) &&
                javaMethodSignatures[candidate.jvmMethod.signature]?.onEach { it.isJvmOnlyOverride = true } != null
        }
        val notOverridden = candidates.filter { isListable(it) && it !in jvmOnlyOverridden }
        // whether the member a wins over the merged member b, of a less specific return type, see compareReturnType,
        // or else the method of a Java class over the one of a Java interface, which it implements in Kotlin,
        // whatever the order of the supertypes, or else the one walked first
        fun isJavaClassMember(candidate: InheritedCandidate) =
            candidate.declarations is JavaClass && candidate.kmClass.kind != ClassKind.INTERFACE
        fun wins(a: InheritedCandidate, aIndex: Int, b: InheritedCandidate, bIndex: Int): Boolean =
            when (val specificity = a.compareReturnType(b, supertypeNamesOf)) {
                null -> false
                0 -> when {
                    a.declarations is JavaClass && b.declarations is JavaClass && isJavaClassMember(a) != isJavaClassMember(b) ->
                        isJavaClassMember(a)
                    else -> aIndex < bIndex
                }
                else -> specificity > 0
            }
        // the member winning over all the others, or none, when some cannot be compared
        fun winner(members: List<InheritedCandidate>): InheritedCandidate? = members.withIndex().firstOrNull { (index, member) ->
            members.withIndex().all { (otherIndex, other) -> other === member || wins(member, index, other, otherIndex) }
        }?.value
        // the index of the first direct supertype of the kmClass reaching the class of each name, see listed
        val directIndices = HashMap<String, Int>()
        directSupertypes[kmClass.name].orEmpty().forEachIndexed { index, direct ->
            val reached = ArrayDeque(listOf(direct))
            while (reached.isNotEmpty()) {
                val name = reached.removeFirst()
                if (name !in directIndices) {
                    directIndices[name] = index
                    reached += directSupertypes[name].orEmpty()
                }
            }
        }
        // the names of the supertypes reached through the superclass of the kmClass, like java.util.Map through java.util.TreeMap,
        // whose members of a body, like the default getOrDefault of Map, the superclass inherits as implementations, see listed
        val reachedThroughSuperclass by lazy(LazyThreadSafetyMode.NONE) {
            val reached = HashSet<String>()
            val pending = ArrayDeque(directSupertypes[kmClass.name].orEmpty().filter { it in classNames }.take(1))
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                if (reached.add(name)) pending += directSupertypes[name].orEmpty()
            }
            reached
        }
        // the compiler groups the members merged with the first one, in the order of the direct supertypes contributing them,
        // like the K2 intersection of the members of the supertypes, then with the first one left, and so on,
        // so that the members merged with one member only, but not with each other, stay apart,
        // like the put(T) of a Java JGen<Integer>, taking Object, merged with the put(t: T) of GenericPut<Int>,
        // which is merged with the put(key: Int) of PrimitivePut, but not the Java one, taking int,
        // for `abstract class Join : JGenOfInteger(), PrimitivePut, GenericPut<Int>`, unlike for `interface Join : GenericPut<Int>, …`
        // each group listing its members of the most specific return type, see wins, which stand for the implementation
        // of a class merged with them, see InheritedCandidate.implementationModality, while the member of an interface implements none,
        // unless one of a body inherited by the superclass, like the default getOrDefault of java.util.Map by java.util.TreeMap,
        // also grouping the Java methods, and the JVM-only members, which a member of the same JVM signature overrides,
        // see javaOverrides, so that they are not listed, though they still implement it, when of a class,
        // like the put(Object) of JGen<Integer>, or the putIfAbsent of java.util.HashMap, see InheritedCandidate.implementingClass,
        // the putIfAbsent(key: K, value: V) of a Kotlin interface given Int and String
        // the first member of the group of each one, see listed, of the members in the order of the direct supertypes contributing them
        fun groupedAroundFirst(ordered: List<InheritedCandidate>): IdentityHashMap<InheritedCandidate, InheritedCandidate> {
            val groups = IdentityHashMap<InheritedCandidate, InheritedCandidate>()
            ordered.forEach { first ->
                if (first in groups) return@forEach
                ordered.filter { it === first || (it !in groups && first.isMergedWith(it)) }.forEach { groups[it] = first }
            }
            return groups
        }
        fun listed(survivors: List<InheritedCandidate>): List<InheritedCandidate> {
            val overriddenOnJvm = candidates.filter { candidate ->
                candidate.jvmMethod != null && !candidate.isHidden && candidate.isApi && candidate !in survivors &&
                    javaOverrides.overrides(candidate.jvmMethod, candidate.overridden)
            }
            val ordered = (survivors + overriddenOnJvm).sortedBy { directIndices[it.name] ?: Int.MAX_VALUE }
            val groups = groupedAroundFirst(ordered)
            val listed = survivors.filterIndexed { index, candidate ->
                survivors.withIndex().none { (rivalIndex, rival) ->
                    rivalIndex != index && groups[rival] === groups[candidate] && wins(rival, rivalIndex, candidate, index)
                }
            }
            listed.filter { it.kmClass.kind == ClassKind.INTERFACE && it.implementationModality == null }.forEach { candidate ->
                ordered.firstNotNullOfOrNull { merged ->
                    merged.takeIf { it !== candidate && groups[it] === groups[candidate] }?.let {
                        when {
                            it.implementingClass != null -> it.implementationModality
                            it.kmClass.kind != ClassKind.INTERFACE -> it.implementationModality ?: it.member.modality
                            // the member of an interface of a body inherited by the superclass, also implementing it
                            it.member.modality == Modality.OPEN && it.name in reachedThroughSuperclass -> Modality.OPEN
                            else -> null
                        }
                    }
                }?.let { candidate.implementationModality = it }
            }
            return listed
        }
        // the compiler merges the members which the direct supertypes contribute, each its own, or the one it inherits,
        // also a hidden one overridden by another direct supertype, and picks the one of the most specific return type,
        // or else the one of the first direct supertype, in the order of declaration, see contribution,
        // so that none of them can be called when the picked one is hidden, like f() of `interface Join : Left, Right`,
        // whose Left hides the f() which Right does not, while the one of Right is picked for `interface Join : Right, Left`
        // the listed member still stands for the picked one, which is visible, like the implementation of an abstract member,
        // declared by an interface of an intermediate class, of `abstract class Square : Shape, SquareBase()`,
        // while the compiler picks the abstract one of Shape, the first direct supertype
        // of the Java methods of the same signature, like put(int) and put(@NotNull Integer), both put(Int), K2 calls the one
        // taking the primitive types, which a Kotlin override overrides, see InheritedCandidate.isShadowedBy,
        // unless a hidden member is picked in its place, see below
        fun List<InheritedCandidate>.withoutCalledInstead() = filter { candidate -> none { it !== candidate && candidate.isShadowedBy(it) } }
        if (candidates.none { it.isHidden }) return listed(notOverridden).withoutCalledInstead()
        val candidatesByClass = candidates.groupBy { it.name }
        val contributions = HashMap<Pair<String, InheritedCandidate>, List<InheritedCandidate>>()
        // the members merged with the listed candidate which the class named name contributes to its subclasses:
        // its own one, or the hidden one overriding it, or else the one picked of the ones its direct supertypes contribute,
        // in the order of declaration, like the hidden f() of Left for `interface Join : Left, Right`, also when Right overrides it,
        // grouped around the first one like the listed ones, see listed, so that a member merged with the candidate,
        // but not with the first one of its group, stays apart, like the hidden put(key: Int) of HiddenPut
        // for `abstract class Join : JGenOfInteger(), HiddenPut, GenericPut<Int>`, whose put(Object) is grouped with put(t: T),
        // or none, or all of them, when none can be picked, as some cannot be compared
        fun contribution(name: String, candidate: InheritedCandidate): List<InheritedCandidate> =
            contributions.getOrPut(name to candidate) {
                val merged = candidatesByClass[name].orEmpty()
                    .filter { it === candidate || it.isMergedWith(candidate) || it.overrides(candidate) }
                    .ifEmpty {
                        // but the members the class overrides, like the put(T) of JRvGen<T> by the put(Integer) of its Java subclass
                        val own = candidatesByClass[name].orEmpty()
                        val picks = directSupertypes[name].orEmpty().map { supertype ->
                            contribution(supertype, candidate).filter { picked -> own.none { it.overrides(picked) } }
                        }
                        picks.firstOrNull { it.size > 1 } ?: picks.flatten().distinct().let { contributed ->
                            val groups = groupedAroundFirst(contributed)
                            val group = groups[candidate]
                                ?: contributed.firstOrNull { it.isMergedWith(candidate) || it.overrides(candidate) }?.let { groups[it] }
                            if (group == null) contributed else contributed.filter { groups[it] === group }
                        }
                    }
                winner(merged)?.let { listOf(it) } ?: merged
            }
        // a member overridden only by hidden ones is still inherited when the compiler picks it,
        // like the visible f() of Root for `interface Join : Root, Left`, whose Left overrides it as hidden,
        // or the run() of java.lang.Runnable for `abstract class Join : Runnable, HiddenRun`, of the same JVM signature,
        // as the hidden member hides only the members it overrides on the class inheriting it through its own supertype
        val survivors = notOverridden.filter { candidate ->
            // or when it picks a visible override of a supertype which is not part of the API, which the member stands for
            !isOverriddenByHidden(candidate) || contribution(kmClass.name, candidate).singleOrNull().let { picked ->
                picked === candidate || (picked != null && !picked.isApi && !picked.isHidden && picked.overrides(candidate))
            }
        }
        return listed(survivors).filter { candidate -> contribution(kmClass.name, candidate).singleOrNull()?.isHidden != true }
            .withoutCalledInstead()
    }

    /**
     * The signatures of the [function], see [Member.signature], as the compiler compares them with the ones of a member of a Java class,
     * see [InheritedCandidate.rivalrySignatures], with the type arguments of the [substitution] substituted,
     * of the variants of the collection types its value parameters declare, see [collectionVariants],
     * of a function of a Java class, the [isJava] one, or else of their one variant, not of the ones substituted,
     * like the `List<String>` of `put(T)` of a `JavaGen<List<String>>`, which is no `MutableList<String>`,
     * with the types of its value parameters not nullable, see [rivalryType],
     * the type parameters of the inheriting class named by the [names], by their ids.
     */
    private fun rivalrySignatures(
        function: KmFunction,
        isJava: Boolean,
        substitution: Map<Int, KmType>,
        names: Map<Int, String>
    ): Set<String> {
        val combinations = function.valueParameters.fold(listOf(emptyList<KmType>())) { combinations, parameter ->
            val variants = if (isJava) parameter.type.collectionVariants() else listOf(parameter.type)
            combinations.flatMap { combination -> variants.map { combination + it } }.take(MAX_RIVALRY_VARIANTS)
        }
        return combinations.mapTo(HashSet()) { types ->
            // by the types themselves, not to replace the element type of a vararg parameter
            val replacements = IdentityHashMap<KmType, KmType>()
            function.valueParameters.forEachIndexed { index, parameter -> replacements[parameter.type] = types[index] }
            val variant = function.withValueParameterTypes { replacements[it] ?: it }
            val substituted = (Member.Function(variant).substituted(substitution, names) as Member.Function).function
            Member.Function(substituted.withValueParameterTypes { it.rivalryType() }).signature(names)
        }
    }

    /**
     * The variants of this type of a value parameter of a member of a Java class, see [rivalrySignatures],
     * of either the read-only or the mutable collection type of each one it refers to, see [MUTABLE_COLLECTION_TYPES],
     * as the `List<String>` of Java is `(Mutable)List<String!>!` of K2, at most [MAX_RIVALRY_VARIANTS] of them.
     */
    private fun KmType.collectionVariants(): List<KmType> {
        val argumentVariants = arguments.fold(listOf(emptyList<KmTypeProjection>())) { combinations, projection ->
            val variants = projection.type?.collectionVariants()?.map { KmTypeProjection(projection.variance, it) }
                ?: listOf(projection)
            combinations.flatMap { combination -> variants.map { combination + it } }.take(MAX_RIVALRY_VARIANTS)
        }
        val name = (classifier as? KmClassifier.Class)?.name
        val readOnlyName = name?.let { READ_ONLY_COLLECTION_TYPES[it] } ?: name
        val classifiers = if (readOnlyName == null || readOnlyName !in MUTABLE_COLLECTION_TYPES) listOf(classifier) else {
            listOf(KmClassifier.Class(readOnlyName), KmClassifier.Class(MUTABLE_COLLECTION_TYPES.getValue(readOnlyName)))
        }
        return classifiers.flatMap { classifier ->
            argumentVariants.map { arguments -> copy(arguments = arguments).also { it.classifier = classifier } }
        }.take(MAX_RIVALRY_VARIANTS)
    }

    /**
     * This type of a value parameter as the compiler compares it with the one of a member of a Java class, see [rivalrySignatures],
     * not nullable, also its type arguments, nor definitely not nullable, with its redundant projections invariant,
     * see [withoutRedundantProjections].
     */
    private fun KmType.rivalryType(): KmType {
        val arguments = arguments.map { projection -> projection.type?.let { KmTypeProjection(projection.variance, it.rivalryType()) } ?: projection }
        return copy(arguments = arguments).also {
            it.isNullable = false
            it.isDefinitelyNonNull = false
        }.withoutRedundantProjections()
    }

    /**
     * This type with the projections of its type arguments, also nested, which the variance of their type parameters
     * makes redundant, made invariant, like the `Collection<out Int>` of a `Collection<? extends Integer>` of Java,
     * the `Collection<Int>` of a Kotlin member for the compiler, as `Collection` is covariant,
     * or this very type, if it has none, see [InheritedCandidate.rivalrySignatures].
     */
    private fun KmType.withoutRedundantProjections(): KmType {
        val name = (classifier as? KmClassifier.Class)?.name ?: return this
        if (arguments.isEmpty()) return this
        val variances by lazy(LazyThreadSafetyMode.NONE) { declaredVariancesOf(name) }
        var isChanged = false
        val projections = arguments.mapIndexed { index, projection ->
            val type = projection.type ?: return@mapIndexed projection
            val invariant = type.withoutRedundantProjections()
            val variance = projection.variance?.takeUnless { it == KmVariance.INVARIANT }
                ?.takeUnless { it == variances.getOrNull(index) }
                ?: KmVariance.INVARIANT
            if (invariant === type && variance == projection.variance) projection else {
                isChanged = true
                KmTypeProjection(variance, invariant)
            }
        }
        return if (isChanged) copy(arguments = projections) else this
    }

    /**
     * The JVM signatures of the bridge methods which javac generates for the Java [method] in its class,
     * of the same name and number of parameters, but of the erased parameter types of a method it overrides,
     * like `addFirst(Ljava/lang/Object;)V` for the `addFirst(String)` of a Java subclass of `java.util.ArrayList<String>`,
     * or for the `addFirst(E)` of a Java class declaring `E extends Comparable<E>`, erased to `Comparable`,
     * read from its class file, also when another method refers to a class missing from the classpath,
     * which fails reflecting the declared methods, or else reflected, or none.
     */
    private fun bridgeSignatures(method: Method): List<JvmMethodSignature> {
        val declaringClass = method.declaringClass
        val bridges = readClassBytes(declaringClass.name)?.let { bytes ->
            try {
                buildList<JvmMethodSignature> {
                    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?
                        ): MethodVisitor? {
                            if (access and Opcodes.ACC_BRIDGE != 0) add(JvmMethodSignature(name, descriptor))
                            return null
                        }
                    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
            } catch (_: Exception) {
                null
            }
        } ?: reflectedValue(default = emptyList()) {
            declaringClass.declaredMethods.filter { it.isBridge }.map { JvmMethodSignature(it.name, AsmType.getMethodDescriptor(it)) }
        }
        val descriptor = AsmType.getMethodDescriptor(method)
        return bridges.filter {
            it.name == method.name && it.descriptor != descriptor &&
                AsmType.getArgumentCount(it.descriptor) == method.parameterCount
        }
    }

    /**
     * The JVM methods which the members of the supertypes override, see [inheritedCandidates]:
     * the [members] they override, by their keys, see [javaMemberKey], and the JVM [signatures] of the overriding functions.
     */
    private inner class JavaOverrides {

        val members = HashSet<String>()

        // the keys of the overriding functions by their JVM signatures, null when not told, like of a declared function
        val signatures = HashMap<JvmMethodSignature, MutableSet<String?>>()

        /**
         * Collect the JVM methods which the [function] of the [owner] overrides, by its JVM signature too,
         * the [function] of the [key], if told, see [overrides].
         */
        fun collect(owner: OverrideResolver.Owner?, function: KmFunction, jvmDescriptor: String? = null, key: String? = null) {
            owner?.let { members += overrideResolver.overriddenJavaMembers(it, function, jvmDescriptor) }
            function.signature?.let { signatures.getOrPut(it) { HashSet() } += key }
        }

        /**
         * Whether the [method] is overridden, as one of the [members], or of the same JVM signature as one of the overriding functions,
         * but the ones of the keys the [method] overrides in turn, the [overridden], like the `put(t: T)` of a Kotlin interface
         * the `put(Object)` of a Java class implementing it, see [InheritedCandidate.overrides].
         */
        fun overrides(method: JvmMethod, overridden: Set<String> = emptySet()): Boolean =
            method.key in members || signatures[method.signature]?.any { it == null || it !in overridden } == true

    }

    /**
     * The names of the classes of these types, like the supertypes of a class,
     * of a suspend function type the one of its own class, like `kotlin/coroutines/SuspendFunction1`, see [withSuspendFunctionTypeClass].
     */
    private fun List<KmType>.classNames(): List<String> =
        mapNotNull { (it.withSuspendFunctionTypeClass().classifier as? KmClassifier.Class)?.name }

    /**
     * The id standing for the type parameter of the [id] of a class inheriting the members of its supertypes,
     * see [inheritedMembers], apart from the ids of the type parameters of the supertypes, and of their members,
     * numbered from 0 within the metadata of each class.
     */
    private fun inheritingTypeParameterId(id: Int): Int = INHERITING_TYPE_PARAMETER_IDS + id

    /**
     * The type arguments of the [supertype] of an inheriting class, see [inheritedMembers],
     * by the ids of the type parameters of its class, the [kmClass], they are given for,
     * and of the ones of its outer classes, if it is an inner class, given in its outer type,
     * like `String` for the `E` of `List<E>`, the supertype `List<String>`,
     * but none for a type parameter whose type argument is not given, like of a raw Java supertype,
     * or refers to a type parameter which is not in scope of the inheriting class, see [inheritingTypeParameterId],
     * like a type parameter of a raw Java supertype, which is left as declared.
     * The outer classes are found like the supertypes of the class of the [declarations], see [inheritedName],
     * as they might be relocated with it, like by shading.
     */
    private fun supertypeSubstitution(kmClass: KmClass, supertype: KmType, declarations: ClassDeclarations): Map<Int, KmType> = buildMap {
        var declaration: KmClass? = kmClass
        var type: KmType? = supertype
        while (declaration != null && type != null) {
            declaration.typeParameters.forEachIndexed { index, typeParameter ->
                type.arguments.getOrNull(index)?.type
                    ?.takeIf { argument -> argument.refersOnlyTo { it >= INHERITING_TYPE_PARAMETER_IDS } }
                    ?.let { put(typeParameter.id, it) }
            }
            if (!declaration.isInner) break
            // like a Java class, see JavaClass
            declaration = inheritedDeclarations(inheritedName(declarations, declaration.name.substringBeforeLast('.')))?.kmClass
            type = type.outerType
        }
    }

    /**
     * The name the supertype of the [metadataName], of the class of the [declarations], or of its supertypes, is found by,
     * see [inheritedDeclarations]: its located name, when relocated with the class, or as a dependency of it, like by shading,
     * see [OverrideResolver.locatedName], or else the [metadataName], if the class of the located name cannot be described,
     * like when collecting the members the class might override, see [OverrideResolver.locatedName],
     * but the located name of a class which neither can be described, unless absent from the classpath,
     * so that its supertypes are walked through from its class file, see [rawJavaSupertypes].
     */
    private fun inheritedName(declarations: ClassDeclarations, metadataName: String): String {
        val located = declarations.overrideOwner?.let { overrideResolver.locatedName(it, metadataName) } ?: return metadataName
        // the functions of a relocated Java class which kotlin-reflect does not tell, like of one implementing a relocated Kotlin interface,
        // are converted from the Java reflection, see reflectedJavaClass, so that its members are the ones of the class extended,
        // not the ones of an original class next to it, which might be of another version
        return when {
            inheritedDeclarations(located) != null -> located
            inheritedDeclarations(metadataName) != null || isAbsent(located) -> metadataName
            else -> located
        }
    }

    /**
     * The name the Kotlin metadata refers to the class of the [jvmName] by, a supertype of the class of the [declarations],
     * or of its supertypes, see [OverrideResolver.unlocatedName].
     */
    private fun unlocatedName(declarations: ClassDeclarations, jvmName: String): String =
        declarations.overrideOwner?.let { overrideResolver.unlocatedName(it, jvmName) } ?: jvmName

    /**
     * This type, of a Java class described by kotlin-reflect, see [JavaClass], with its classifier,
     * and the ones of its type arguments, and of its outer type, named by the names the Kotlin metadata refers to them by,
     * see [unlocatedName], or this very type, if none is relocated.
     */
    private fun KmType.unlocated(declarations: ClassDeclarations): KmType {
        val jvmName = (classifier as? KmClassifier.Class)?.name
        val metadataName = jvmName?.let { unlocatedName(declarations, it) }
        val unlocatedArguments = arguments.map { argument ->
            val type = argument.type ?: return@map argument
            type.unlocated(declarations).takeIf { it !== type }?.let { KmTypeProjection(argument.variance, it) } ?: argument
        }
        val unlocatedOuter = outerType?.unlocated(declarations)
        if (metadataName == jvmName && unlocatedOuter === outerType &&
            unlocatedArguments.indices.all { unlocatedArguments[it] === arguments[it] }
        ) return this
        return copy(arguments = unlocatedArguments, outerType = unlocatedOuter).also { type ->
            if (metadataName != null && metadataName != jvmName) type.classifier = KmClassifier.Class(metadataName)
        }
    }

    /**
     * The declarations of the supertype named like `java/util/ArrayList`, whose members a class inherits, see [inheritedMembers]:
     * the ones of a Kotlin class, or of a built-in type, see [classDeclarations],
     * or the ones of a Java class, as kotlin-reflect describes it, see [JavaClass],
     * or of a function type class, see [functionTypeClassDeclarations],
     * or `null` if the class cannot be described.
     */
    private fun inheritedDeclarations(name: String): ClassDeclarations? {
        val declarations = classDeclarations(name)
        if (declarations?.kmClass != null) return declarations
        return (declarations as? FoundClass)?.javaDeclarations ?: functionTypeClassDeclarations(name)
    }

    /**
     * The declarations of the function type class named like `kotlin/Function1`, see [functionTypeClassDeclaration],
     * a supertype like a built-in type, whose `invoke` operator is inherited, or `null` if it is not one.
     */
    private fun functionTypeClassDeclarations(name: String): ClassDeclarations? =
        functionTypeClasses.getOrPut(name) { functionTypeClassDeclaration(name)?.let { BuiltinClass(name, it) } ?: return null }

    // the declarations of the function type classes, see functionTypeClassDeclarations, built once
    private val functionTypeClasses = ConcurrentHashMap<String, ClassDeclarations>()

    /**
     * The direct supertypes of the Java class named like `com/example/Base`, whose members cannot be described,
     * see [inheritedDeclarations], as raw types, without their type arguments, mapped to the built-in types:
     * the ones of its JVM class, if loaded, named like [javaSupertypeNames] does,
     * but with the type arguments of a generic one which does not refer to a type variable, see [concreteJavaSupertype],
     * like `Outer<Event>.Inner` of a Java class kotlin-reflect cannot describe, like a relocated one,
     * or else the ones its class file declares, see [OverrideResolver.unloadableJavaSupertypeNames],
     * given by the [named] of these types.
     */
    private fun rawJavaSupertypes(name: String, named: (KmType) -> KmType): List<KmType> {
        val javaClass = findClassByMetadataName(name)?.clazz
            ?: return overrideResolver.unloadableJavaSupertypeNames(name).map { named(classType(it)) }
        val genericSupertypes = reflectedValue(default = emptyList()) {
            listOfNotNull(javaClass.genericSuperclass) + javaClass.genericInterfaces
        }.filterIsInstance<ParameterizedType>()
        return javaSupertypeClasses(javaClass).map { (supertype, metadataName) ->
            val generic = genericSupertypes.firstOrNull { it.rawType == supertype }?.let { concreteJavaSupertype(it, metadataName) }
            named(generic ?: classType(metadataName))
        }
    }

    /**
     * The [genericSupertype] of a Java class, named [metadataName], converted from the Java reflection, see [toKmJavaType],
     * or `null` if it refers to a type variable, like of the Java class, which is not substituted when walked through,
     * see [rawJavaSupertypes], or one of its classes cannot be reflected.
     */
    private fun concreteJavaSupertype(genericSupertype: ParameterizedType, metadataName: String): KmType? {
        fun JavaType.refersToTypeVariable(): Boolean = when (this) {
            is TypeVariable<*> -> true
            is ParameterizedType -> actualTypeArguments.any { it.refersToTypeVariable() } || ownerType?.refersToTypeVariable() == true
            is GenericArrayType -> genericComponentType.refersToTypeVariable()
            is WildcardType -> (upperBounds + lowerBounds).any { it.refersToTypeVariable() }
            else -> false
        }
        return reflectedValue(default = null) {
            if (genericSupertype.refersToTypeVariable()) return@reflectedValue null
            genericSupertype.toKmJavaType(ReflectedTypeParameters(emptyMap())).also { it.classifier = KmClassifier.Class(metadataName) }
        }
    }

    /**
     * Whether the type [a] is more specific than the type [b], a positive number, or less, a negative one,
     * or equally, `0`, like the same class of the same nullability, or two type parameters,
     * or `null` if they cannot be compared, as told by their classifiers, and their nullability, not by their type arguments,
     * the classifiers related also through a Java class, like `java.util.ArrayList`, a subtype of `MutableList`,
     * as told by the names of their supertypes, given by the [supertypeNamesOf] their metadata names, see [supertypeNames].
     *
     * @param ignoreNullability whether the same classes are equally specific regardless of their nullability,
     *   like a platform type of Java, like `String!`, compared with `String?`.
     */
    private fun compareSpecificity(
        a: KmType,
        b: KmType,
        supertypeNamesOf: (String) -> Set<String>,
        ignoreNullability: Boolean = false
    ): Int? {
        val aClassifier = a.classifier
        val bClassifier = b.classifier
        if (aClassifier is KmClassifier.TypeParameter && bClassifier is KmClassifier.TypeParameter) return 0
        if (aClassifier !is KmClassifier.Class || bClassifier !is KmClassifier.Class) return null
        if (aClassifier.name == bClassifier.name) return when {
            ignoreNullability || a.isNullable == b.isNullable -> 0
            b.isNullable -> 1
            else -> -1
        }
        fun isSubtype(name: String, of: String): Boolean = of == ANY_CLASS_NAME || of.replace('/', '.') in supertypeNamesOf(name)
        return when {
            isSubtype(aClassifier.name, bClassifier.name) -> 1
            isSubtype(bClassifier.name, aClassifier.name) -> -1
            else -> null
        }
    }

    /**
     * The extensions listed for a class, see [listedExtensions].
     */
    private class ListedExtensions(val functions: List<IndexedExtension>, val properties: List<IndexedExtensionProperty>)

    /**
     * The extensions listed for the [kmClass], named [className], on the class and on its supertypes.
     *
     * For a built-in type, or its companion object, when [isBuiltin],
     * only the ones of the public `kotlin.*` packages, see [isBuiltinExtensionSource],
     * on the class and on all its supertypes, except `Any`, also the ones from the `kotlin.*` packages,
     * whose extensions are, after all, the ones of the standard library,
     * but the ones of any library on the companion object itself, like `fun Int.Companion.serializer()`
     * of kotlinx.serialization, as there are few of them, each extending that very type.
     * For any other class, all of them, on the class and its [userSupertypes],
     * not to flood its listing with the generic extensions of the standard library,
     * but the ones of the standard library on `Comparable` for a class comparable to itself, see [isComparableToItself],
     * and the ones of its package on any of its supertypes, like `fun List<Order>.total()` for `interface Orders : List<Order>`,
     * when the type arguments of the supertype are told, see [ClassSupertypes.types], on `Comparable` only for a comparable class,
     * also an enum class, see [isComparable], and for a class of the `kotlin.*` or `java.*` packages only on `Comparable`.
     * Either way, the ones on a type parameter with multiple bounds only when the class satisfies all of them,
     * see [receiverBoundsOf], also when none of them is among the [userSupertypes], see [listedFor],
     * and only the ones which can be called on the class, as told by the type arguments, see [isCallableOn],
     * also the ones on its companion object, see [extendedCompanionOf], telling its deprecation, see [withCompanionDeprecation],
     * and never the ones on a type parameter shadowed by a more specific overload, see [withoutShadowed],
     * nor the ones called on the name of an object, on an instance of a class other than a built-in type,
     * whose members shadow the extensions of its body, see [bodyExtensionsOf], or on the companion object,
     * shadowed by its members, see [withoutShadowedByMembers].
     *
     * @param apiClass the [kmClass] as a class of the API.
     * @param declarations the declarations of the [kmClass], telling its members, which shadow the extensions.
     * @param memberName the name of the only extensions to tell, like for [resolveMember], or `null` for all of them,
     *   filtered by name first, not to tell whether each of the others can be called,
     *   nor anything of the class, when no extension is named so, see [extensionNames], as for most of its members.
     */
    @OptIn(ExperimentalContextParameters::class)
    private fun listedExtensions(
        kmClass: KmClass,
        className: String,
        apiClass: ApiClass,
        declarations: ClassDeclarations,
        isBuiltin: Boolean,
        memberName: String? = null
    ): ListedExtensions {
        if (memberName != null && memberName !in extensionNames) return ListedExtensions(emptyList(), emptyList())
        val companion = extendedCompanionOf(kmClass, className, apiClass, declarations)
        val companionName = companion?.name
        val companionReceiverNames = listOfNotNull(companionName?.let { "$className.$it" })
        val supertypes = supertypesOf(kmClass)
        val listed = if (isBuiltin) {
            val supertypeNames = supertypes.names - "kotlin.Any"
            val listed = extensionsListedFor(
                listOf(className) + supertypeNames + companionReceiverNames, supertypeNames + className
            )
            // a library extends the companion object of a built-in type only deliberately, like with serializer()
            val companionNames = setOfNotNull(
                kmClass.name.takeIf { kmClass.kind == ClassKind.COMPANION_OBJECT },
                companionName?.let { "${kmClass.name}.$it" }
            )
            fun isListed(isBuiltinExtension: Boolean, source: String, receiver: KmType?) = isBuiltinExtension ||
                (!isInternalPackageSource(source) && (receiver?.classifier as? KmClassifier.Class)?.name in companionNames)
            ListedExtensions(
                listed.functions.filter { isListed(it.isBuiltinExtension, it.source, it.function.receiverParameterType) },
                listed.properties.filter { isListed(it.isBuiltinExtension, it.source, it.property.receiverParameterType) }
            )
        } else {
            val ownReceiverNames = ownExtensionReceiverNames(className)
            val satisfiedBounds = ownReceiverNames.toSet() + supertypes.names
            val userSupertypes = userSupertypes(kmClass)
            val listed = extensionsListedFor(ownReceiverNames + userSupertypes + companionReceiverNames, satisfiedBounds)
            // also the ones of an object of the package, imported from it, told by the JVM package of their class,
            // like the one of the class
            val classPackage = contextPackageOf(declarations, kmClass)
            // the other supertypes, from the kotlin.* and java.* packages, whose extensions are listed only
            // when of the package of the class, like next to it, but not the ones of the other libraries,
            // like fun List<Order>.total() for interface Orders : List<Order>, also on Any,
            // which an interface extending only Java interfaces does not reach, like interface Task : Runnable,
            // and on Comparable, only for a comparable class, see isComparable,
            // also the ones of the standard library for a class comparable to itself,
            // unless the class is of these packages itself, whose extensions are the ones of the standard library,
            // listed then only on Comparable, but never for an enum class, see isComparableToItself,
            // like Comparable<T>.compareTo of the kotlin package for kotlin.DeprecationLevel,
            // while the ones with multiple bounds, satisfied by the class, are already listed
            val otherSupertypes = when {
                isOfStandardPackage(classPackage) -> listOf(COMPARABLE_CLASS_NAME)
                else -> (supertypes.names + ANY_RECEIVER_KEY).filter { it !in userSupertypes }
            }.filter { it != COMPARABLE_CLASS_NAME || supertypes.isComparable }
            val isComparableToItself = supertypes.isComparableToItself
            val isEnum = kmClass.kind == ClassKind.ENUM_CLASS
            fun ReceiverBounded.isListedOn(supertype: String): Boolean {
                val isOfClassPackage = sourceClass.substringBeforeLast('.', "") == classPackage
                val isListed = when {
                    supertype != COMPARABLE_CLASS_NAME || !isBuiltinExtension -> isOfClassPackage
                    else -> isComparableToItself || (isOfClassPackage && !isEnum)
                }
                return isListed && receiverBounds.all { it in satisfiedBounds }
            }
            // a supertype taking type arguments only when they are told, see ClassSupertypes.types,
            // like not the Collection<E> of a raw java.util.ArrayList which a Java supertype extends,
            // which an extension on Collection<Order> would be told callable on, see isCallableOn
            fun isTold(supertype: String, receiver: KmType?, typeParameters: List<KmTypeParameter>): Boolean {
                if (supertype in supertypes.types) return true
                val receiverTypes = when (val classifier = receiver?.classifier) {
                    is KmClassifier.TypeParameter -> receiverBoundTypes(classifier.id, typeParameters)
                    else -> listOfNotNull(receiver)
                }
                return receiverTypes.all { classReceiverIndexKey(it) != supertype || it.arguments.isEmpty() }
            }
            // but not, beyond Comparable, one of a type parameter bounded recursively, like T : Comparable<T>,
            // matched against a supertype referring to the type parameters of the class, like Map<V, Comparable<V>>,
            // whose bounds against the types a type parameter of the class takes are not told, see isClassTypeParameterTyped
            fun isRecursivelyBoundedOnTypeParameters(supertype: String, typeParameters: List<KmTypeParameter>): Boolean =
                supertype != COMPARABLE_CLASS_NAME &&
                    typeParameters.any { typeParameter ->
                        typeParameter.upperBounds.any { bound -> !bound.refersOnlyTo { it != typeParameter.id } }
                    } &&
                    supertypes.types[supertype].orEmpty().any { !it.refersOnlyTo { false } }
            // unless it can be called on the class of some types given for its type parameters, see instantiated,
            // like on a HashMap<String, String>, of a class K<V> : HashMap<V, V>(), a Map<T, T> of T : Comparable<T>
            val instantiated by lazy(LazyThreadSafetyMode.NONE) { supertypes.instantiated() }
            // filtered by name first, like below, not to tell whether each of the others is listed
            fun <E : ReceiverBounded> Map<String, List<E>>.listedOnOtherSupertypes(
                declarationOf: (E) -> Triple<String, KmType?, List<KmTypeParameter>>,
                isCallableOn: ClassSupertypes.(E) -> Boolean
            ): List<E> = otherSupertypes.flatMap { supertype ->
                this[supertype].orEmpty().filter { extension ->
                    val (name, receiver, typeParameters) = declarationOf(extension)
                    (memberName == null || name == memberName) &&
                        extension.isListedOn(supertype) && isTold(supertype, receiver, typeParameters) &&
                        (!isRecursivelyBoundedOnTypeParameters(supertype, typeParameters) || instantiated.any { it.isCallableOn(extension) })
                }
            }
            ListedExtensions(
                (listed.functions + extensionIndex.listedOnOtherSupertypes(
                    { Triple(it.function.name, it.function.receiverParameterType, it.function.typeParameters) },
                    { isCallableOn(it.function) }
                )).distinct(),
                (listed.properties + extensionPropertyIndex.listedOnOtherSupertypes(
                    { Triple(it.property.name, it.property.receiverParameterType, it.property.typeParameters) },
                    { isCallableOn(it.property) }
                )).distinct()
            )
        }
        val callable = ListedExtensions(
            listed.functions.filter { extension ->
                (memberName == null || extension.function.name == memberName) && supertypes.isCallableOn(extension.function)
            },
            listed.properties.filter { extension ->
                (memberName == null || extension.property.name == memberName) && supertypes.isCallableOn(extension.property)
            }
        )
        val builtinClass = declarations as? BuiltinClass
        val unshadowed = callable.withoutShadowed(supertypes) { names ->
            // a JVM-only function is a Java method, see isShadowingJavaMethod
            (kmClass.functions + builtinClass?.jvmOnlyFunctions.orEmpty().filter { it.isShadowingJavaMethod })
                .filter { it.name in names && it.receiverParameterType == null }
                .inApi(apiClass, declarations) +
                inheritedFunctionsNamed(kmClass, declarations, names, apiClass)
        }
        // called on the name of an object, like Obj.ext(), on an instance of the class, like foo.ext(),
        // or on the name of the class whose companion object it extends, like Foo.of(),
        // the companion object looked up only when one of them is called on it
        val unshadowedByMembers = when {
            kmClass.kind == ClassKind.OBJECT || kmClass.kind == ClassKind.COMPANION_OBJECT ->
                unshadowed.withoutShadowedByMembers(kmClass.name, { _, _ -> true }, { supertypes }) { name ->
                    publicMembersNamed(kmClass, declarations, name, apiClass)
                }
            else -> {
                val companionClassName = companion?.let { "${kmClass.name}.${it.name}" }
                val isOnCompanion = { receiver: KmType? ->
                    companionClassName != null && (receiver?.classifier as? KmClassifier.Class)?.name == companionClassName
                }
                // but the ones which can be called on a nullable receiver, like nullableFoo.ext(), where no member can
                val isOnInstance = { receiver: KmType?, typeParameters: List<KmTypeParameter> ->
                    !isOnCompanion(receiver) && isNeverNullable(receiver, typeParameters)
                }
                val unshadowedOnInstance = if (isBuiltin) unshadowed else {
                    unshadowed.withoutShadowedByMembers(kmClass.name, isOnInstance, { supertypes }) { name ->
                        publicMembersNamed(kmClass, declarations, name, apiClass)
                    }
                }
                if (companionClassName == null) unshadowedOnInstance else {
                    val apiCompanion by lazy(LazyThreadSafetyMode.NONE) { apiCompanionOf(kmClass, declarations, apiClass) }
                    val companionSupertypes = { apiCompanion?.let { supertypesOf(it.kmClass) } }
                    unshadowedOnInstance.withoutShadowedByMembers(
                        companionClassName, { receiver, _ -> isOnCompanion(receiver) }, companionSupertypes
                    ) { name ->
                        apiCompanion?.let { publicMembersNamed(it.kmClass, it.foundClass, name, it.apiClass) }.orEmpty()
                    }
                }
            }
        }
        val companionDeprecation = companion?.deprecation ?: return unshadowedByMembers
        return unshadowedByMembers.withCompanionDeprecation("${kmClass.name}.${companion.name}", companionDeprecation)
    }

    /**
     * These extensions without the ones called on the name of an object, like `Obj.ext()`,
     * on an instance of a class, like `foo.ext()`, or on the name of the class whose companion object it is, like `Foo.ext()`,
     * told by their receivers, see [isCalledOnClass],
     * shadowed by a member of the class, given by [membersNamed] their name, which Kotlin always calls instead,
     * reporting an extension on the class itself as shadowed by the member:
     * an extension property by a member property of the same name, unless it has a context parameter, see [isReadAnywhere],
     * and an extension function by a member function accepting its every call, see [shadowsAsMember],
     * or by a member property of a function type, see [isInvokedInsteadOf],
     * unless the extension hides the members, see [IndexedExtension.hidesMembers].
     *
     * @param className the name of the class, like `com/example/Foo.Companion`, which the type parameter of the receiver
     *   of an extension stands for.
     * @param classSupertypes the supertypes of the class, telling the types an extension is called with,
     *   see [shadowsAsMember], looked up only when asked, or `null` if the class is not found.
     */
    private fun ListedExtensions.withoutShadowedByMembers(
        className: String,
        isCalledOnClass: (receiver: KmType?, typeParameters: List<KmTypeParameter>) -> Boolean,
        classSupertypes: () -> ClassSupertypes?,
        membersNamed: (name: String) -> List<Member>
    ): ListedExtensions {
        val functions = functions.filter {
            !it.hidesMembers && isCalledOnClass(it.function.receiverParameterType, it.function.typeParameters)
        }
        val properties = properties.filter { isCalledOnClass(it.property.receiverParameterType, it.property.typeParameters) }
        if (functions.isEmpty() && properties.isEmpty()) return this
        val members = (functions.map { it.function.name } + properties.map { it.property.name }).toSet().associateWith(membersNamed)
        val supertypes by lazy(LazyThreadSafetyMode.NONE, classSupertypes)
        val shadowedFunctions = functions.filterTo(HashSet()) { extension ->
            members.getValue(extension.function.name).any { member ->
                when (member) {
                    is Member.Function -> member.function.shadowsAsMember(extension.function, className) { supertypes }
                    is Member.Property -> member.property.isInvokedInsteadOf(extension.function)
                }
            }
        }
        val shadowedProperties = properties.filterTo(HashSet()) { extension ->
            members.getValue(extension.property.name).any { it is Member.Property && it.property.isReadAnywhere }
        }
        return ListedExtensions(
            this.functions.filter { it !in shadowedFunctions },
            this.properties.filter { it !in shadowedProperties }
        )
    }

    /**
     * Whether the [receiver] of an extension, declaring the [typeParameters], is never `null`,
     * so that a member of its class can always be called on it: neither a nullable type, like `Foo?`,
     * nor a type parameter which can be inferred as one, like `T : Foo?`, also through a type parameter bounding it,
     * unless definitely not nullable, like `T & Any`.
     */
    private fun isNeverNullable(
        receiver: KmType?,
        typeParameters: List<KmTypeParameter>,
        visited: MutableSet<Int> = HashSet()
    ): Boolean {
        if (receiver == null || receiver.isNullable) return false
        return when (val classifier = receiver.classifier) {
            is KmClassifier.Class -> true
            is KmClassifier.TypeParameter -> receiver.isDefinitelyNonNull || (visited.add(classifier.id) &&
                typeParameters.firstOrNull { it.id == classifier.id }?.upperBounds.orEmpty().any {
                    isNeverNullable(it, typeParameters, visited)
                })
            is KmClassifier.TypeAlias -> false
        }
    }

    /**
     * Whether this member function of the class named [className], like `com/example/Foo.Companion`,
     * is always called instead of the [extension] called on the class, see [withoutShadowedByMembers],
     * like `fun shadow()` of `companion object : Bar` instead of `fun Bar.shadow()` for `Foo.shadow()`:
     * the value parameters of the [extension] have neither a default value, nor a vararg, which this function could not be called with,
     * while this function takes as many value parameters, of the same names, as they can be named in a call,
     * each one accepting every argument of the parameter of the [extension]:
     * of a type parameter of its own, bounded only by `Any?`, inferred then as the type of the argument, like `x: T` for `x: Int`,
     * while one no parameter is of does not matter, as Kotlin calls the member also when it cannot infer it,
     * or of the same type, see [isSameType], also nullable, like `x: Int?` for `x: Int`,
     * where the type parameter of the receiver of the [extension] stands for the class, when pinned to it, see [pins],
     * or else of a class without type arguments which the class of the parameter of the [extension] is a subclass of,
     * like `x: CharSequence` for `x: String`,
     * while a type parameter of the [extension] is accepted like any of its bounds, as the argument is a subtype of each of them,
     * like `x: Bar` for `x: T` of `fun <T : Bar> T.ext(x: T)`, where `T` can be inferred as a supertype of the class,
     * unlike `x: Obj`, which `Obj.ext(other)` does not accept,
     * and like the type argument of the supertype of the class which is the receiver of the [extension],
     * see [receiverTypeArguments], like `x: String` for `x: T` of `fun <T> Base<T>.ext(x: T)` for `object Obj : Base<String>`.
     * It has no context parameter, and it is an operator, or infix, if the [extension] is, as only then it is called the same way,
     * also when it is suspend, called then also outside a coroutine, failing to compile.
     * An [extension] with a type parameter which only a type argument given explicitly tells, see [hasExplicitTypeParameter],
     * like `fun <R> Bar.ext()`, called as `Obj.ext<Int>()`, is shadowed only by a member taking as many type arguments,
     * whatever they are, neither bounded, nor the types of its value parameters, like `fun <T> ext()`,
     * as Kotlin calls the extension when the member does not take them, like `fun ext()`, `fun <T : Number> ext()`,
     * or `fun <T> ext(x: T)` next to `fun <R> Bar.ext(x: Int)`, called as `Obj.ext<String>(1)`.
     * Not told is a call giving explicitly the type arguments which are inferred otherwise, which reaches the [extension],
     * like `Obj.ext<Bar>()` of `fun <T : Bar> T.ext()` next to a member `ext()`.
     *
     * @param supertypes the supertypes of the class, looked up only when asked, or `null` if the class is not found.
     */
    @OptIn(ExperimentalContextParameters::class)
    private fun KmFunction.shadowsAsMember(extension: KmFunction, className: String, supertypes: () -> ClassSupertypes?): Boolean {
        if (extension.valueParameters.any { it.declaresDefaultValue || it.varargElementType != null }) return false
        if (contextParameters.isNotEmpty() || (extension.isOperator && !isOperator) || (extension.isInfix && !isInfix)) return false
        if (valueParameters.size != extension.valueParameters.size) return false
        // the ones inferred as the type of any argument
        val inferred = typeParameters.filter { typeParameter -> typeParameter.upperBounds.all { it.isNullableAny() } }
            .mapTo(HashSet()) { it.id }
        if (extension.hasExplicitTypeParameter) {
            val takesAnyTypeArguments = typeParameters.size == extension.typeParameters.size && inferred.size == typeParameters.size &&
                valueParameters.all { parameter -> parameter.type.refersOnlyTo { it !in inferred } }
            if (!takesAnyTypeArguments) return false
        }
        val pinnedId by lazy(LazyThreadSafetyMode.NONE) {
            val id = (extension.receiverParameterType?.classifier as? KmClassifier.TypeParameter)?.id
            extension.typeParameters.firstOrNull { it.id == id }?.takeIf { supertypes()?.pinsFinal(it) == true }?.id ?: -1
        }
        val receiverTypeArguments by lazy(LazyThreadSafetyMode.NONE) { supertypes()?.receiverTypeArguments(extension).orEmpty() }
        fun isSubclass(name: String, of: String) = name == of || of == ANY_CLASS_NAME ||
            supertypes()?.supertypeNamesOf(name)?.contains(of.replace('/', '.')) == true
        fun accepts(type: KmType, argument: KmType, visited: MutableSet<Int>): Boolean {
            val classifier = type.classifier
            if (classifier is KmClassifier.TypeParameter) return classifier.id in inferred && !type.isDefinitelyNonNull
            if (classifier !is KmClassifier.Class || (argument.isNullable && !type.isNullable)) return false
            if (isSameType(argument, if (argument.isNullable) type else type.notNullable(), pinnedId, className)) return true
            return when (val argumentClassifier = argument.classifier) {
                is KmClassifier.Class -> type.arguments.isEmpty() && isSubclass(argumentClassifier.name, classifier.name)
                is KmClassifier.TypeParameter -> {
                    val typeParameter = extension.typeParameters.firstOrNull { it.id == argumentClassifier.id }
                    if (typeParameter == null || !visited.add(typeParameter.id)) return false
                    val bounds = (listOfNotNull(receiverTypeArguments[typeParameter.id]) + typeParameter.upperBounds)
                        .ifEmpty { listOf(classType(ANY_CLASS_NAME, nullable = true)) }
                    bounds.any { bound ->
                        accepts(type, if (argument.isNullable) bound.copy().also { it.isNullable = true } else bound, visited)
                    }
                }
                is KmClassifier.TypeAlias -> false
            }
        }
        return valueParameters.zip(extension.valueParameters).all { (parameter, shadowed) ->
            parameter.name == shadowed.name && parameter.varargElementType == null &&
                accepts(parameter.type, shadowed.type, HashSet())
        }
    }

    /**
     * Whether this member property of a class is always invoked instead of the [extension] function of its name
     * called on the class, see [withoutShadowedByMembers], like `val run: () -> Unit` instead of `fun Bar.run()` for `Obj.run()`:
     * a property of a function type without parameters, neither nullable, nor suspend, nor with a receiver,
     * read anywhere, see [isReadAnywhere], next to an extension without value parameters, which is no operator,
     * nor called with type arguments given explicitly, see [hasExplicitTypeParameter], which a function type does not take,
     * while a function type with parameters does not take the named arguments of the [extension],
     * like `Obj.run(x = 1)` of `fun Bar.run(x: Int)` next to `val run: (Int) -> Unit`.
     */
    private fun KmProperty.isInvokedInsteadOf(extension: KmFunction): Boolean =
        extension.valueParameters.isEmpty() && !extension.isOperator && !extension.hasExplicitTypeParameter && isReadAnywhere &&
            !returnType.isNullable && !returnType.isSuspend && returnType.arguments.size == 1 &&
            (returnType.classifier as? KmClassifier.Class)?.name == "kotlin/Function0"

    /**
     * The types which the type parameters of the [extension] stand for, or are subtypes of, by their ids,
     * when called on the class: the type arguments of the supertype of the class which is the receiver of the [extension],
     * told by the Kotlin metadata, see [ClassSupertypes.types], reached through a single path, and referring to no type parameter,
     * like `String` of `T` of `fun <T> Base<T>.ext(x: T)` for `object Obj : Base<String>`,
     * each one in an invariant position, which the type parameter can only be inferred as,
     * or in a contravariant one, like of `interface Consumer<in T>`, where it can only be inferred as its subtype,
     * unlike in a covariant one, where it can be inferred as any supertype, like `Any` for `Obj.ext(1)`.
     */
    private fun ClassSupertypes.receiverTypeArguments(extension: KmFunction): Map<Int, KmType> {
        val receiver = extension.receiverParameterType ?: return emptyMap()
        val classifier = receiver.classifier as? KmClassifier.Class ?: return emptyMap()
        if (receiver.arguments.isEmpty()) return emptyMap()
        val supertype = types[classReceiverIndexKey(receiver)]?.singleOrNull() ?: return emptyMap()
        val declaredVariances = declaredVariances(classifier.name)
        if (declaredVariances?.size != receiver.arguments.size || supertype.arguments.size != receiver.arguments.size) {
            return emptyMap()
        }
        return buildMap {
            receiver.arguments.forEachIndexed { index, argument ->
                val argumentType = argument.type ?: return@forEachIndexed
                val id = (argumentType.classifier as? KmClassifier.TypeParameter)?.id ?: return@forEachIndexed
                val variance = argument.variance?.let { projectedVariance(declaredVariances[index], it) }
                val supertypeArgument = supertype.arguments[index]
                val type = supertypeArgument.type ?: return@forEachIndexed
                if (!argumentType.isNullable && (variance == KmVariance.INVARIANT || variance == KmVariance.IN) &&
                    supertypeArgument.variance == KmVariance.INVARIANT && type.flexibleTypeUpperBound == null &&
                    type.refersOnlyTo { false }
                ) {
                    putIfAbsent(id, type)
                }
            }
        }
    }

    /**
     * Whether the [typeParameter] of the receiver of an extension is pinned to the class, see [pins],
     * a final one without type parameters, like an object, as it can be inferred as a subclass,
     * or as the class with other type arguments, otherwise.
     */
    private fun ClassSupertypes.pinsFinal(typeParameter: KmTypeParameter): Boolean =
        kmClass.modality == Modality.FINAL && kmClass.typeParameters.isEmpty() && pins(typeParameter)

    /**
     * The public members named [name] of the [kmClass], declared, see [apiMembersNamed], and inherited, see [inheritedCandidates],
     * which shadow an extension called on it, see [withoutShadowedByMembers],
     * unlike a protected one, as Kotlin calls the extension where the member cannot be used,
     * a member extension, and a method taking parameters of a Java supertype, or a JVM-only one of a built-in supertype,
     * see [isShadowingJavaMethod], like [inheritedFunctionsNamed].
     */
    private fun publicMembersNamed(kmClass: KmClass, declarations: ClassDeclarations, name: String, apiClass: ApiClass): List<Member> =
        (apiMembersNamed(kmClass, declarations, name, apiClass) +
            inheritedCandidates(kmClass, declarations, name, apiClass, hidingExtensions = mutableListOf()).mapNotNull { candidate ->
                candidate.member.takeUnless {
                    (candidate.declarations is JavaClass || (candidate.declarations is BuiltinClass && candidate.jvmMethod != null)) &&
                        (it as? Member.Function)?.function?.isShadowingJavaMethod == false
                }
            }
        ).filter { member ->
            member.visibility == Visibility.PUBLIC && when (member) {
                is Member.Function -> member.function.receiverParameterType == null
                is Member.Property -> member.property.receiverParameterType == null
            }
        }

    /**
     * These extensions, telling on the ones on the companion object named [companionName], like `com/example/Foo.Companion`,
     * its [deprecation], as Kotlin reports it when they are called, like `Foo.of()`,
     * rendered next to their own one, see [formatCompanionDeprecation],
     * while the ones resolved by the name of the companion object, like `Foo.Companion.of`, follow it, see [enclosingContext].
     */
    private fun ListedExtensions.withCompanionDeprecation(companionName: String, deprecation: Deprecation): ListedExtensions {
        fun KmType?.isCompanion() = (this?.classifier as? KmClassifier.Class)?.name == companionName
        return ListedExtensions(
            functions.map { if (it.function.receiverParameterType.isCompanion()) it.copy(companionDeprecation = deprecation) else it },
            properties.map { if (it.property.receiverParameterType.isCompanion()) it.copy(companionDeprecation = deprecation) else it }
        )
    }

    /**
     * These extensions without the ones on a type parameter pinned to the class of the [supertypes], see [pins],
     * like `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T)` for `Int`,
     * shadowed by a more specific overload taking the same parameters, see [shadows],
     * like `fun Int.coerceAtLeast(minimumValue: Int)`, called instead,
     * unless the type argument is given explicitly, like `5.coerceAtLeast<Int>(3)`:
     * a member function of the class, also an inherited one, given by [memberFunctions] their names,
     * other than a member extension, or a Java method taking parameters, see [isShadowingJavaMethod],
     * or one of these extensions on the class itself,
     * imported from the same package as the shadowed one.
     * So is an extension property on such a type parameter, like `val <T : Comparable<T>> T.rank`,
     * shadowed by one of these extension properties of the same name on the class itself, like `val Version.rank`,
     * imported from the same package, read instead, unless it has a context parameter, see [isReadAnywhere],
     * while a member property shadows it anyway, see [withoutShadowedByMembers].
     * Any other extension on a type parameter is listed, as the type parameter can be inferred as a supertype of the class,
     * like for `ps.foo(object : PD {})` of `fun <T : PD> T.foo(value: T)`, next to the member `foo(value: PS)`.
     */
    private fun ListedExtensions.withoutShadowed(
        supertypes: ClassSupertypes,
        memberFunctions: (names: Set<String>) -> List<KmFunction>
    ): ListedExtensions {
        val kmClass = supertypes.kmClass
        // the type parameter can be inferred as a subclass, or as a supertype of one of the type arguments
        if (kmClass.modality != Modality.FINAL || kmClass.typeParameters.isNotEmpty()) return this
        val pinned = functions.filter { extension ->
            extension.function.pinnableReceiverTypeParameter?.let { supertypes.pins(it) } == true
        }
        val pinnedProperties = properties.filter { extension ->
            extension.property.pinnableReceiverTypeParameter?.let { supertypes.pins(it) } == true
        }
        if (pinned.isEmpty() && pinnedProperties.isEmpty()) return this
        fun KmType?.isClass() = this != null && !isNullable && arguments.isEmpty() &&
            (classifier as? KmClassifier.Class)?.name == kmClass.name
        val shadowedProperties = pinnedProperties.filterTo(HashSet()) { extension ->
            properties.any {
                it.property.name == extension.property.name && it.source == extension.source &&
                    it.property.receiverParameterType.isClass() && it.property.isReadAnywhere
            }
        }
        if (pinned.isEmpty()) return ListedExtensions(functions, properties.filter { it !in shadowedProperties })
        val members = memberFunctions(pinned.mapTo(HashSet()) { it.function.name }).groupBy { it.name }
        val extensionsOnClass = functions.filter { it.function.receiverParameterType.isClass() }
        val shadowed = pinned.filterTo(HashSet()) { extension ->
            val function = extension.function
            val overloads = members[function.name].orEmpty() +
                extensionsOnClass.filter { it.function.name == function.name && it.source == extension.source }.map { it.function }
            overloads.any { it.shadows(function, kmClass.name) }
        }
        return ListedExtensions(functions.filter { it !in shadowed }, properties.filter { it !in shadowedProperties })
    }

    /**
     * Whether the type parameter of the receiver of an extension, see [pinnableReceiverTypeParameter],
     * is pinned to the class, so that no other type satisfies its bounds:
     * one of them takes it as an invariant, or a contravariant, type argument, which the class gives as itself,
     * like `T : Comparable<T>` for `Int`, a `Comparable<Int>`, or `N : Node<N>` for `class Leaf : Node<Leaf>`,
     * as a supertype of the class would have to be the class itself, or its subtype, to satisfy it,
     * told by the Kotlin metadata, see [ClassSupertypes.types].
     */
    private fun ClassSupertypes.pins(typeParameter: KmTypeParameter): Boolean = typeParameter.upperBounds.any { bound ->
        val classifier = bound.classifier as? KmClassifier.Class ?: return@any false
        // any of the supertypes reached through different paths, see keptSupertypes, which take the same class as a type argument
        val supertype = types[classReceiverIndexKey(bound)]?.first() ?: return@any false
        val declaredVariances = declaredVariances(classifier.name)
        if (declaredVariances?.size != bound.arguments.size || supertype.arguments.size != bound.arguments.size) {
            return@any false
        }
        bound.arguments.indices.any { index ->
            val argument = bound.arguments[index]
            val argumentType = argument.type
            val supertypeArgument = supertype.arguments[index].type
            val variance = argument.variance?.let { projectedVariance(declaredVariances[index], it) }
            argumentType != null && supertypeArgument != null &&
                (argumentType.classifier as? KmClassifier.TypeParameter)?.id == typeParameter.id && !argumentType.isNullable &&
                (variance == KmVariance.INVARIANT || variance == KmVariance.IN) &&
                (supertypeArgument.classifier as? KmClassifier.Class)?.name == kmClass.name &&
                !supertypeArgument.isNullable && supertypeArgument.arguments.isEmpty()
        }
    }

    /**
     * The functions named any of the [names], which the [kmClass], without type parameters, inherits from its supertypes,
     * part of the API in the [scope], with the type arguments of the supertypes substituted, see [supertypeSubstitution],
     * like `fun coerceAtMost(maximumValue: Leaf)` of `fun coerceAtMost(maximumValue: T)` of `Base<Leaf>`,
     * as told by [inheritedCandidates], also leaving out the ones merged with a hidden one which the compiler picks,
     * but the member extensions, the JVM-only ones of a built-in supertype,
     * and the methods of a Java supertype taking parameters, see [isShadowingJavaMethod].
     */
    private fun inheritedFunctionsNamed(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        names: Set<String>,
        scope: ApiClass
    ): List<KmFunction> = names.flatMap { name ->
        inheritedCandidates(kmClass, declarations, name, scope, hidingExtensions = mutableListOf()).mapNotNull { candidate ->
            (candidate.member as? Member.Function)?.function?.takeUnless {
                it.receiverParameterType != null || (candidate.declarations is BuiltinClass && candidate.jvmMethod != null) ||
                    (candidate.declarations is JavaClass && !it.isShadowingJavaMethod)
            }
        }
    }

    /**
     * The companion object of a class whose extensions are listed with the class, see [extendedCompanionOf].
     *
     * @property name the name of the companion object, like `Companion`.
     * @property deprecation the deprecation of the companion object, never a hidden one,
     *   which Kotlin reports when its extensions are called, like `Foo.parse()`, see [withCompanionDeprecation].
     */
    private class ExtendedCompanion(val name: String, val deprecation: Deprecation?)

    /**
     * The companion object of the [kmClass], named [className], whose extensions,
     * like `fun Foo.Companion.parse()`, are called on the name of the class, like `Foo.parse()`,
     * or `null` if none of them is indexed, or it has none which is part of the API, like one deprecated as hidden,
     * which an extension compiled before an upgrade of its library hid it might still extend,
     * while Kotlin no longer calls `Foo.parse()`, told only for an indexed extension, not to load the companion object.
     * A built-in type described by kotlin-reflect, see [toKmBuiltinClass], declares no companion object,
     * so it is told by kotlin-reflect, like `String.Companion` of `String.Companion.format()`, never deprecated.
     * The extensions on the supertypes of the companion object are not listed, not to flood the listing of the class.
     *
     * @param apiClass the [kmClass] as a class of the API.
     * @param declarations the declarations of the [kmClass], telling the located name of the companion object.
     */
    private fun extendedCompanionOf(
        kmClass: KmClass,
        className: String,
        apiClass: ApiClass,
        declarations: ClassDeclarations
    ): ExtendedCompanion? {
        fun isExtended(name: String) = "$className.$name".let { it in extensionIndex || it in extensionPropertyIndex }
        kmClass.companionObject?.let { name ->
            if (!isExtended(name)) return null
            val (located, _) = apiCompanionClassOf(kmClass, declarations, apiClass) ?: return null
            val deprecation = classDeclarations(located)?.classDeprecation
            if (deprecation?.isHidden == true) return null
            return ExtendedCompanion(name, deprecation)
        }
        // the companion objects of the built-in types have the default name
        if (declarations !is BuiltinClass || kmClass.name in builtinClasses || !isExtended(DEFAULT_COMPANION_NAME)) return null
        return reflectedValue(default = null) { builtinTypes[className]?.companionObject?.simpleName }
            ?.takeIf(::isExtended)
            ?.let { ExtendedCompanion(it, deprecation = null) }
    }

    /**
     * The comments telling the classes enclosing the declarations of the [kmClass] resolved directly, one line per class,
     * the outermost first, rendered before them, unlike in the listing of the outer class, where they are rendered within it:
     * the outermost protected class, like `// in protected companion object Outer.Named`,
     * as only the subclasses of its outer class can use them, which would otherwise read as usable by anyone,
     * and each deprecated class, like `// in @Deprecated("Use Bar", level = DeprecationLevel.ERROR) class Foo`,
     * also a protected one, like `// in @Deprecated("Use Bar") protected class Outer.Foo`,
     * as Kotlin reports the deprecation of each class on its name the caller writes,
     * like `Foo.create()`, `Foo.Companion.create()`, `Foo.Nested()`, or the extension `Foo.parse()` of its companion object.
     * A class deprecated as hidden is never told, as nothing within it is resolved, see [ClassScope.isUsable].
     * Neither is the deprecation of a class enclosing the [aliasedClass], as the caller never writes its name,
     * like `Alias.create()` of `typealias Alias = Foo.Nested`, while Kotlin reports the deprecation of the aliased class itself.
     *
     * @param apiClass the [kmClass] as a class of the API.
     * @param declarations the declarations of the [kmClass], telling the located names of the classes enclosing it.
     * @param includingClass whether the class itself counts, as for its members,
     *   unlike for the class resolved directly, whose header tells its own visibility and deprecation.
     * @param isBuiltin whether the class is a built-in type, or its companion object, never deprecated, nor looked up.
     * @param aliasedClass the class a typealias the declarations are resolved through expands to, like `com/example/Foo.Nested`,
     *   the [kmClass] itself, like for `Alias.create` of `typealias Alias = Foo.Nested`,
     *   as nothing within a class nested in it, nor its companion object, is resolved through the typealias, see [resolveTypeAlias].
     */
    private fun enclosingContext(
        kmClass: KmClass,
        apiClass: ApiClass,
        declarations: ClassDeclarations,
        includingClass: Boolean,
        isBuiltin: Boolean,
        aliasedClass: String? = null
    ): List<EnclosingClass> {
        val names = enclosingClassNames(kmClass.name) + kmClass.name
        val outermostProtected = names.indices.firstOrNull { apiClass.scopes[it].visibility == Visibility.PROTECTED }
        return names.indices.take(if (includingClass) names.size else names.size - 1).mapNotNull { index ->
            val name = names[index]
            val deprecation = when {
                isBuiltin || aliasedClass?.startsWith("$name.") == true -> null
                name == kmClass.name -> declarations.classDeprecation
                else -> classDeclarations(declarations.located(name))?.classDeprecation
            }?.takeUnless { it.isHidden }
            val isProtected = apiClass.scopes[index].visibility == Visibility.PROTECTED
            if (deprecation == null && index != outermostProtected) return@mapNotNull null
            fun line(deprecation: Deprecation?) = "// in " + formatDeprecation(deprecation) + (if (isProtected) "protected " else "") +
                formatClassKind(apiClass.scopes[index].kind) + escapeQualifiedName(name.substringAfterLast('/'))
            val line = line(deprecation)
            EnclosingClass(
                line,
                isDeprecated = deprecation != null,
                deprecationOnClassName = deprecation?.let {
                    formatDeprecationComment(
                        formatClassKind(apiClass.scopes[index].kind) + escapeQualifiedName(name.substringAfterLast('/')),
                        it, isAlsoCalledOnInstance = true
                    )
                },
                protectedLine = when {
                    index != outermostProtected -> null
                    deprecation == null -> line
                    else -> line(deprecation = null)
                }
            )
        }
    }

    /**
     * The companion object of a class, unless it is not part of the API, see [ApiClasses],
     * with its [properties] and [functions] which are, see [inApi].
     *
     * @param apiClass the companion object as a class of the API.
     */
    private inner class ApiCompanion(val kmClass: KmClass, val foundClass: ClassDeclarations, val apiClass: ApiClass) {

        val properties: List<KmProperty> by lazy { propertiesNamed(null) }

        val functions: List<KmFunction> by lazy { functionsNamed(null) }

        /**
         * The [properties] named [name], or all of them, if `null`, filtered by name first,
         * not to tell whether each of the others is hidden.
         */
        fun propertiesNamed(name: String?): List<KmProperty> =
            kmClass.properties.filter { name == null || it.name == name }.inApi(apiClass, foundClass)

        /**
         * The [functions] named [name], or all of them, like the [propertiesNamed].
         */
        fun functionsNamed(name: String?): List<KmFunction> =
            kmClass.functions.filter { name == null || it.name == name }.inApi(apiClass, foundClass)

    }

    /**
     * The companion object of the [kmClass], see [ApiCompanion], or `null` if it has none which is part of the API,
     * like a hidden, or a synthetic one, whose members cannot be referred to either.
     *
     * @param declarations the declarations of the [kmClass], telling the located name of the companion object.
     * @param outer the [kmClass] as a class of the API, which the companion object is derived from.
     */
    private fun apiCompanionOf(kmClass: KmClass, declarations: ClassDeclarations, outer: ApiClass): ApiCompanion? {
        val (name, apiClass) = apiCompanionClassOf(kmClass, declarations, outer) ?: return null
        val companionClass = classDeclarations(name) ?: return null
        return ApiCompanion(companionClass.kmClass ?: return null, companionClass, apiClass)
    }

    /**
     * The located name of the companion object of the [kmClass], with the companion object as a class of the API,
     * see [apiCompanionOf], without loading its declarations, or `null` if it has none which is part of the API.
     */
    private fun apiCompanionClassOf(kmClass: KmClass, declarations: ClassDeclarations, outer: ApiClass): Pair<String, ApiClass>? {
        val name = declarations.located("${kmClass.name}.${kmClass.companionObject ?: return null}")
        return name to (apiClasses.nestedApiClass(outer, name) ?: return null)
    }

    /**
     * The declaration of the [companion] object of the [kmClass], like `companion object`,
     * named only when custom, as the name is then needed to qualify the members, like `Outer.Factory.create()`.
     */
    private fun formatCompanionHeader(kmClass: KmClass, companion: ApiCompanion, contextPackage: String): String {
        val companionName = kmClass.companionObject.takeIf { it != DEFAULT_COMPANION_NAME } ?: ""
        return formatClassHeader(
            companion.kmClass, escapeIdentifier(companionName), contextPackage,
            deprecation = companion.foundClass.classDeprecation
        )
    }

    /**
     * Format a [property] of the [companion] object on its lines, see [formatProperty],
     * the setter visibility decided in the companion object.
     */
    private fun formatCompanionProperty(property: KmProperty, companion: ApiCompanion, contextPackage: String): List<String> =
        formatApiProperty(
            property, companion.apiClass, companion.foundClass,
            owner = companion.foundClass.overrideOwner, contextPackage = contextPackage
        )

    private fun formatCompanionFunction(function: KmFunction, companion: ApiCompanion, contextPackage: String): String =
        formatFunction(
            function, inInterface = false, owner = companion.foundClass.overrideOwner, contextPackage = contextPackage,
            deprecation = companion.foundClass.deprecation(function)
        )

    /**
     * Resolves the top-level declarations named [memberName] of a file facade like the members of a class,
     * see [resolveMember]: under the `// from …` header of the package to import them from,
     * referring to the types of the package by their simple names, the properties first, then the functions.
     *
     * @param kmPackages the package declarations, with the class declaring them, like a file facade.
     */
    private fun resolvePackageMember(kmPackages: List<Pair<KmPackage, FoundClass>>, memberName: String): MemberListing? {
        // each declaration on its lines
        fun signatures(
            format: (KmPackage, FoundClass, contextPackage: String) -> List<List<String>>
        ): List<ListingItem.Extension> = kmPackages.flatMap { (kmPackage, foundClass) ->
            val binaryName = foundClass.binaryName
            val packageName = foundClass.kotlinPackageName
            val source = packageSourceOf(binaryName, packageName)
            val contextPackage = kotlinPackageOf(binaryName, packageName)
            format(kmPackage, foundClass, contextPackage).map { ListingItem.Extension(source, it) }
        }
        val properties = signatures { kmPackage, foundClass, contextPackage ->
            kmPackage.properties.filter { it.name == memberName }.inApi(TopLevel, foundClass).map {
                formatApiProperty(it, TopLevel, foundClass, contextPackage = contextPackage)
            }
        }
        val functions = signatures { kmPackage, foundClass, contextPackage ->
            kmPackage.functions.filter { it.name == memberName }.inApi(TopLevel, foundClass).map {
                listOf(formatFunction(it, contextPackage = contextPackage, deprecation = foundClass.deprecation(it)))
            }
        }
        return MemberListing.of(properties + functions)
    }

    /**
     * The deprecation of a member of a built-in type, described by kotlin-reflect, like the hidden `getFirst()`
     * of `java.util.List` since JDK 21, which Kotlin hides in favor of the `first()` extension,
     * by the same rule as the one of the annotations read from the class files, see [deprecationVisitor].
     * The annotations are built by kotlin-reflect from the metadata of the built-ins, never failing the resolve.
     */
    private fun builtinDeprecation(member: KCallable<*>): Deprecation? =
        reflectedValue(default = null) { reflectedDeprecation(member.annotations) }

    /**
     * The deprecation of a Java member, the [element], annotated with `@java.lang.Deprecated`,
     * which Kotlin warns of, with the message `Deprecated in Java`, regardless of its `forRemoval`,
     * or `null` if it is not deprecated, or cannot be reflected.
     */
    private fun javaDeprecation(element: () -> AnnotatedElement?): Deprecation? = reflectedValue(default = null) {
        if (element()?.isAnnotationPresent(java.lang.Deprecated::class.java) == true) {
            Deprecation(JAVA_DEPRECATION_MESSAGE, replaceWith = null, level = DeprecationLevel.WARNING)
        } else {
            null
        }
    }

    /**
     * The deprecation told by the [reflected] annotations of a declaration, like of a built-in member,
     * see [builtinDeprecation], or of a class whose class file cannot be read, or of its members,
     * see [reflectedClassFile] and [reflectedMembersFile], by the same rule as the one of the annotations read from the class files, see [deprecationVisitor],
     * each attribute read on its own, never failing the resolve.
     */
    private fun reflectedDeprecation(reflected: List<Annotation>): Deprecation? = reflectedValue(default = null) {
        val annotations = DeprecationAnnotations()
        reflected.forEach { annotation ->
            when (annotation) {
                is Deprecated -> {
                    annotations.isDeprecated = true
                    annotations.message = reflectedValue(default = null) { annotation.message }
                    annotations.replaceWith = reflectedValue(default = null) { annotation.replaceWith.expression }
                    annotations.level = reflectedValue(default = null) { annotation.level.name }
                }
                is DeprecatedSinceKotlin -> {
                    annotations.isDeprecatedSinceKotlin = true
                    annotations.warningSince = reflectedValue(default = null) { annotation.warningSince }
                    annotations.errorSince = reflectedValue(default = null) { annotation.errorSince }
                    annotations.hiddenSince = reflectedValue(default = null) { annotation.hiddenSince }
                }
            }
        }
        annotations.deprecationOf(::isSince)
    }

    /**
     * The names of the supertypes of the [kmClass], walked breadth-first, like `kotlin.collections.Collection`,
     * the [included] ones, traversed further through their declarations, given by [kmClassOf] their metadata names,
     * or else through their Java classes, see [javaSupertypeNames].
     * A function type, like `suspend () -> Unit`, is named by its function type class,
     * like `kotlin.coroutines.SuspendFunction0`, see [receiverIndexKeys], and has the supertype `kotlin.Function`.
     *
     * @param types when given, receives the supertypes told by the Kotlin metadata, or by the generic signatures
     *   of the Java supertypes, see [javaSupertypeTypes], by their names,
     *   with the type arguments substituted down to the [kmClass], see [ClassSupertypes.types],
     *   each one reached through any path, but the ones kotlinc leaves out, see [keptSupertypes].
     */
    private fun supertypeNames(
        kmClass: KmClass,
        kmClassOf: (metadataName: String) -> KmClass?,
        types: MutableMap<String, List<KmType>>? = null,
        included: (name: String) -> Boolean = { true }
    ): Set<String> = supertypeNames(kmClass.name, { if (it == kmClass.name) kmClass else kmClassOf(it) }, types, included = included)

    /**
     * The names of the supertypes of the class of the [metadataName], see [supertypeNames] of a [KmClass],
     * also of a Java class, like `java/util/ArrayList`, whose supertypes are told by its JVM class.
     *
     * @param type the type of the Java class of the [metadataName], whose type arguments are substituted
     *   in the [types] of its supertypes, see [javaSupertypeTypes], or `null` for the class itself,
     *   whose supertypes then keep its type parameters, unless it is a Java class, telling no [types] then.
     */
    private fun supertypeNames(
        metadataName: String,
        kmClassOf: (metadataName: String) -> KmClass?,
        types: MutableMap<String, List<KmType>>? = null,
        type: KmType? = null,
        included: (name: String) -> Boolean = { true }
    ): Set<String> {
        val supertypeNames = linkedSetOf<String>()
        // the metadata names of the supertypes to traverse, starting with the class itself, which is not among them,
        // with the types they are reached by, unless through a Java class, telling the substitution of their type parameters
        val pending = ArrayDeque<Pair<String, KmType?>>(listOf(metadataName to type))
        // the structures of the types of each supertype, see structureKey, telling the ones already reached at once,
        // as their number can double at each level of a hierarchy
        val structures = HashMap<String, MutableSet<String>>()
        fun visit(name: String, metadataName: String, type: KmType?) {
            if (type != null && types != null) {
                val known = types[name].orEmpty()
                val structure = type.structureKey()
                val knownStructures = structures.getOrPut(name) { HashSet() }
                // each one reached through another path, like MutableList<String> of a Kotlin interface
                // and MutableList<String!> of a Java one, see keptSupertypes, up to MAX_SUPERTYPE_PATHS of them,
                // and beyond, one covering others of the same kind replacing them, see coversType, a covered one adding nothing,
                // or else one without platform type arguments replacing one with them, which kotlinc would leave out
                val reached = when {
                    structure in knownStructures -> null
                    known.size < MAX_SUPERTYPE_PATHS -> known + type
                    else -> {
                        val isPlatform = type.hasPlatformArguments
                        val sameKind = known.filter { it.hasPlatformArguments == isPlatform }
                        val covered = sameKind.filter { type.coversType(it) }
                        when {
                            sameKind.any { it.coversType(type) } -> null
                            covered.isNotEmpty() -> known.filter { it !in covered } + type
                            isPlatform -> null
                            else -> known.indexOfFirst { it.hasPlatformArguments }.takeIf { it >= 0 }?.let { index ->
                                known.toMutableList().also { it[index] = type }
                            }
                        }
                    }
                }
                if (reached != null) {
                    types[name] = reached
                    knownStructures += structure
                    // the ones replaced are known no more
                    if (reached.size <= known.size) {
                        known.filter { replaced -> reached.none { it === replaced } }.forEach { knownStructures -= it.structureKey() }
                    }
                    // walked again for the supertypes it gives, like MutableCollection<String>, when already walked,
                    // untyped, or with another type, or else walked with it as well, when still pending
                    if (name in supertypeNames) pending += metadataName to type
                }
            }
            val functionTypeClass = FunctionTypeClass.of(name)
            if (functionTypeClass != null) {
                if (included(name)) supertypeNames += name
                functionTypeClass.supertypeNames.forEach { visit(it, kotlinClassName(it), type = null) }
            } else if (included(name) && supertypeNames.add(name)) {
                pending += metadataName to type
            }
        }
        fun visitSupertypes(supertypes: List<KmType>, projections: TypeProjections?) {
            for (supertype in supertypes) {
                val classifier = supertype.classifier as? KmClassifier.Class ?: continue
                val type = if (types != null && projections != null) {
                    supertype.substitutedProjecting(projections) { substituted(it) }
                } else {
                    null
                }
                visit(receiverIndexKeys(supertype).single(), classifier.name, type)
            }
        }
        var isClassItself = true
        while (pending.isNotEmpty()) {
            val (supertypeName, type) = pending.removeFirst()
            val supertypeKmClass = kmClassOf(supertypeName)
            if (supertypeKmClass != null) {
                val projections = if (isClassItself) NO_PROJECTIONS else type?.let { projectionsOf(supertypeKmClass, it) }
                visitSupertypes(supertypeKmClass.supertypes, projections)
            } else {
                val javaTypes = if (types != null) javaSupertypeTypes(supertypeName, type) else emptyMap()
                // the Kotlin collection a JDK class implements purely, before its Java supertypes, see javaSupertypeTypes
                PURE_IMPLEMENTATIONS[supertypeName]?.let { pureImplementation ->
                    val name = pureImplementation.replace('/', '.')
                    visit(name, pureImplementation, type = javaTypes[name])
                }
                javaSupertypeNames(supertypeName).forEach { (name, supertypeMetadataName) ->
                    visit(name, supertypeMetadataName, type = javaTypes[name])
                }
            }
            isClassItself = false
        }
        types?.replaceAll { _, reached -> keptSupertypes(reached) }
        return supertypeNames
    }

    /**
     * The supertypes of the same class reached through different paths, the [reached] ones, which kotlinc keeps,
     * like `selectOnlyPureKotlinSupertypes` of kotlinc, the ones whose type arguments are not platform types of Java,
     * see [hasPlatformArguments], like `MutableList<String>` of a Kotlin interface, rather than `MutableList<String!>` of a Java one,
     * but `Box<List<String!>>` of a Java one, of a nested platform type, next to `Box<List<String>>`,
     * or else all of them, an extension matching any of them, see [ClassSupertypes.isCallableOn].
     */
    private fun keptSupertypes(reached: List<KmType>): List<KmType> =
        if (reached.size < 2) reached else reached.filter { !it.hasPlatformArguments }.ifEmpty { reached }

    /**
     * The direct supertypes of the Java class of the given [metadataName], which has no Kotlin metadata,
     * by their Kotlin names, like `kotlin.collections.MutableList` of `java.util.List`,
     * see [OverrideResolver.builtinNameOfJavaClass],
     * or `kotlin.Function1` of `kotlin.jvm.functions.Function1`, paired with their metadata names,
     * or none if the class cannot be loaded, like when its supertype is missing from the classpath.
     */
    private fun javaSupertypeNames(metadataName: String): List<Pair<String, String>> {
        val javaClass = findClassByMetadataName(metadataName)?.clazz ?: return emptyList()
        return javaSupertypeClasses(javaClass).map { (_, supertypeMetadataName) -> supertypeMetadataName.replace('/', '.') to supertypeMetadataName }
    }

    /**
     * The direct supertypes of the [javaClass], with their metadata names, see [javaSupertypeMetadataName],
     * each one reflected on its own, not to lose the others when one of them cannot be reflected.
     */
    private fun javaSupertypeClasses(javaClass: Class<*>): List<Pair<Class<*>, String>> {
        val supertypes = reflectedValue(default = emptyList()) { listOfNotNull(javaClass.superclass) + javaClass.interfaces }
        return supertypes.mapNotNull { supertype -> reflectedValue(default = null) { supertype to javaSupertypeMetadataName(supertype) } }
    }

    /**
     * The metadata name of a supertype of a Java class, like `kotlin/collections/MutableList` of `java.util.List`,
     * see [OverrideResolver.builtinNameOfJavaClass], or `kotlin/Function1` of `kotlin.jvm.functions.Function1`.
     */
    private fun javaSupertypeMetadataName(supertype: Class<*>): String =
        overrideResolver.builtinNameOfJavaClass(supertype.name)
            ?: functionTypeOfJvmInterface(supertype.name.replace('.', '/'))
            ?: kotlinClassName(supertype)

    /**
     * The direct supertypes of the Java class of the given [metadataName], which has no Kotlin metadata, reached by the [type],
     * by their names, like [javaSupertypeNames], with the type arguments told by its generic signature,
     * see [FoundClass.javaClassHeader], and the ones of the [type] substituted for its type parameters,
     * like `Comparable<Base>` of `class Base implements Comparable<Base>`,
     * the types they give, as platform types, matching either nullability, see [javaSignatureType],
     * but the Kotlin collection a JDK class is the pure implementation of, see [PURE_IMPLEMENTATIONS],
     * like `MutableList<String>` of `java.util.ArrayList<String>`, not a `MutableList<String?>`, as kotlinc sees it,
     * or none, when its type arguments are not known, like of a raw type.
     */
    private fun javaSupertypeTypes(metadataName: String, type: KmType?): Map<String, KmType> {
        val foundClass = findClassByMetadataName(metadataName)
        val header = foundClass?.javaClassHeader ?: return emptyMap()
        val projections = when {
            header.typeParameters.isEmpty() -> NO_PROJECTIONS
            type == null -> return emptyMap()
            else -> projectionsOf(header, type) ?: return emptyMap()
        }
        // a JDK class which kotlin-reflect describes as a built-in type, like java.util.List as List, named as a supertype,
        // like abstract class X : java.util.List<String>, has the supertypes of its JVM class, like java.util.SequencedCollection<E>,
        // or java.util.Collection<E>, the ones javaSupertypeNames tells, not the ones of the built-in type,
        // unless they take other type arguments than its type parameters, like Comparable<Integer> of java.lang.Integer,
        // told by the built-in type then, like Comparable<Int>, which is no Comparable of another type either
        val supertypes = foundClass.clazz?.takeIf(::isDescribedAsBuiltin)
            ?.let { clazz -> reflectedValue(default = null) { clazz.javaSignatureSupertypes(::javaSupertypeMetadataName) } }
            ?: header.supertypes
        val pureImplementation = PURE_IMPLEMENTATIONS[metadataName]?.replace('/', '.')
        val types = supertypes.associate { supertype ->
            val key = receiverIndexKeys(supertype).single()
            key to if (key == pureImplementation) {
                supertype.substitutedProjecting(projections) { substituted(it) }
            } else {
                supertype.substitutedProjecting(projections) { javaSignatureType(it) }
            }
        }
        if (pureImplementation == null || pureImplementation in types) return types
        // implemented through a Java supertype, like MutableSet<E> of java.util.LinkedHashSet<E>, through HashSet<E!>
        val pureType = KmType().also { pureType ->
            pureType.classifier = KmClassifier.Class(pureImplementation.replace('.', '/'))
            header.typeParameters.mapTo(pureType.arguments) { typeParameter ->
                KmTypeProjection(KmVariance.INVARIANT, KmType().also { it.classifier = KmClassifier.TypeParameter(typeParameter.id) })
            }
        }
        return types + (pureImplementation to pureType.substitutedProjecting(projections) { substituted(it) })
    }

    /**
     * All the supertypes of a class, excluding the ones from `kotlin.*` and `java.*` packages,
     * which are not traversed further, so that a class implementing, e.g., `CharSequence`
     * is not flooded with the generic extensions of the standard library,
     * nor with the ones of any other library on these types, like on `java.lang.AutoCloseable`,
     * or `java.util.function.Function`, which can still be called on the class,
     * also on `kotlin.Comparable`, like `fun Comparable<Money>.atLeast()` of another package than
     * `class Price : Comparable<Money>`, but the ones of the package of the class, on any of these types, see [listedExtensions],
     * and the ones of the standard library for a class comparable to itself, see [isComparableToItself],
     * except the function types, like `(String) -> Unit`, and their supertype `kotlin.Function`,
     * whose extensions are listed for a class implementing them, like for a function type class,
     * see [functionTypeReceiverNames].
     * Also the ones reached through a Java supertype, see [supertypeNames].
     */
    private fun userSupertypes(kmClass: KmClass): Set<String> =
        supertypeNames(kmClass, kmClassOf = ::loadKmClass) {
            !isOfStandardPackage(it) ||
                it == FUNCTION_CLASS_NAME ||
                FunctionTypeClass.of(it) != null
        }

    /**
     * The supertypes of a class, walked once for its listing, see [supertypesOf].
     *
     * @property kmClass the class.
     * @property names the names of all its supertypes, also the ones from `kotlin.*` and `java.*` packages,
     *   unlike the [userSupertypes], telling whether the class satisfies the multiple bounds of a type parameter receiver,
     *   see [receiverBoundsOf].
     * @property types the supertypes told by the Kotlin metadata, or by the generic signatures of the Java supertypes,
     *   see [javaSupertypeTypes], by their names, with the type arguments of the class,
     *   like `Comparable<User>` of `class User : Entity<User>()`, where `abstract class Entity<E> : Comparable<E>`,
     *   telling whether an extension listed by its receiver name can be called on the class, see [isCallableOn],
     *   while a type parameter of the class, in its type arguments, stands for any type,
     *   each one reached through any path, which kotlinc keeps, see [keptSupertypes], the class matching any of them.
     */
    private class ClassSupertypes(val kmClass: KmClass, val names: Set<String>, val types: Map<String, List<KmType>>) {

        /**
         * The supertypes of the supertypes of the class, walked once each, by their metadata names, see [supertypesOfSupertype].
         */
        val supertypesOfSupertypes = HashMap<String, SupertypesOfSupertype>()

        /**
         * The supertypes of the types of the generic Java classes, with their type arguments, see [supertypesOf],
         * by the structures of these types, see [structureKey], as each of them is matched against many extensions.
         */
        val supertypesOfJavaTypes = HashMap<String, Map<String, List<KmType>>>()

    }

    /**
     * The supertypes of a supertype of a class, see [ClassSupertypes.supertypesOfSupertype].
     *
     * @property names the names of its supertypes, see [ClassSupertypes.supertypeNamesOf].
     * @property types its supertypes told by the Kotlin metadata, or by the generic signatures of the Java supertypes,
     *   by their names, like [ClassSupertypes.types], see [ClassSupertypes.supertypeTypesOf].
     */
    private class SupertypesOfSupertype(val names: Set<String>, val types: Map<String, List<KmType>>)

    /**
     * The supertypes of the [kmClass], also the ones which the JVM classes of its built-in supertypes add,
     * see [jvmSupertypeNamesOfBuiltins], like `java.io.Serializable` of `kotlin.Throwable`, or of `kotlin.String`.
     */
    private fun supertypesOf(kmClass: KmClass): ClassSupertypes {
        val types = HashMap<String, List<KmType>>()
        val names = supertypeNames(kmClass, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) }, types = types)
        val name = kmClass.name.replace('/', '.')
        return ClassSupertypes(kmClass, names + jvmSupertypeNamesOfBuiltins(names + name), types)
    }

    /**
     * Whether the class is comparable to itself, or to one of its supertypes,
     * like `class Version : Comparable<Version>`, or `class User : Entity<User>()`, where `Entity<E> : Comparable<E>`,
     * satisfying the bound `T : Comparable<T>` of the extensions of the standard library on `Comparable`,
     * like `coerceAtLeast`, which are few and widely used, unlike the generic extensions
     * on the other types of the standard library, see [userSupertypes].
     * Told by the Kotlin metadata of the class and of its supertypes, see [ClassSupertypes.types],
     * or by the generic signature of a Java supertype, like `class Base implements Comparable<Base>`, see [javaSupertypeTypes].
     * An enum class, comparable to itself through `kotlin.Enum`, is not told to be,
     * as these extensions are rarely called on the enum entries, and would grow the listing of every enum class,
     * unlike the ones of its own package, like `fun <T : Comparable<T>> T.isBetween(low: T, high: T)`, see [isComparable].
     */
    private val ClassSupertypes.isComparableToItself: Boolean
        get() {
            if (kmClass.kind == ClassKind.ENUM_CLASS) return false
            // any of the supertypes reached through different paths, see keptSupertypes, which are comparable to the same class
            val comparableTo = types[COMPARABLE_CLASS_NAME]?.first()?.arguments?.singleOrNull()?.type ?: return false
            if (isSelfTypeParameter(comparableTo)) return true
            val name = (comparableTo.classifier as? KmClassifier.Class)?.name ?: return false
            return name == kmClass.name || name.replace('/', '.') in names
        }

    /**
     * Whether the class is comparable, to whatever type, like `class Price : Comparable<Money>`,
     * so that the extensions on `Comparable` of the package of the class are listed for it, like next to it,
     * see [listedExtensions], also for an enum class, comparable to itself through `kotlin.Enum`,
     * unlike the ones of the standard library, see [isComparableToItself], which implies it,
     * but not a class comparable only through a raw Java supertype, like `class Mid extends Base`, where
     * `class Base<T> implements Comparable<Base<T>>`, told by its name, but not by its type, see [ClassSupertypes.types],
     * comparable to the raw `Base`, on which kotlinc does not accept the extensions of the package, like on `Comparable<Money>`.
     */
    private val ClassSupertypes.isComparable: Boolean
        get() = COMPARABLE_CLASS_NAME in types

    /**
     * Whether the [type] is a type parameter of the class bounded by the class itself, of its own type parameters,
     * like `N` of `abstract class Named<N : Named<N>>`, standing for its subclass, which is the class then,
     * as an approximation, like for `fun <E : Enum<E>> f(e: E)`, which can call on `e` what an enum class can,
     * unlike `T` of `abstract class Starred<X, T : Starred<String, T>>`, of other type arguments.
     */
    private fun ClassSupertypes.isSelfTypeParameter(type: KmType): Boolean {
        val id = (type.classifier as? KmClassifier.TypeParameter)?.id ?: return false
        return kmClass.typeParameters.find { it.id == id }?.upperBounds?.any { bound ->
            (bound.classifier as? KmClassifier.Class)?.name == kmClass.name &&
                bound.arguments.size == kmClass.typeParameters.size &&
                bound.arguments.zip(kmClass.typeParameters).all { (argument, typeParameter) ->
                    (argument.type?.classifier as? KmClassifier.TypeParameter)?.id == typeParameter.id
                }
        } == true
    }

    /**
     * The names of the supertypes of the class of the given [metadataName], see [supertypeNames],
     * also of a Java class, without the Kotlin metadata, told by its JVM class,
     * and the ones the JVM classes of the built-in types among them add, see [jvmSupertypeNamesOfBuiltins].
     */
    private fun ClassSupertypes.supertypeNamesOf(metadataName: String): Set<String> =
        if (metadataName == kmClass.name) names else supertypesOfSupertype(metadataName).names

    /**
     * The supertypes of the class of the given [metadataName] told by the Kotlin metadata, by their names,
     * with the type arguments of the class, see [ClassSupertypes.types].
     */
    private fun ClassSupertypes.supertypeTypesOf(metadataName: String): Map<String, List<KmType>> =
        if (metadataName == kmClass.name) types else supertypesOfSupertype(metadataName).types

    /**
     * The supertypes of the class of the given [metadataName], a supertype of the class, or of its type arguments,
     * walked once, see [supertypeNames], and the ones the JVM classes of the built-in types among them add,
     * see [jvmSupertypeNamesOfBuiltins].
     */
    private fun ClassSupertypes.supertypesOfSupertype(metadataName: String): SupertypesOfSupertype =
        supertypesOfSupertypes.getOrPut(metadataName) {
            val types = HashMap<String, List<KmType>>()
            val known = supertypeNames(metadataName, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) }, types).ifEmpty {
                // a loaded Java interface without superinterfaces, like java.lang.Runnable, is still known
                if (findClassByMetadataName(metadataName)?.clazz != null) setOf(ANY_RECEIVER_KEY) else emptySet()
            }
            SupertypesOfSupertype(known + jvmSupertypeNamesOfBuiltins(known + metadataName.replace('/', '.')), types)
        }

    /**
     * The supertypes of the [type] of the class of the given [supertypeName], with the type arguments of the [type],
     * like `Comparable<String>` of `String`, or `null` if it is not told, see [ClassSupertypes.types],
     * like of a supertype reached through a raw Java type,
     * while a star projection, or another projection, is kept, like `Collection<*>` of `List<*>`,
     * also approximated when nested in a type argument of the supertype, see [substitutedProjecting],
     * more than one when reached through different paths, see [keptSupertypes].
     */
    private fun ClassSupertypes.supertypesOf(type: KmType, supertypeName: String): List<KmType>? {
        val name = (type.classifier as? KmClassifier.Class)?.name ?: return null
        val key = supertypeName.replace('/', '.')
        val declaration = if (name == kmClass.name) kmClass else builtinClasses[name] ?: loadKmClass(name)
        if (declaration == null) {
            // a Java class, whose supertypes are told by its generic signature, see javaSupertypeTypes,
            // walked again from the type itself, when it takes type arguments, like Comparable<JavaBox<String!>> of JavaBox<String>
            val header = findClassByMetadataName(name)?.javaClassHeader ?: return null
            if (header.typeParameters.isEmpty()) return supertypeTypesOf(name)[key]
            return supertypesOfJavaTypes.getOrPut(type.structureKey()) {
                HashMap<String, List<KmType>>().also { types ->
                    supertypeNames(name, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) }, types, type = type)
                }
            }[key]
        }
        val supertypes = supertypeTypesOf(name)[key] ?: return null
        if (declaration.typeParameters.isEmpty()) return supertypes
        val projections = projectionsOf(declaration, type) ?: return null
        return supertypes.map { supertype -> supertype.substitutedProjecting(projections) { substituted(it) } }
    }

    /**
     * The names of the supertypes which the JVM classes of the built-in types of the given [names] add
     * to the ones told by their built-in declarations, as Kotlin/JVM sees them,
     * `java.io.Serializable`, like of `kotlin.String`, and `kotlin.Cloneable`, like of `kotlin.Array`,
     * but none of the other ones of the JVM classes, which Kotlin does not see,
     * like `java.lang.constant.Constable` of `java.lang.Integer`, or `java.util.Collection` of `java.util.List`.
     */
    private fun jvmSupertypeNamesOfBuiltins(names: Set<String>): Set<String> =
        names.flatMapTo(HashSet()) { name ->
            jvmSupertypeNamesOfBuiltin.getOrPut(name) {
                val javaClass = DefaultKotlinIntrospector.builtinTypes[name]
                    ?.let { reflectedValue(default = null) { it.javaObjectType } }
                    ?: return@getOrPut emptySet()
                JVM_SUPERTYPES_OF_BUILTINS.filterTo(HashSet()) { (_, jvmClass) -> jvmClass.isAssignableFrom(javaClass) }
                    .mapTo(HashSet()) { (supertypeName, _) -> supertypeName }
            }
        } - names

    // the JVM supertypes of the built-in types, by their names, see jvmSupertypeNamesOfBuiltins
    private val jvmSupertypeNamesOfBuiltin = ConcurrentHashMap<String, Set<String>>()

    /**
     * Whether the class of the metadata name [subtype] is the class of the metadata name [supertype], or its subtype,
     * also when it is not known, like of a class absent from the classpath, but `kotlin.Any`, a subtype of no other class.
     */
    private fun ClassSupertypes.isSubclass(subtype: String, supertype: String): Boolean {
        // Nothing is a subtype of any type
        if (subtype == supertype || supertype == ANY_CLASS_NAME || subtype == NOTHING_CLASS_NAME) return true
        if (subtype == ANY_CLASS_NAME) return false
        val names = supertypeNamesOf(subtype)
        return names.isEmpty() || supertype.replace('/', '.') in names
    }

    /**
     * Whether an extension on the [receiver], declaring the [typeParameters], listed for the class
     * by the name of the receiver, or of a bound of its type parameter receiver, see [receiverIndexKeys],
     * can be called on the class, as told by the type arguments of its supertypes, see [ClassSupertypes.types]:
     * like `fun Box<Int>.sum()` for `class IntBox : Box<Int>`, but not `fun Box<String>.join()`,
     * or `fun <T, B> B.same() where B : Box<T>, B : Source<T>` only for the class, whose supertypes take the same `T`,
     * matched according to the variance, like `fun Source<Number>.first()` for `class IntBox : Source<Int>`,
     * where `interface Source<out T>`.
     * A type parameter receiver stands for the class, like `T` of `T : Comparable<T>`,
     * or else for its supertype, which satisfies all the bounds, like `User` of `T : Entity<T>` for `class Admin : User()`,
     * where `class User : Entity<User>()`, told by the type arguments of the bounds.
     * The type parameters the receiver refers to must be inferred from the class, or from the [otherTypes],
     * the ones of the context and value parameters, not of the [returnType], unless their inference is not modelled,
     * see [ReceiverMatcher.isInferred]: when a bound refers to a type parameter, like `B : A`, or `T : Comparable<T>`,
     * they are inferred from all the types matched at once, like kotlinc infers them for a call without arguments,
     * see [ReceiverMatcher.isInferredByCall], and only where that is not modelled, one by one, while matching.
     * An extension without a receiver can be called on it as well.
     * Only what is told by the Kotlin metadata, or by the generic signatures of the Java supertypes, is matched,
     * any other extension can be called, like on a supertype reached through a raw Java type,
     * or on a type parameter whose other bounds are not checked.
     */
    private fun ClassSupertypes.isCallableOn(
        receiver: KmType?,
        typeParameters: List<KmTypeParameter>,
        returnType: () -> KmType,
        otherTypes: () -> List<KmType>
    ): Boolean {
        if (receiver == null || receiver.classifier is KmClassifier.TypeAlias) return true
        // what the type parameters are inferred from, the same for each type the receiver is matched against
        val referred = lazy(LazyThreadSafetyMode.NONE) { typeParameterIdsReferredBy(listOf(receiver), typeParameters) }
        val inferredOtherwise = lazy(LazyThreadSafetyMode.NONE) { typeParameterIdsReferredBy(otherTypes(), typeParameters) }
        val referredByReturnType = lazy(LazyThreadSafetyMode.NONE) { typeParameterIdsReferredBy(listOf(returnType()), typeParameters) }
        return when (val classifier = receiver.classifier) {
            is KmClassifier.Class -> {
                val reached = types[classReceiverIndexKey(receiver)] ?: return true
                fun matches(isCollecting: Boolean, isInferred: ReceiverMatcher.() -> Boolean) =
                    matchesThroughAnyPaths(typeParameters, receiverTypeParameterId = null, self = kmClass.name, isCollecting) {
                        anyReached(reached) { supertype ->
                            argumentsMatch(classifier.name, receiver.arguments, supertype.arguments, KmVariance.OUT) && isInferred()
                        }
                    }
                // first as kotlinc infers the type parameters, from all the types matched, when that is modelled,
                // and only when it is not, also for a supertype matched, as each of them is inferred while matching
                val isModelled = isCallInferenceModelled(receiver, typeParameters, inferredOtherwise) &&
                    reached.none { it.refersToFunctionType() || it.refersToNothingClass() }
                var isNotModelled = !isModelled
                val isInferredByCall = isModelled && matches(isCollecting = true) {
                    isInferredByCall(referred) ?: false.also { isNotModelled = true }
                }
                isInferredByCall || (isNotModelled && matches(isCollecting = false) { isInferred(referred, inferredOtherwise, referredByReturnType) })
            }
            is KmClassifier.TypeParameter -> {
                val bounds = receiverBoundTypes(classifier.id, typeParameters)
                fun isSatisfiedBy(self: String) = matchesThroughAnyPaths(typeParameters, classifier.id, self) {
                    bounds.all { isSupertype(it) } && isInferred(referred, inferredOtherwise, referredByReturnType)
                }
                isSatisfiedBy(kmClass.name) || bounds
                    .flatMap { bound -> types[classReceiverIndexKey(bound)].orEmpty().flatMap { it.arguments } }
                    .mapNotNullTo(LinkedHashSet()) { (it.type?.classifier as? KmClassifier.Class)?.name }
                    .any { it.replace('/', '.') in names && isSatisfiedBy(self = it) }
            }
            is KmClassifier.TypeAlias -> true
        }
    }

    // whether the extension function can be called on the class, see isCallableOn
    @OptIn(ExperimentalContextParameters::class)
    private fun ClassSupertypes.isCallableOn(function: KmFunction): Boolean =
        isCallableOn(function.receiverParameterType, function.typeParameters, returnType = { function.returnType }) {
            function.contextParameters.map { it.type } + function.valueParameters.map { it.type }
        }

    // whether the extension property can be called on the class, see isCallableOn
    @OptIn(ExperimentalContextParameters::class)
    private fun ClassSupertypes.isCallableOn(property: KmProperty): Boolean =
        isCallableOn(property.receiverParameterType, property.typeParameters, returnType = { property.returnType }) {
            property.contextParameters.map { it.type }
        }

    /**
     * The supertypes of a generic class with a type given for each of its type parameters, the same one for all of them,
     * which satisfies their bounds, see [INSTANTIATING_CLASS_NAMES], like `HashMap<String, String>` of `class K<V> : HashMap<V, V>()`,
     * each of them telling exactly whether an extension can be called on the class of these type arguments, see [isCallableOn],
     * which is callable on the class then, as its type parameters stand for any types,
     * or none, when its supertypes refer to other type parameters as well, like the ones of an outer class.
     */
    private fun ClassSupertypes.instantiated(): List<ClassSupertypes> {
        val typeParameters = kmClass.typeParameters
        if (typeParameters.isEmpty()) return emptyList()
        val matcher = ReceiverMatcher(this, emptyList(), receiverTypeParameterId = null, kmClass.name)
        return INSTANTIATING_CLASS_NAMES.mapNotNull { name ->
            val type = classType(name)
            val substitution = typeParameters.associate { it.id to type }
            val isBounded = typeParameters.all { typeParameter ->
                typeParameter.upperBounds.all { bound ->
                    val substituted = bound.substituted(substitution)
                    substituted.refersOnlyTo { false } && matcher.isSubtype(type, substituted)
                }
            }
            val instantiatedTypes = types.mapValues { (_, reached) -> reached.map { it.substituted(substitution) } }
            val isInstantiated = instantiatedTypes.values.all { reached -> reached.all { it.refersOnlyTo { false } } }
            if (isBounded && isInstantiated) ClassSupertypes(kmClass, names, instantiatedTypes) else null
        }
    }

    /**
     * Whether the inference of the [typeParameters] of an extension on the [receiver] by kotlinc is modelled,
     * see [ReceiverMatcher.CallInference], as far as the extension tells: when a bound refers to a type parameter,
     * like `B : A`, or `T : Comparable<T>`, as they are told one by one without it, when neither the receiver, nor a bound,
     * refers to a function type, which is matched leniently against a class which is none, like one of a supertype
     * of the class matched, which neither refers to `Nothing`, or `Nothing?`, anywhere, inferred by kotlinc
     * in a way which is not modelled, and when no type parameter is told by the context, or value, parameters, the [inferredOtherwise] ones.
     */
    private fun isCallInferenceModelled(
        receiver: KmType,
        typeParameters: List<KmTypeParameter>,
        inferredOtherwise: Lazy<Set<Int>>
    ): Boolean {
        val ids = typeParameters.mapTo(HashSet()) { it.id }
        val bounds = typeParameters.flatMap { it.upperBounds }
        return bounds.any { bound -> !bound.refersOnlyTo { it !in ids } } &&
            !receiver.refersToFunctionType() && bounds.none { it.refersToFunctionType() } &&
            ids.none { it in inferredOtherwise.value }
    }

    // whether this type, or a type argument of it, is Nothing, or Nothing?
    private fun KmType.refersToNothingClass(): Boolean =
        (classifier as? KmClassifier.Class)?.name == NOTHING_CLASS_NAME || arguments.any { it.type?.refersToNothingClass() == true }

    // whether this type, or a type argument of it, is a function type, like (String) -> Unit, or kotlin.Function
    private fun KmType.refersToFunctionType(): Boolean =
        (classifier as? KmClassifier.Class)?.name?.let { FunctionTypeClass.of(it.replace('/', '.')) } != null ||
            arguments.any { it.type?.refersToFunctionType() == true }

    /**
     * Whether the [match] of a [ReceiverMatcher], created for the [typeParameters], the [receiverTypeParameterId] and the [self] class,
     * is matched through any of the supertypes reached through different paths, see [ReceiverMatcher.anyReached],
     * at each place it matches them, also when the one matched first fails what follows,
     * like the other bound of `fun <E, P> P.f() where P : Box<List<E>>, P : Inv<E>`, or the other type argument
     * of `fun <E> Two<Box<List<E>>, E>.f()`, which kotlinc checks against each kept supertype, see [keptSupertypes]:
     * matched again, by the same matcher, choosing the supertypes one by one, see [PathChoices],
     * up to [MAX_PATH_CHOICE_MATCHES] times, and beyond, place by place, see [matchesChoosingPathsOneByOne].
     *
     * @param isCollecting whether the types the type parameters are inferred from are only collected, see [ReceiverMatcher]
     */
    private fun ClassSupertypes.matchesThroughAnyPaths(
        typeParameters: List<KmTypeParameter>,
        receiverTypeParameterId: Int?,
        self: String,
        isCollecting: Boolean = false,
        match: ReceiverMatcher.() -> Boolean
    ): Boolean {
        // first matching the first supertype matched at each place, which tells the most
        val matcher = ReceiverMatcher(this, typeParameters, receiverTypeParameterId, self, isCollecting = isCollecting)
        if (matcher.match()) return true
        if (!matcher.hasPathChoices) return false
        var forced = emptyList<Int>()
        repeat(MAX_PATH_CHOICE_MATCHES) {
            val choices = PathChoices(forced)
            if (matcher.matchingAgain(choices).match()) return true
            forced = choices.next() ?: return false
        }
        return matcher.matchesChoosingPathsOneByOne(match)
    }

    /**
     * Whether the [match] of this matcher is matched through the supertypes chosen place by place, see [PathChoices],
     * when their combinations are more than matched one by one, see [matchesThroughAnyPaths]:
     * at each place, the first supertype which matches with the ones chosen before, while the places beyond it
     * match the first supertype matched, or else, while they match any type, the first one not contradicting
     * what is matched without them, never going back to a place chosen at,
     * like the platform type `String!` of each of the 9 bounds of `P : Box<List<E>>`, … `P : Inv<E>` for `Inv<String?>`,
     * found in 9 steps, instead of the 2^9 combinations, which is a combination matching as a whole, like any other one.
     * A combination matched only by going back to a place stays not matched, and so does one beyond
     * [MAX_PATH_CHOICE_MATCHES] matches more.
     */
    private fun ReceiverMatcher.matchesChoosingPathsOneByOne(match: ReceiverMatcher.() -> Boolean): Boolean {
        val chosen = ArrayList<Int>()
        var matches = 0
        while (true) {
            var index = 0
            while (true) {
                if (matches >= MAX_PATH_CHOICE_MATCHES) return false
                matches += 2
                // the places beyond matching the first supertype matched
                val choices = PathChoices(chosen + index, isMatchingFirstBeyond = true)
                if (matchingAgain(choices).match()) return true
                // no place beyond the chosen ones to choose at
                if (choices.placeCount <= chosen.size) return false
                // or else matching any type, telling whether the supertype contradicts what is chosen before
                if (matchingAgain(PathChoices(chosen + index, isSkippingBeyond = true)).match()) break
                if (++index >= choices.countAt(chosen.size)) return false
            }
            chosen += index
        }
    }

    /**
     * The supertypes chosen at each place a [ReceiverMatcher] matches the supertypes reached through different paths,
     * see [ReceiverMatcher.anyReached], in the order of the places, the [forced] ones first, and the first one beyond,
     * which tell the next choices, see [next], depth first, as the places following a choice depend on it,
     * or else, when [isMatchingFirstBeyond], any of them beyond the forced ones, the first one matched,
     * or none of them, when [isSkippingBeyond], these places matching any type, see [matchesChoosingPathsOneByOne].
     */
    private class PathChoices(
        private val forced: List<Int>,
        private val isMatchingFirstBeyond: Boolean = false,
        val isSkippingBeyond: Boolean = false
    ) {

        private val chosen = ArrayList<Int>()

        private val counts = ArrayList<Int>()

        // the index of the supertype chosen at the next place, of the given count of the supertypes,
        // or null beyond the forced ones, when they are not chosen there
        fun choose(count: Int): Int? {
            val index = forced.getOrNull(chosen.size) ?: if (isMatchingFirstBeyond || isSkippingBeyond) null else 0
            chosen += index ?: 0
            counts += count
            return index
        }

        // the count of the places chosen at
        val placeCount: Int get() = chosen.size

        // the count of the supertypes at the given place
        fun countAt(place: Int): Int = counts[place]

        // the choices to force next, the next supertype at the last place which has one, or null if none has
        fun next(): List<Int>? {
            val place = chosen.indices.lastOrNull { chosen[it] + 1 < counts[it] } ?: return null
            return chosen.subList(0, place) + (chosen[place] + 1)
        }

    }

    /**
     * The ids of the [typeParameters] of an extension the [types] refer to, also through the bounds of the ones they refer to,
     * like `E` of `T : List<E>` for `List<T>`, see [ReceiverMatcher.isInferred].
     */
    private fun typeParameterIdsReferredBy(types: List<KmType>, typeParameters: List<KmTypeParameter>): Set<Int> {
        val referred = HashSet<Int>()
        val byId = typeParameters.associateBy { it.id }
        fun refer(type: KmType) {
            type.refersOnlyTo { id ->
                if (referred.add(id)) byId[id]?.upperBounds?.forEach(::refer)
                true
            }
        }
        types.forEach(::refer)
        return referred
    }

    /**
     * The class bounds of the type parameter of the given [id], among the [typeParameters],
     * also the ones of a type parameter bounding it, like `A` of `T` in `fun <U : A, T : U> T.foo()`.
     */
    private fun receiverBoundTypes(id: Int, typeParameters: List<KmTypeParameter>, visited: MutableSet<Int> = HashSet()): List<KmType> {
        if (!visited.add(id)) return emptyList()
        val typeParameter = typeParameters.find { it.id == id } ?: return emptyList()
        return typeParameter.upperBounds.flatMap { bound ->
            when (val classifier = bound.classifier) {
                is KmClassifier.Class -> listOf(bound)
                is KmClassifier.TypeParameter -> receiverBoundTypes(classifier.id, typeParameters, visited)
                is KmClassifier.TypeAlias -> emptyList()
            }
        }
    }

    /**
     * Whether the class of the given [metadataName] is a final class, which no other class extends, like `kotlin.Int`,
     * or a Java final class, but not `kotlin.Any`, an interface, nor a class which cannot be loaded.
     */
    private fun isFinalClass(metadataName: String): Boolean {
        val declaration = classHeader(metadataName)
        return declaration != null && declaration.modality == Modality.FINAL && declaration.kind != ClassKind.INTERFACE
    }

    /**
     * The declaration of the class of the given [metadataName], a built-in one, the Kotlin metadata of a loaded class,
     * or the header of a Java class, see [FoundClass.javaClassHeader], or `null` if the class cannot be loaded.
     */
    private fun classHeader(metadataName: String): KmClass? =
        builtinClasses[metadataName] ?: findClassByMetadataName(metadataName)?.let { it.kmClass ?: it.javaClassHeader }

    /**
     * The declaration-site variances of the type parameters of the class of the given [metadataName],
     * like `in P1, out R` of a function type, see [FunctionTypeClass], invariant ones of a Java class,
     * see [FoundClass.javaClassHeader], or `null` if the class cannot be loaded.
     */
    private fun declaredVariances(metadataName: String): List<KmVariance>? {
        FunctionTypeClass.of(metadataName.replace('/', '.'))?.let { functionTypeClass ->
            return List(functionTypeClass.arity) { KmVariance.IN } + KmVariance.OUT
        }
        return classHeader(metadataName)?.typeParameters?.map { it.variance }
    }

    /**
     * This supertype of a class with the type arguments of a type of the class, the [projections], substituted
     * by the [substitute] function for its type parameters, keeping their projections, combined with the ones of this type,
     * like `AbstractList<out String>` of `ArrayList<out String>`, `Collection<*>` of `MutableList<*>`,
     * or `Collection<in String>` of `MutableList<in String>`, which stands for a star projection, see [projectedVariance],
     * and approximating a type argument the projected one is nested in by its supertype, like kotlinc,
     * like `List<MutableList<out String>>` of `Nest<out String>`, where `interface Nest<E> : List<MutableList<E>>`,
     * or `Box<out MutableList<out String>>` of an invariant `Box`, or `Comparable<*>` of a contravariant `Comparable`.
     */
    private fun KmType.substitutedProjecting(
        projections: TypeProjections,
        substitute: KmType.(substitution: Map<Int, KmType>) -> KmType
    ): KmType {
        val substituted = substitute(projections.substitution)
        return if (projections.projected.isEmpty()) substituted else approximated(substituted, projections).first
    }

    /**
     * The [substituted] type of this one, see [substitutedProjecting], with the projections of the [projections] kept,
     * paired with whether it is approximated by a supertype, which a type argument it is given for is projected for then.
     */
    private fun KmType.approximated(substituted: KmType, projections: TypeProjections): Pair<KmType, Boolean> {
        val projected = projections.projected
        val declared by lazy(LazyThreadSafetyMode.NONE) {
            (classifier as? KmClassifier.Class)?.name?.let(::declaredVariances)?.takeIf { it.size == arguments.size }
        }
        var isApproximated = false
        val approximatedArguments = substituted.arguments.mapIndexed { index, argument ->
            val original = arguments[index]
            val type = original.type ?: return@mapIndexed argument
            val id = (type.classifier as? KmClassifier.TypeParameter)?.id
            if (id != null) {
                if (id !in projected) return@mapIndexed argument
                isApproximated = true
                val projection = projections.projections.getValue(id)
                val variance = projection.variance?.let { combinedVariance(original.variance!!, it) }
                return@mapIndexed if (variance == null) KmTypeProjection.STAR else KmTypeProjection(variance, argument.type)
            }
            if (type.refersOnlyTo { it !in projected }) return@mapIndexed argument
            val (approximatedType, isTypeApproximated) = type.approximated(argument.type!!, projections)
            if (!isTypeApproximated) return@mapIndexed KmTypeProjection(argument.variance, approximatedType)
            isApproximated = true
            // a supertype of the type argument, in a covariant position, or else any type
            when (projectedVariance(declared?.get(index) ?: KmVariance.INVARIANT, original.variance!!)) {
                KmVariance.INVARIANT -> KmTypeProjection(KmVariance.OUT, approximatedType)
                KmVariance.OUT -> KmTypeProjection(original.variance, approximatedType)
                else -> KmTypeProjection.STAR
            }
        }
        // the outer type of an inner class, like Outer<out String> of Outer<E>.Inner
        val originalOuterType = outerType
        val substitutedOuterType = substituted.outerType
        val approximatedOuterType = if (originalOuterType != null && substitutedOuterType != null) {
            originalOuterType.approximated(substitutedOuterType, projections).also { (_, isOuterApproximated) ->
                if (isOuterApproximated) isApproximated = true
            }.first
        } else {
            substitutedOuterType
        }
        return substituted.copy(arguments = approximatedArguments, outerType = approximatedOuterType) to isApproximated
    }

    /**
     * What a [ReceiverMatcher] matched, and inferred, all the state of a match, which matching again starts anew,
     * see [ReceiverMatcher.matchingAgain], each of them told where the matcher refers to it.
     */
    private class Matched {
        val inferred = HashMap<Int, MutableList<Pair<KmType, KmVariance>>>()
        val inferredIds = ArrayList<Int>()
        val constrainedBy = ArrayList<Triple<Int, KmType, KmVariance>>()
        val boundsChecked = HashSet<Int>()
        val typesInferredFromBounds: MutableSet<KmType> = Collections.newSetFromMap(IdentityHashMap())
        var isInferringFromBounds = false
        var isUntold = false
        val classTypeParameterTypes = ArrayList<ClassTypeParameterType>()
        var isPlatformArgument = false
        var isMatchedBeyondInference = false
    }

    /**
     * A [type] of an extension which the type parameter of a class of the given [id] is matched against,
     * in a position of the given [variance], see [ReceiverMatcher.isClassTypeParameterTyped],
     * or else, when [isNotNullOf], in an invariant position, as definitely not nullable, like `V & Any`,
     * the type parameter being the type, or the nullable one of it, like `String`, or `String?`, of `String`,
     * like a platform type, `V!` of a Java supertype, matched against the nullable one, like `String?`.
     */
    private class ClassTypeParameterType(val id: Int, val type: KmType, val variance: KmVariance, val isNotNullOf: Boolean = false)

    /**
     * How a type parameter of an extension is inferred through its bound, see [ReceiverMatcher.isInferred],
     * fixed by kotlinc as the bound, or else in a way not modelled, inferred as before #292 then,
     * when the return type refers to it, which neither hides, nor lists, more than before.
     */
    private enum class BoundInference { FIXED, NOT_MODELLED }

    /**
     * Matches the types of an extension, declaring the [typeParameters], against the types of the supertypes of a class,
     * see [isCallableOn], inferring its type parameters from the types matched against each of them, see [infer],
     * and the type parameter receiver of the [receiverTypeParameterId] standing for the class of the metadata name [self].
     * When [isCollecting], the types matched against the type parameters are only collected, without telling
     * whether a type parameter can be all of them, nor whether they satisfy its bounds, which kotlinc tells
     * from all of them at once, see [isInferredByCall].
     */
    private inner class ReceiverMatcher(
        private val supertypes: ClassSupertypes,
        typeParameters: List<KmTypeParameter>,
        private val receiverTypeParameterId: Int?,
        private val self: String,
        private val isCollecting: Boolean = false
    ) {

        // the supertypes chosen among the ones reached through different paths, when matching again, see matchingAgain
        private var pathChoices: PathChoices? = null

        private val typeParameters = typeParameters.associateBy { it.id }

        // what is matched, all of it, forgotten as a whole when matching again, see matchingAgain
        private var matched = Matched()

        // the types each type parameter is inferred from, with the variances of their positions
        private val inferred get() = matched.inferred

        // the type parameters, in the order of what is inferred, see forgetInferredSince
        private val inferredIds get() = matched.inferredIds

        // the types the type parameters are inferred from by the types matched, with the variances of their positions,
        // without the ones inferred from their bounds, see satisfiesBounds, the constraints kotlinc infers them by, see CallInference
        private val constrainedBy get() = matched.constrainedBy

        // telling whether a type the type parameter is inferred from is a subtype of another one, or the same type, see infer,
        // without inferring the type parameters of the extension, whose ids the type parameters of the class can take
        private val typeMatcher by lazy(LazyThreadSafetyMode.NONE) {
            ReceiverMatcher(supertypes, emptyList(), receiverTypeParameterId = null, self)
        }

        // the type parameters whose bounds are being checked, see satisfiesBounds
        private val boundsChecked get() = matched.boundsChecked

        // the types inferred from the bounds of a type parameter of a contravariant position, see satisfiesBounds,
        // by their instances, which tell nothing kotlinc fixes a type parameter by, see satisfiesRecursiveBounds
        private val typesInferredFromBounds get() = matched.typesInferredFromBounds

        // whether the bounds of a type parameter of a contravariant position are inferring, see typesInferredFromBounds
        private var isInferringFromBounds
            get() = matched.isInferringFromBounds
            set(value) {
                matched.isInferringFromBounds = value
            }

        // the ids of the type parameters bounding each type parameter, also through others, like B, and A, of C for C : B, B : A
        private val boundingClosures by lazy(LazyThreadSafetyMode.NONE) {
            this.typeParameters.keys.associateWith { id ->
                val closure = HashSet<Int>()
                val pending = ArrayDeque(this.typeParameters.getValue(id).boundingTypeParameterIds())
                while (pending.isNotEmpty()) {
                    val next = pending.removeFirst()
                    if (closure.add(next)) pending += this.typeParameters[next]?.boundingTypeParameterIds().orEmpty()
                }
                closure
            }
        }

        // whether a type parameter bounds multiple other ones inferred from types, also through others,
        // like A of B : A, and C : A, or of C : B, B : A, for I<B, C>, or I<C, B>,
        // whose order of fixing kotlinc decides in ways not modelled, leniently not checked, see satisfiesRecursiveBounds
        private fun hasTypeParameterBoundingMultipleInferredOnes(): Boolean {
            val inferredClosures = boundingClosures.filterKeys { !inferred[it].isNullOrEmpty() }.values
            return typeParameters.keys.any { id -> inferredClosures.count { id in it } > 1 }
        }

        // whether a bound refers to a type parameter of the extension, like T : Comparable<T>, see satisfiesRecursiveBounds
        private val hasBoundsReferringToTypeParameters by lazy(LazyThreadSafetyMode.NONE) {
            this.typeParameters.values.any { typeParameter -> typeParameter.upperBounds.any { !it.refersToNoTypeParameter() } }
        }

        // whether what is not told was matched, like the type arguments of a class which cannot be loaded, see matchesUntold,
        // or a type parameter of the class, which stands for any type, so that what is inferred is not told, see isInferred
        private var isUntold
            get() = matched.isUntold
            set(value) {
                matched.isUntold = value
            }

        // the types of the extension each type parameter of the class is matched against, see isClassTypeParameterTyped
        private val classTypeParameterTypes get() = matched.classTypeParameterTypes

        /**
         * Whether each type parameter the receiver refers to, the [referred] ones, also through the bounds of the ones it refers to,
         * like `E` of `T : List<E>`, is inferred from the matched types, see [infer],
         * like not `T` of `I<List<T>>` for `I<List<*>>`, see [isTrivialStarConstraint], nor `E` of `R<T>` for `R<Nothing>`,
         * where kotlinc cannot infer it, unless it is the type parameter receiver standing for the class,
         * a bound of an inferred one, like `U` of `T : U`, or one bounded by inferred ones, like `T` of `T : R`, or of `T : List<R>`,
         * which kotlinc fixes as its bound, unless that inference is not modelled, see [inferenceByBound],
         * where it is inferred when the return type refers to it, the [referredByReturnType] ones, like before #292,
         * or one of the [inferredOtherwise] ones, also through their bounds,
         * like `E` of `T : List<E>` for `value: T`, see [typeParameterIdsReferredBy],
         * and the recursive bounds of the ones inferred only from contravariant positions are satisfied, see [satisfiesRecursiveBounds],
         * as far as what is matched is told, see [isUntold].
         * A type parameter the receiver does not refer to, like `T` of `fun <reified T> Registry.register()`,
         * is given explicitly, like on any other class.
         *
         * @param inferredOtherwise the ones the types of the context and value parameters of the extension refer to,
         *   which a call can infer them from, like `T` of `fun <T> Box<List<T>>.put(value: T)`,
         *   but not the return type, which only an expected type tells, so that `fun <T> Box<List<T>>.get(): T`
         *   is not callable on `Box<Nothing>`, of `interface Box<out T>`, as `box.get()` is not, like kotlinc.
         */
        fun isInferred(referred: Lazy<Set<Int>>, inferredOtherwise: Lazy<Set<Int>>, referredByReturnType: Lazy<Set<Int>>): Boolean {
            if (!isClassTypeParameterTyped()) return false
            if (isUntold || typeParameters.isEmpty()) return true
            val isInferred = referred.value.filterTo(HashSet()) { id ->
                id == receiverTypeParameterId || id !in typeParameters || !inferred[id].isNullOrEmpty() || id in inferredOtherwise.value
            }
            // whether a type parameter is inferred through a bound whose inference is not modelled, see inferenceByBound
            var isInferredAsBefore = false
            // a type parameter bounding an inferred one is its supertype, like U of T : U,
            // and one bounded by inferred ones is fixed by kotlinc as its bound, like T : R, or T : List<R>, for r: R
            do {
                val bounding = isInferred.flatMap { id -> typeParameters[id]?.boundingTypeParameterIds().orEmpty() }
                val bounded = typeParameters.keys.filter { id ->
                    id !in isInferred && when (inferenceByBound(id, isInferred)) {
                        BoundInference.FIXED -> true
                        BoundInference.NOT_MODELLED -> (id in referredByReturnType.value).also { if (it) isInferredAsBefore = true }
                        null -> false
                    }
                }
            } while (isInferred.addAll(bounding) or isInferred.addAll(bounded))
            return isInferred.containsAll(referred.value) && satisfiesRecursiveBounds {
                if (isInferredAsBefore) inferredOtherwise.value + referredByReturnType.value else inferredOtherwise.value
            }
        }

        /**
         * How the type parameter of the given [id] is inferred through its bounds: [BoundInference.NOT_MODELLED],
         * when a bound refers back to it through other type parameters, like `T : Comparable<U>` for `U : Comparable<T>`,
         * or else [BoundInference.FIXED] by kotlinc as a bound referring only to the [inferred] type parameters,
         * like `T : R`, or `T : List<R>`, also through a chain, like `T : U`, `U : R`,
         * unless what it refers to bounds another type parameter as well, like `R` of `T : R`, and `U : R`,
         * or has a recursive bound, like `R : Comparable<R>`, [BoundInference.NOT_MODELLED] then,
         * or `null`, when no bound refers only to inferred ones, like `T : R` for `R` not inferred, nor to other ones,
         * like `T : Comparable<T>`.
         */
        private fun inferenceByBound(id: Int, inferred: Set<Int>): BoundInference? {
            val inferences = typeParameters.getValue(id).upperBounds.mapNotNull { bound ->
                val referred = typeParameterIdsReferredBy(listOf(bound), typeParameters.values.toList())
                val others = referred - id
                if (others.isEmpty()) return@mapNotNull null
                if (id in referred) return@mapNotNull BoundInference.NOT_MODELLED
                if (!others.all { it in inferred }) return@mapNotNull null
                val isNotModelled = others.any { other ->
                    typeParameters.values.any { it.id != id && it.id !in referred && other in it.boundingTypeParameterIds() } ||
                        other in typeParameterIdsReferredBy(typeParameters.getValue(other).upperBounds, typeParameters.values.toList())
                }
                if (isNotModelled) BoundInference.NOT_MODELLED else BoundInference.FIXED
            }
            return inferences.minOrNull()
        }

        /**
         * Whether each type parameter of the class, standing for any type, see [matches], can take one type
         * in all the positions it is matched in, against the types of the extension referring to none of its type parameters,
         * like `V` of `HashMap<V, V>` not for `Map<String, Order>`, as the invariant key makes it `String`, which is no `Order`,
         * but for `Map<String, CharSequence>`: the same type in all the invariant positions,
         * a subtype of the types of the covariant ones, and a supertype of the types of the contravariant ones,
         * like `Nothing` of only covariant positions, or `Any?` of only contravariant ones.
         */
        private fun isClassTypeParameterTyped(): Boolean =
            classTypeParameterTypes.groupBy { it.id }.all { (id, positions) ->
                fun typesOf(variance: KmVariance) = positions.filter { it.variance == variance && !it.isNotNullOf }.map { it.type }
                val invariant = typesOf(KmVariance.INVARIANT)
                // the ones it is, or is the nullable type of, like String of V & Any, a subtype of String?, and a supertype of String
                val notNullOf = positions.filter { it.isNotNullOf }.map { it.type }
                val bounds = supertypes.kmClass.typeParameters.find { it.id == id }?.upperBounds.orEmpty()
                // also the bounds of the type parameter, like CharSequence of V : CharSequence, referring to no type parameter
                val upper = typesOf(KmVariance.OUT) + notNullOf.map { type -> type.copy().also { it.isNullable = true } } +
                    bounds.filter { bound -> bound.refersOnlyTo { false } }
                val lower = typesOf(KmVariance.IN) + notNullOf
                // whether the subtype is a subtype of the supertype, as far as told
                fun isSubtype(subtype: KmType, supertype: KmType) = typeMatcher.matches(supertype, subtype, KmVariance.OUT, isStrict = false)
                // the bounds referring only to itself, like Comparable<V> of V : Comparable<V>, of the type it takes
                val selfBounds = bounds.filter { bound -> !bound.refersOnlyTo { false } && bound.refersOnlyTo { it == id } }
                fun satisfiesSelfBounds(type: KmType) = selfBounds.all { bound -> isSubtype(type, bound.substituted(mapOf(id to type))) }
                when (val type = invariant.firstOrNull()) {
                    // either of the two types each of these leaves, which all the other ones leave as well
                    null -> lower.all { subtype -> upper.all { isSubtype(subtype, it) } } &&
                        notNullOf.all { notNull ->
                            listOf(notNull, notNull.copy().also { it.isNullable = true }).any { candidate ->
                                lower.all { isSubtype(it, candidate) } && upper.all { isSubtype(candidate, it) } &&
                                    satisfiesSelfBounds(candidate)
                            }
                        }
                    else -> invariant.all { typeMatcher.matches(it, type, KmVariance.INVARIANT, isStrict = false) } &&
                        upper.all { isSubtype(type, it) } && lower.all { isSubtype(it, type) } && satisfiesSelfBounds(type)
                }
            }

        // whether the type parameter of the class of the given id is bounded by a not nullable class, like V : Comparable<V>,
        // never nullable then, which used as definitely not nullable, like V & Any, is just itself
        private fun isNotNullableClassTypeParameter(id: Int?): Boolean =
            supertypes.kmClass.typeParameters.find { it.id == id }?.upperBounds.orEmpty()
                .any { it.classifier is KmClassifier.Class && !it.isNullable }

        /**
         * Whether the class of the [expected] type of the extension, like `List<T>`, can be the type parameter of the class [actual],
         * or its subtype, like in an invariant, or a contravariant, position: a subclass of the class of each of its bounds,
         * as far as they are told, like not `List`, no `Comparable`, of `V : Comparable<V>`,
         * while a subtype of it, like in a covariant position, can still be a class implementing both.
         */
        private fun isClassOfClassTypeParameter(expected: KmType, actual: KmType): Boolean {
            val name = (expected.classifier as? KmClassifier.Class)?.name ?: return true
            val id = (actual.classifier as? KmClassifier.TypeParameter)?.id ?: return true
            val bounds = supertypes.kmClass.typeParameters.find { it.id == id }?.upperBounds.orEmpty()
            return bounds.all { bound ->
                val boundName = (bound.classifier as? KmClassifier.Class)?.name
                boundName == null || supertypes.isSubclass(name, boundName)
            }
        }

        // whether this not nullable type of the extension is a class, or a type parameter bounded only by not nullable classes
        private fun KmType.isNeverNullable(): Boolean = when (val classifier = classifier) {
            is KmClassifier.Class -> true
            is KmClassifier.TypeParameter -> isDefinitelyNonNull || typeParameters[classifier.id]?.upperBounds.orEmpty().let { bounds ->
                bounds.isNotEmpty() && bounds.all { it.classifier is KmClassifier.Class && !it.isNullable }
            }
            is KmClassifier.TypeAlias -> false
        }

        /**
         * The types of the extension the type parameter of the class [actual] is matched against, see [isClassTypeParameterTyped],
         * with the variances of their positions, the [expected] type referring to no type parameter of the extension,
         * or else the bound of its type parameter, like `Order` of `T : Order` for `Map<String, T>`, which it is a subtype of,
         * in a covariant, or an invariant, position,
         * a platform type, when [isPlatform], like `V!` of a Java supertype, also in a type argument of one,
         * like `V!` of `(Mutable)List<V!>!`, being the type, or the nullable one of it, in an invariant position,
         * like `String`, or `String?`, of `String?`, a supertype of `String`, and a subtype of `String?`, but only `String` of `String`,
         * and a supertype of the not nullable one in a contravariant position,
         * and a nullable one, like `V?`, the not nullable type of a nullable one, or that nullable one in an invariant position,
         * while one used as definitely not nullable, like `V & Any`, unless never nullable, see [isNotNullableClassTypeParameter],
         * is a subtype of the type, of the nullable one then, like `String?` of `String`, and a supertype of it,
         * both of them in an invariant position, see [ClassTypeParameterType.isNotNullOf],
         * or none if nothing is told, like of a nullable one for a not nullable type, or of a type parameter bounded by another.
         */
        private fun classTypeParameterTypesOf(
            expected: KmType,
            actual: KmType,
            variance: KmVariance,
            isPlatform: Boolean
        ): List<ClassTypeParameterType> {
            val id = (actual.classifier as? KmClassifier.TypeParameter)?.id ?: return emptyList()
            val typeParameterId = (expected.classifier as? KmClassifier.TypeParameter)?.id
            val (type, typeVariance) = when {
                expected.refersToNoTypeParameter() -> expected to variance
                typeParameterId == null || variance == KmVariance.IN -> return emptyList()
                else -> {
                    val bound = typeParameters[typeParameterId]?.upperBounds?.singleOrNull()
                        ?.takeIf { it.refersToNoTypeParameter() && it.classifier is KmClassifier.Class } ?: return emptyList()
                    (if (expected.isNullable) bound.copy().also { it.isNullable = true } else bound) to KmVariance.OUT
                }
            }
            fun typed(type: KmType, variance: KmVariance) = ClassTypeParameterType(id, type, variance)
            return when {
                isPlatform -> when (typeVariance) {
                    KmVariance.OUT -> listOf(typed(type, KmVariance.OUT))
                    KmVariance.IN -> listOf(typed(type.notNullable(), KmVariance.IN))
                    KmVariance.INVARIANT -> when {
                        type.isNullable -> listOf(ClassTypeParameterType(id, type.notNullable(), KmVariance.INVARIANT, isNotNullOf = true))
                        else -> listOf(typed(type, KmVariance.INVARIANT))
                    }
                }
                actual.isDefinitelyNonNull && !isNotNullableClassTypeParameter(id) -> when (typeVariance) {
                    KmVariance.OUT -> listOf(typed(type.copy().also { it.isNullable = true }, KmVariance.OUT))
                    KmVariance.IN -> listOf(typed(type, KmVariance.IN))
                    KmVariance.INVARIANT -> listOf(ClassTypeParameterType(id, type, KmVariance.INVARIANT, isNotNullOf = true))
                }
                !actual.isNullable -> listOf(typed(type, typeVariance))
                // V? is String? for V of String, and of String?
                type.isNullable && typeVariance == KmVariance.INVARIANT ->
                    listOf(ClassTypeParameterType(id, type.notNullable(), KmVariance.INVARIANT, isNotNullOf = true))
                type.isNullable -> listOf(typed(type.notNullable(), typeVariance))
                else -> emptyList()
            }
        }

        // the ids of the type parameters bounding this one, like U of T : U
        private fun KmTypeParameter.boundingTypeParameterIds(): List<Int> =
            upperBounds.mapNotNull { (it.classifier as? KmClassifier.TypeParameter)?.id }

        /**
         * Whether the type parameters inferred only from contravariant positions, like `T` of `I<T>`, where `interface I<in T>`,
         * satisfy their bounds referring to type parameters, like `T : Comparable<T>`, as kotlinc infers each of them
         * as the intersection of the types it is inferred from and of the bounds, see [intersectionOf],
         * like `CharSequence & List<String>` of `where T : Comparable<T>, T : CharSequence` for `I<List<String>>`,
         * or `String` of `Cc<T, T>` for `Cc<String, CharSequence>`, where `interface Cc<in A, in B>`,
         * see [satisfiesFixedBounds], unless something else constrains it,
         * a covariant or an invariant position, also of a type parameter it bounds, like `A` of `B : A` in `Mx<B, A>`,
         * where `interface Mx<out A, in B>`, or a type the [inferredOtherwise] ids tell, like of a value parameter `value: T`,
         * which kotlinc infers it from instead.
         * Like kotlinc, they are fixed one by one, see [fixedTypes], first the first one declared bounding a type parameter
         * fixed before, like `A` of `B : A`, also one nothing else constrains, like `U` of `T : U`, as the type of that one,
         * so that `<T : U, U : Comparable<U>> I<T>` is no more callable on `I<List<String>>` than `<T : Comparable<T>> I<T>`,
         * and `A` of `<B : A, C : A, A : Comparable<A>> Cc<B, C>` takes the type of `B`, then the first one declared,
         * which a type tells, like `B` of `B : A` in `Cc<B, A>`, as the intersection, also of the type of `A`, if fixed before,
         * and the ones inferred only from `Any?`, or `Any!` of Java, which tell nothing, last,
         * while the types inferred from the bounds of another type parameter, like `U` of `T : Comparable<U>`,
         * tell nothing kotlinc fixes it by, which it infers by its own bounds then, unchecked.
         * An intersection no type is a subtype of, like of the final `Int` and of `CharSequence`, see [isEmptyIntersection],
         * is `Nothing` for kotlinc, which accepts the call then, even when another type parameter does not satisfy its bound,
         * like `A` of `<A : Comparable<A>, B : A> Cc<B, A>` for `Cc<Plain, Any>` of a final class `Plain`.
         * Not checked, like any other contravariant bound, are the type parameters of an extension
         * with a type parameter bounding multiple other ones inferred from types, see [hasTypeParameterBoundingMultipleInferredOnes],
         * or bounding ones fixed as intersections of multiple types.
         */
        private fun satisfiesRecursiveBounds(inferredOtherwise: () -> Set<Int>): Boolean {
            if (!hasBoundsReferringToTypeParameters || hasTypeParameterBoundingMultipleInferredOnes()) return true
            val upperTypes = HashMap<Int, List<KmType>>()
            inferred.forEach { (id, types) ->
                val upper = types.filter { (type, variance) -> variance == KmVariance.IN && type !in typesInferredFromBounds }
                    .map { it.first }
                if (upper.isNotEmpty()) upperTypes[id] = upper
            }
            if (upperTypes.isEmpty()) return true
            val otherwise = inferredOtherwise()
            // the ones only contravariant positions constrain, if any, by the order of the declaration
            val fixable = typeParameters.keys.filter { id ->
                !hasLowerConstraint(id, otherwise, HashSet()) && (id in upperTypes || inferred[id].isNullOrEmpty())
            }
            // when the order kotlinc fixes them in is not certain, any of the orders satisfying the bounds, see fixingSatisfiesBounds
            return retrying(Mark()) { fixingSatisfiesBounds(fixable, upperTypes, isBoundingLast = false) } ||
                retrying(Mark()) { fixingSatisfiesBounds(fixable, upperTypes, isBoundingLast = true) }
        }

        /**
         * Whether the [fixable] type parameters satisfy their bounds, fixed one by one, see [satisfiesRecursiveBounds],
         * and, when [isBoundingLast], the ones bounding other ones not fixed yet only after them, also when bounding fixed ones,
         * like `A` of `<A : Comparable<A>, B : A, C : A> I<B, C, A>`, for `I<String, Int, Any>`,
         * which kotlinc infers as `String`, of `B`, `C` inferred as `Nothing` then.
         */
        private fun fixingSatisfiesBounds(fixable: List<Int>, upperTypes: Map<Int, List<KmType>>, isBoundingLast: Boolean): Boolean {
            val fixed = LinkedHashMap<Int, List<KmType>>()
            val fixedAsBounded = HashSet<Int>()
            val notFixed = HashSet<Int>()
            // fixes the type parameter, telling whether its intersection is not empty
            fun fix(id: Int): Boolean {
                val bounded = fixed.keys.filter { id in typeParameters.getValue(it).boundingTypeParameterIds() }
                if (bounded.isNotEmpty()) fixedAsBounded += id
                when (val types = fixedTypes(id, bounded, upperTypes, fixed)) {
                    null -> notFixed += id
                    else -> fixed[id] = types.ifEmpty { return false }
                }
                return true
            }
            while (true) {
                val unfixed = fixable.filter { id -> id !in fixed && id !in notFixed }
                fun isBounding(id: Int) = unfixed.count { id in typeParameters.getValue(it).boundingTypeParameterIds() } > 1
                val next = unfixed.firstOrNull { id ->
                    (!isBoundingLast || unfixed.none { id in typeParameters.getValue(it).boundingTypeParameterIds() }) &&
                        fixed.keys.any { id in typeParameters.getValue(it).boundingTypeParameterIds() }
                }
                    ?: unfixed.firstOrNull { id ->
                        (!isBoundingLast || !isBounding(id)) && upperTypes[id].orEmpty().any { !it.isTriviallyAny }
                    }
                    ?: unfixed.firstOrNull { id -> upperTypes[id].orEmpty().any { !it.isTriviallyAny } }
                    ?: unfixed.firstOrNull { it in upperTypes }
                    ?: break
                if (!fix(next)) return true
            }
            val order = fixed.keys.toList()
            return fixed.all { (id, types) ->
                satisfiesFixedBounds(id, types, fixed, fixedBefore = order.subList(0, order.indexOf(id)).toSet(), id in fixedAsBounded)
            }
        }

        /**
         * The types kotlinc infers the type parameter of the given [id] as, when it bounds none of the [fixed] ones,
         * the intersection, see [intersectionOf], which is empty, when no type is a subtype of all of them,
         * or else the types of the [bounded] ones, or of the common supertype of them, when it is one of them,
         * like `CharSequence` of `String` and `CharSequence`, or `null`, when it is none of them.
         * When it is declared before one fixed as `Nothing`, kotlinc infers it as its bound with `Nothing` substituted,
         * like `Comparable<Nothing>` of `T : Comparable<T>` for `<T : Comparable<T>, U : T> I<U>` for `I<Nothing>`,
         * no `Comparable<Comparable<Nothing>>`.
         */
        private fun fixedTypes(
            id: Int,
            bounded: List<Int>,
            upperTypes: Map<Int, List<KmType>>,
            fixed: Map<Int, List<KmType>>
        ): List<KmType>? {
            if (bounded.isEmpty()) return intersectionOf(id, upperTypes, fixed) ?: emptyList()
            val types = bounded.map { fixed.getValue(it) }
            val common = types.singleOrNull() ?: types.mapNotNull { it.singleOrNull() }.takeIf { it.size == types.size }
                ?.let { singles -> singles.firstOrNull { candidate -> singles.all { isSubtype(it, candidate) } } }
                ?.let(::listOf)
                ?: return null
            val order = typeParameters.keys.toList()
            val isBeforeNothing = bounded.any { other ->
                order.indexOf(id) < order.indexOf(other) && fixed.getValue(other).singleOrNull()?.isNothing == true
            }
            val selfReferring = typeParameters.getValue(id).upperBounds.singleOrNull { !it.refersOnlyTo { other -> other != id } }
            return if (isBeforeNothing && selfReferring != null) {
                listOf(selfReferring.substituted(mapOf(id to classType(NOTHING_CLASS_NAME))))
            } else {
                common
            }
        }

        // whether the subtype is a subtype of the supertype, see typeMatcher
        fun isSubtype(subtype: KmType, supertype: KmType): Boolean =
            typeMatcher.matches(supertype, subtype, KmVariance.OUT, isStrict = false)

        // whether this type is Any?, or Any! of Java, telling nothing about a type parameter inferred from it in a contravariant position
        private val KmType.isTriviallyAny: Boolean
            get() = isNullableAny() || ((classifier as? KmClassifier.Class)?.name == ANY_CLASS_NAME &&
                flexibleTypeUpperBound?.type?.isNullableAny() == true)

        // whether this type is Nothing, not nullable
        private val KmType.isNothing: Boolean
            get() = !isNullable && isNothingClass

        // whether this type is Nothing, or Nothing?
        private val KmType.isNothingClass: Boolean
            get() = (classifier as? KmClassifier.Class)?.name == NOTHING_CLASS_NAME

        // whether this type refers to no type parameter of the extension, like CharSequence, unlike Comparable<T>
        private fun KmType.refersToNoTypeParameter(): Boolean = refersOnlyTo { it !in typeParameters }

        /**
         * The types of the intersection kotlinc infers the type parameter of the given [id] as, inferred only from
         * contravariant positions, the [upperTypes] of it, its bounds referring to no type parameter, like `CharSequence`,
         * but the [fixed] ones of a single type, like `List<CharSequence>` of `T : List<U>` for `U` fixed as `CharSequence`,
         * and the ones of the type parameters bounding it, also through the ones bounding them,
         * like `U`, and `A`, of `T : U`, `U : A`, the types of the ones already [fixed], and their bounds of these types,
         * like `Comparable<Any>` of `A : Comparable<A>` fixed as `Any`, and else the upper types of them,
         * without the ones which are supertypes of another one, like `CharSequence` of `String`,
         * or `null` if no type is a subtype of all of them, see [isEmptyIntersection].
         */
        private fun intersectionOf(id: Int, upperTypes: Map<Int, List<KmType>>, fixed: Map<Int, List<KmType>>): List<KmType>? {
            val typeParameter = typeParameters.getValue(id)
            val types = ArrayList(upperTypes.getValue(id))
            val substitution = fixed.filterValues { it.size == 1 }.mapValues { (_, fixedTypes) -> fixedTypes.single() }
            // also the ones referring to the type parameters fixed before, like List<CharSequence> of T : List<U> for U of CharSequence
            typeParameter.upperBounds.map { it.substituted(substitution) }.filterTo(types) { it.refersToNoTypeParameter() }
            // not nullable, when any of them is not, also a bound of a class, like T : Comparable<T>, or of a type parameter bounding it
            fun KmTypeParameter.isNotNullable() = upperBounds.any { !it.isNullable && it.classifier is KmClassifier.Class }
            var isNotNullable = typeParameter.isNotNullable()
            val visited = hashSetOf(id)
            val bounding = ArrayDeque(typeParameter.boundingTypeParameterIds())
            while (bounding.isNotEmpty()) {
                val boundingId = bounding.removeFirst()
                val boundingParameter = typeParameters[boundingId]
                if (!visited.add(boundingId) || boundingParameter == null) continue
                if (boundingParameter.isNotNullable()) isNotNullable = true
                val fixedTypes = fixed[boundingId]
                if (fixedTypes != null) {
                    types += fixedTypes
                    boundingParameter.upperBounds.map { it.substituted(substitution) }.filterTo(types) { it.refersToNoTypeParameter() }
                } else {
                    types += upperTypes[boundingId].orEmpty()
                    boundingParameter.upperBounds.map { it.substituted(substitution) }.filterTo(types) { it.refersToNoTypeParameter() }
                }
                bounding += boundingParameter.boundingTypeParameterIds()
            }
            // the classes of the bounds referring to the type parameter only covariantly, like List of T : List<T>, count as well,
            // and of the ones referring only to other type parameters not fixed yet, like Comparable of T : Comparable<B>
            val referring = typeParameter.upperBounds.filter { bound ->
                bound.classifier is KmClassifier.Class &&
                    (bound.isSelfReferringOnlyIn(id, KmVariance.OUT) || bound.refersOnlyTo { it != id })
            }
            if (isEmptyIntersection(types + referring)) return null
            if (types.any { !it.isNullable }) isNotNullable = true
            val intersected = if (isNotNullable) types.map { if (it.isNullable) it.notNullable() else it } else types
            // a supertype of another one tells nothing more, the first of the same ones kept
            return intersected.filterIndexed { index, type ->
                intersected.indices.none { other ->
                    other != index && isSubtype(intersected[other], type) && (other < index || !isSubtype(type, intersected[other]))
                }
            }
        }

        /**
         * Whether no type is a subtype of all the [types], when one of them is of a final class which is no subclass of another one,
         * like `Int`, and `CharSequence`, or `List`, or of two classes which are not interfaces, neither of them a subclass of the other,
         * like `Number`, and an open class, as far as their classes tell, the intersection kotlinc infers as `Nothing` then.
         */
        private fun isEmptyIntersection(types: List<KmType>): Boolean {
            // Nothing is a subtype of any type
            val names = types.mapNotNullTo(LinkedHashSet()) { (it.classifier as? KmClassifier.Class)?.name } - NOTHING_CLASS_NAME
            if (names.size < 2) return false
            fun isClass(name: String) = classHeader(name)?.kind
                .let { it != null && it != ClassKind.INTERFACE && it != ClassKind.ANNOTATION_CLASS }
            return names.any { name ->
                (isFinalClass(name) && names.any { other -> !supertypes.isSubclass(name, other) }) ||
                    (isClass(name) && names.any { other ->
                        isClass(other) && !supertypes.isSubclass(name, other) && !supertypes.isSubclass(other, name)
                    })
            }
        }

        /**
         * Whether the type parameter of the given [id], inferred as the intersection of the [types], see [intersectionOf],
         * satisfies its bounds referring to itself, like `T : Comparable<T>`,
         * or, when it [isFixedAsBounded], as the type of the one it bounds, like `U` of `T : U`, its bounds referring to type parameters,
         * like `U : Comparable<T>`, or `U : List<E>`, which the type of the one it bounds is no subtype of then, see [satisfiesBounds],
         * unless they refer to a type parameter fixed after it, not among the ones [fixedBefore], which kotlinc fixes by them,
         * these substituted for the types they are fixed as, when of a single type,
         * a single type checked against the bound, see [satisfiesRecursiveBound], and an intersection when any of its types is
         * a subtype of the bound, which refers to the type parameter itself either only contravariantly,
         * like `Comparable<T>`, satisfied when it is substituted for any of the types, as the intersection is a subtype of each,
         * or else, like `List<T>`, when it is substituted for each of them.
         */
        private fun satisfiesFixedBounds(
            id: Int,
            types: List<KmType>,
            fixed: Map<Int, List<KmType>>,
            fixedBefore: Set<Int>,
            isFixedAsBounded: Boolean
        ): Boolean {
            val others = fixed.filter { (other, otherTypes) -> other in fixedBefore && otherTypes.size == 1 }
                .mapValues { (_, otherTypes) -> otherTypes.single() }
            return typeParameters.getValue(id).upperBounds.all { bound ->
                // the other bounds are types of the intersection, not referring to a type parameter fixed after it,
                // nor to one fixed before as an intersection
                val isChecked = if (isFixedAsBounded) !bound.refersToNoTypeParameter() else !bound.refersOnlyTo { it != id }
                if (!isChecked || !bound.refersOnlyTo { it == id || it in others || it !in fixed }) {
                    return@all true
                }
                val substituted = bound.substituted(others)
                types.singleOrNull()?.let { return@all satisfiesRecursiveBound(id, substituted, it) }
                val isContravariant = substituted.isSelfReferringOnlyIn(id, KmVariance.IN)
                types.any { type ->
                    fun isSatisfiedFor(other: KmType) = retrying(Mark()) {
                        matches(substituted.substituted(mapOf(id to other)), type, KmVariance.OUT, isStrict = false)
                    }
                    if (isContravariant) types.any(::isSatisfiedFor) else types.all(::isSatisfiedFor)
                }
            }
        }

        // whether something else than a contravariant position constrains the type parameter, see satisfiesRecursiveBounds
        private fun hasLowerConstraint(id: Int, inferredOtherwise: Set<Int>, visited: MutableSet<Int>): Boolean =
            visited.add(id) && (
                id == receiverTypeParameterId || id in inferredOtherwise ||
                    inferred[id].orEmpty().any { (_, variance) -> variance != KmVariance.IN } ||
                    typeParameters.values.any { typeParameter ->
                        id in typeParameter.boundingTypeParameterIds() && hasLowerConstraint(typeParameter.id, inferredOtherwise, visited)
                    }
                )

        /**
         * Whether the type parameter of the given [id], inferred as the [actual] type, not nullable for a not nullable [bound],
         * like `String` of `T : Comparable<T>` for `I<String?>`, satisfies the bound referring to it,
         * or else a bound referring to it only covariantly, like `T : List<T>`, is satisfied by a final class
         * which is no subclass of the class of the bound, like `Int`, which kotlinc accepts, unlike `Any`.
         */
        private fun satisfiesRecursiveBound(id: Int, bound: KmType, actual: KmType): Boolean {
            val inferredType = if (bound.isNullable || !actual.isNullable) actual else actual.notNullable()
            if (bound.isSelfReferringOnlyIn(id, KmVariance.OUT)) {
                val name = (inferredType.classifier as? KmClassifier.Class)?.name
                val boundName = (bound.classifier as? KmClassifier.Class)?.name
                if (name != null && boundName != null && isFinalClass(name) && !supertypes.isSubclass(name, boundName)) return true
            }
            return matches(bound.substituted(mapOf(id to inferredType)), inferredType, KmVariance.OUT, isStrict = false)
        }

        // matches what is not told, unless isStrict, see argumentsMatch
        private fun matchesUntold(isStrict: Boolean): Boolean {
            if (!isStrict) isUntold = true
            return !isStrict
        }

        /**
         * Whether the class of the type parameter receiver is a subtype of the [bound].
         */
        fun isSupertype(bound: KmType): Boolean {
            val classifier = bound.classifier as? KmClassifier.Class ?: return true
            if (classifier.name == ANY_CLASS_NAME) return true
            val name = classReceiverIndexKey(bound)
            if (name != self.replace('/', '.') && name !in supertypes.supertypeNamesOf(self)) return false
            val reached = supertypes.types[name] ?: return matchesUntold(isStrict = false)
            return anyReached(reached) { supertype -> argumentsMatch(classifier.name, bound.arguments, supertype.arguments, KmVariance.OUT) }
        }

        /**
         * Whether any of the supertypes of the same class reached through different paths, the [reached] ones, see [keptSupertypes],
         * is matched by the [match] function, forgetting what is inferred, and whether what is not told was matched,
         * see [isUntold], by the ones which are not matched, see [retrying],
         * or else only the one of the [pathChoices], when matched again through each of them, see [matchesThroughAnyPaths].
         */
        fun anyReached(reached: List<KmType>, match: (supertype: KmType) -> Boolean): Boolean {
            val mark = Mark()
            if (reached.size > 1) hasPathChoices = true
            // the one chosen, when matched again through any paths, see matchesThroughAnyPaths
            val choices = pathChoices?.takeIf { reached.size > 1 }
            val index = when (choices) {
                null -> null
                // or none beyond the chosen ones, the place matching any type, see matchesChoosingPathsOneByOne
                else -> choices.choose(reached.size) ?: if (choices.isSkippingBeyond) return true else null
            }
            if (index == null) return reached.any { supertype -> retrying(mark) { match(supertype) } }
            return retrying(mark) { match(reached[index]) }
        }

        /**
         * This matcher, matching again, with nothing matched yet, the supertypes reached through different paths
         * chosen by the [choices], see [anyReached].
         */
        fun matchingAgain(choices: PathChoices): ReceiverMatcher {
            pathChoices = choices
            matched = Matched()
            return this
        }

        /**
         * Whether the supertypes of the same class reached through different paths were matched, see [anyReached],
         * which another choice of them could match otherwise, see [matchesThroughAnyPaths].
         */
        var hasPathChoices = false
            private set

        /**
         * Whether the [expected] type arguments of the class of the given [metadataName] match the [actual] ones,
         * in a position of the given [variance], see [matches], each of them, see [argumentMatches].
         *
         * @param isStrict whether what is not told, like the type arguments of a class which cannot be loaded,
         *   or a supertype reached through a raw Java type, no longer matches any type, see [matchesMutable].
         */
        fun argumentsMatch(
            metadataName: String,
            expected: List<KmTypeProjection>,
            actual: List<KmTypeProjection>,
            variance: KmVariance,
            isStrict: Boolean = false
        ): Boolean {
            if (expected.size != actual.size) return matchesUntold(isStrict)
            val declaredVariances = declaredVariances(metadataName)?.takeIf { it.size == expected.size }
                ?: return matchesUntold(isStrict)
            return expected.indices.all { index ->
                argumentMatches(expected[index], actual[index], declaredVariances[index], variance, isStrict)
            }
        }

        /**
         * Whether the [expected] type argument, of a type parameter of the [declaredVariance], matches the [actual] one
         * in a position of the given [variance], see [matches]:
         * in a covariant position, the expected one contains the actual one, like `out CharSequence` contains `String`,
         * or `out String`, in a contravariant one, the actual one contains the expected one,
         * and in an invariant one, they are the same, like a redundant `out String` of `List`, and `String`,
         * but not `MutableList<out String>`, and `MutableList<String>`.
         * A star projection is `out Any?`, or `in Nothing` of a contravariant type parameter, see [projected],
         * so that `List<*>` is the same type as `List<Any?>`.
         * A projection of the other variance is contained only by its bound, like `in String` in `out T`, for `T` of `Any?`,
         * or in `out Any?`, which contains any type argument then, like a star projection,
         * which, of an invariant type parameter, is the same as `in Nothing` as well, like `MutableList<*>` and `MutableList<in Nothing>`,
         * whichever of them is expected, while the `out` projection of the bound of a bounded type parameter,
         * like `BH<out CharSequence>` of `class BH<T : CharSequence>`, the same type as `BH<*>` too, is not told to be.
         * A Java wildcard is a projection, like `out String!` of `? extends String`, see [javaSignatureType].
         */
        private fun argumentMatches(
            expected: KmTypeProjection,
            actual: KmTypeProjection,
            declaredVariance: KmVariance,
            variance: KmVariance,
            isStrict: Boolean
        ): Boolean {
            val (expectedVariance, expectedType) = expected.projected(declaredVariance)
            val (actualVariance, actualType) = actual.projected(declaredVariance)
            val isProjection = expected.type == null || actual.type == null ||
                expected.variance != KmVariance.INVARIANT || actual.variance != KmVariance.INVARIANT
            if (isProjection && expected.type?.refersToNoTypeParameter() == false) isMatchedBeyondInference = true
            // a type parameter of the extension in a projection standing for a star projection, like T of List<in T>,
            // the supertype of MutableList<in T>, which kotlinc still infers, unlike this matcher, see isInferred,
            // but not from a star projection, like of List<*>, which tells nothing about it
            val expectedProjected = expected.type
            if (expectedProjected != null && projectedVariance(declaredVariance, expected.variance!!) == null &&
                !expectedProjected.refersOnlyTo { it !in typeParameters } && actual.type != null
            ) {
                isUntold = true
            }
            if (expectedVariance == actualVariance) return matches(expectedType, actualType, variance.compose(expectedVariance), isStrict)
            if (variance == KmVariance.INVARIANT) {
                // a star projection of an invariant type parameter, the same as in Nothing, and as out Any?, either way
                if (declaredVariance != KmVariance.INVARIANT) return false
                fun isStar(variance: KmVariance, type: KmType) =
                    if (variance == KmVariance.IN) type.isNothing else variance == KmVariance.OUT && type.isNullableAny()
                return (isStar(expectedVariance, expectedType) && isStar(actualVariance, actualType)) ||
                    (actual.type == null && expectedVariance == KmVariance.IN &&
                        matches(expectedType, classType(NOTHING_CLASS_NAME), KmVariance.INVARIANT, isStrict))
            }
            // the variance of the containing type argument, and of the contained one
            val (containing, contained) = if (variance == KmVariance.IN) {
                actualVariance to expectedVariance
            } else {
                expectedVariance to actualVariance
            }
            if (containing == KmVariance.INVARIANT) return false
            // an invariant type argument is contained in a projected one, like String in out String
            if (contained == KmVariance.INVARIANT) return matches(expectedType, actualType, variance.compose(containing), isStrict)
            // a projection of the other variance by its bound, like in String by Any? in out T
            val bound = if (containing == KmVariance.OUT) STAR.second else STAR_OF_CONTRAVARIANT.second
            return if (variance == KmVariance.IN) {
                matches(bound, actualType, variance.compose(containing), isStrict)
            } else {
                matches(expectedType, bound, variance.compose(containing), isStrict)
            }
        }

        /**
         * The variance and the type of this type argument of a type parameter of the [declaredVariance],
         * see [projectedVariance], `out Any?` of a star projection, or `in Nothing` of a contravariant type parameter,
         * like of a projection contradicting the type parameter, like `in String` of `List`,
         * also of a Java wildcard, like of `List<? super String>`.
         */
        private fun KmTypeProjection.projected(declaredVariance: KmVariance): Pair<KmVariance, KmType> {
            val type = type
            val variance = if (type != null) projectedVariance(declaredVariance, variance!!) else null
            return when {
                type != null && variance != null -> variance to type
                declaredVariance == KmVariance.IN -> STAR_OF_CONTRAVARIANT
                else -> STAR
            }
        }

        /**
         * Whether the [expected] type of the extension matches the [actual] one of a supertype of the class:
         * the same, or, in a covariant position, a supertype of it, in a contravariant one, a subtype of it,
         * of the type arguments matched as well, see [supertypesOf],
         * while a type parameter of the class stands for any type, other than the class itself.
         */
        private fun matches(expected: KmType, actual: KmType, variance: KmVariance, isStrict: Boolean): Boolean {
            val isPlatformPosition = isPlatformArgument
            isPlatformArgument = actual.flexibleTypeUpperBound != null
            try {
                return matchesType(expected, actual, variance, isStrict, isPlatformPosition)
            } finally {
                isPlatformArgument = isPlatformPosition
            }
        }

        // whether the types matched are the type arguments of a platform type, like T of (Mutable)List<T!>! of a Java supertype,
        // where a type parameter of the class is a platform type too, which the substitution leaves as is, see matchesType
        private var isPlatformArgument
            get() = matched.isPlatformArgument
            set(value) {
                matched.isPlatformArgument = value
            }

        /**
         * Whether the [expected] type matches the [actual] one, see [matches],
         * the [actual] one a type argument of a platform type when [isPlatformPosition].
         */
        private fun matchesType(
            expected: KmType,
            actual: KmType,
            variance: KmVariance,
            isStrict: Boolean,
            isPlatformPosition: Boolean
        ): Boolean {
            val actualClassifier = actual.classifier as? KmClassifier.Class
                // a type parameter bounded by the class itself, like `E` of `Enum<E : Enum<E>>`, stands for the class
                ?: KmClassifier.Class(supertypes.kmClass.name).takeIf { supertypes.isSelfTypeParameter(actual) }
                // any other one for any type, but for the class itself,
                // like of `inner class Outer<T>.Inner : Comparable<T>`, comparable to `T`,
                // the same one in all its positions, see isClassTypeParameterTyped
                ?: return (receiverTypeParameterId == null ||
                    (expected.classifier as? KmClassifier.TypeParameter)?.id != receiverTypeParameterId) && run {
                    // a platform type, like V! of a Java supertype, unless made nullable, like V? of V! given for T?
                    val isPlatform = isPlatformPosition || (actual.flexibleTypeUpperBound != null && !actual.isNullable)
                    // a nullable one, like V?, is no type of a class, like String, in a covariant, or an invariant position,
                    // nor a type parameter bounded only by classes, like T : Order
                    (!actual.isNullable || isPlatform || variance == KmVariance.IN || expected.isNullable || !expected.isNeverNullable()) &&
                        // nor is a definitely not nullable one, like V & Any, a nullable type, like String?, or T?, only a subtype of it
                        (!actual.isDefinitelyNonNull || isPlatform || variance == KmVariance.OUT || !expected.isNullable) &&
                        (variance == KmVariance.OUT || isClassOfClassTypeParameter(expected, actual)) &&
                        matchesUntold(isStrict = false).also {
                            // a platform type itself, not one of a type argument given by Kotlin to a platform type,
                            // like V of Pair<V, V>!, the same type in all its positions
                            val isPlatformType = actual.flexibleTypeUpperBound != null && !actual.isNullable
                            classTypeParameterTypes += classTypeParameterTypesOf(expected, actual, variance, isPlatformType)
                        }
                }
            return when (val classifier = expected.classifier) {
                is KmClassifier.TypeParameter -> when (classifier.id) {
                    receiverTypeParameterId ->
                        isNullabilityMatched(expected, actual, variance) && when (variance) {
                            KmVariance.INVARIANT -> actualClassifier.name == self
                            KmVariance.IN -> supertypes.isSubclass(self, actualClassifier.name)
                            KmVariance.OUT -> supertypes.isSubclass(actualClassifier.name, self)
                        }
                    in typeParameters -> {
                        if (expected.isNullable || expected.isDefinitelyNonNull) isMatchedBeyondInference = true
                        when {
                            !expected.isDefinitelyNonNull ->
                                inferredFrom(expected, actual, variance)?.let { infer(classifier.id, it, variance, isStrict) } == true
                            // a nullable type is no definitely not nullable one, like String? for T & Any, only a supertype of it
                            actual.isNullable -> variance == KmVariance.IN && infer(classifier.id, actual, variance, isStrict)
                            // a platform type, like String!, is either String, or String?, as the type parameter can be
                            actual.flexibleTypeUpperBound != null -> infer(classifier.id, actual, variance, isStrict)
                            // and a not nullable one, like String, is T & Any of T of String, or of String?,
                            // a supertype of String, and a subtype of String?, satisfying the bounds as String itself
                            // in an invariant position, where no supertype of it can be inferred instead
                            else -> (variance != KmVariance.INVARIANT || satisfiesBounds(classifier.id, actual, variance, isStrict)) &&
                                (variance == KmVariance.IN || infer(classifier.id, actual, KmVariance.OUT, isStrict)) &&
                                (variance == KmVariance.OUT ||
                                    infer(classifier.id, actual.copy().also { it.isNullable = true }, KmVariance.IN, isStrict))
                        }
                    }
                    else -> true
                }
                // a raw type of a Java supertype, either the actual one, or the expected one, see matchesRaw
                is KmClassifier.Class if (actual.isJavaRaw || expected.isJavaRaw) -> matchesRaw(expected, actual, variance, isStrict)
                is KmClassifier.Class -> isNullabilityMatched(expected, actual, variance) && run {
                    // a Java collection, like (Mutable)List<String!>! of java.util.List<String>, is the read-only one,
                    // or else the mutable one, see isJavaCollection, either the actual one,
                    // or the expected one, of a supertype of the type argument of the extension, see classMatches,
                    // or in an invariant position, any type between them, see matchesMutable
                    val mark = Mark()
                    classMatches(expected, classifier.name, actual, actualClassifier.name, variance, isStrict) ||
                        ((actual.isJavaCollection || expected.isJavaCollection) && run {
                            val mutable = actual.mutableAlternative()
                            val expectedMutable = expected.mutableAlternative()
                            val isInvariant = variance == KmVariance.INVARIANT
                            (mutable != null && retrying(mark) { matchesMutable(expected, mutable, variance) }) ||
                                (expectedMutable != null && retrying(mark) { matchesMutable(expectedMutable, actual, variance) }) ||
                                (mutable != null && isInvariant && retrying(mark) {
                                    matchesMutable(expected, mutable, KmVariance.OUT) && matchesMutable(expected, actual, KmVariance.IN)
                                }) ||
                                (expectedMutable != null && isInvariant && retrying(mark) {
                                    matchesMutable(expectedMutable, actual, KmVariance.IN) && matchesMutable(expected, actual, KmVariance.OUT)
                                })
                        })
                }
                is KmClassifier.TypeAlias -> matchesUntold(isStrict)
            }
        }

        /**
         * The type a type parameter of the [expected] type is inferred from, the [actual] type in a position of the given
         * [variance], without the nullability of a nullable type parameter, like `String` of `String?` for `T?`
         * in a covariant position, while it can be `String?` in a contravariant one, and either in an invariant one,
         * like the platform type `String!`, also made nullable, like `String!` substituted for `T?`,
         * or `null` if a not nullable type cannot be a nullable type parameter,
         * like `String` for `T?` in a contravariant, or an invariant position.
         */
        private fun inferredFrom(expected: KmType, actual: KmType, variance: KmVariance): KmType? = when {
            !expected.isNullable -> actual
            // a platform type made nullable, like String! substituted for T of W<T?>, infers T? as String!, not nullable
            actual.flexibleTypeUpperBound != null -> if (actual.isNullable) actual.copy().also { it.isNullable = false } else actual
            !actual.isNullable -> actual.takeIf { variance == KmVariance.OUT }
            variance == KmVariance.IN -> actual
            else -> actual.copy().also { type ->
                type.isNullable = false
                if (variance == KmVariance.INVARIANT) {
                    type.flexibleTypeUpperBound = KmFlexibleTypeUpperBound(actual.copy(), typeFlexibilityId = null)
                }
            }
        }

        /**
         * Whether the [expected] type matches the [actual] one, see [matches], either of them a raw type of a Java supertype,
         * see [isJavaRaw], any type between its erasure, like `MutableMap<Any?, Any?>` of `Map`,
         * and its star projection, like `Map<*, *>`, as kotlinc sees it:
         * in a covariant position, the erasure is a subtype of the expected type, in a contravariant one,
         * the expected type is a subtype of the star projection, and in an invariant one, both.
         * The type parameters of the expected type are inferred from nothing then, unlike by kotlinc, see [isInferred],
         * but in a contravariant position, when only the actual one is raw, they are only trivially constrained by its star projection,
         * like `T` of `I<List<T>>` for `I<List>`, where `interface I<in T>`, which kotlinc cannot infer then,
         * see [isTrivialStarConstraint].
         */
        private fun matchesRaw(expected: KmType, actual: KmType, variance: KmVariance, isStrict: Boolean): Boolean {
            val (expectedErasure, expectedStar) = expected.rawBounds()
            val (actualErasure, actualStar) = actual.rawBounds()
            val isSubtype = variance == KmVariance.IN || matches(expectedStar, actualErasure, KmVariance.OUT, isStrict)
            return isSubtype && (variance == KmVariance.OUT || matches(expectedErasure, actualStar, KmVariance.IN, isStrict)) &&
                ((variance == KmVariance.IN && !expected.isJavaRaw) || matchesUntold(isStrict = false))
        }

        // forgets what is inferred since the given count of the inferredIds
        private fun forgetInferredSince(count: Int) {
            while (inferredIds.size > count) {
                inferred.getValue(inferredIds.removeAt(inferredIds.lastIndex)).let { it.removeAt(it.lastIndex) }
            }
        }

        // what is inferred, and whether what is not told was matched, see isUntold, at a point of the matching, see retrying
        private inner class Mark(
            val inferredCount: Int = inferredIds.size,
            val isUntold: Boolean = this@ReceiverMatcher.isUntold,
            val classTypeParameterTypeCount: Int = classTypeParameterTypes.size,
            val constrainedByCount: Int = constrainedBy.size,
            val isMatchedBeyondInference: Boolean = this@ReceiverMatcher.isMatchedBeyondInference
        ) {

            // goes back to this point, forgetting what is inferred since, and whether what is not told was matched since
            fun restore() {
                forgetInferredSince(inferredCount)
                this@ReceiverMatcher.isUntold = isUntold
                while (classTypeParameterTypes.size > classTypeParameterTypeCount) {
                    classTypeParameterTypes.removeAt(classTypeParameterTypes.lastIndex)
                }
                while (constrainedBy.size > constrainedByCount) constrainedBy.removeAt(constrainedBy.lastIndex)
                this@ReceiverMatcher.isMatchedBeyondInference = isMatchedBeyondInference
            }

        }

        // whether the alternative matches, going back to the given mark before, and when it does not
        private inline fun retrying(mark: Mark, alternative: () -> Boolean): Boolean {
            mark.restore()
            return alternative().also { isMatched -> if (!isMatched) mark.restore() }
        }

        /**
         * Whether the [expected] type of the class of the given [name] matches the [actual] type of the class of the given
         * [actualName] in a position of the given [variance], see [matches], ignoring their nullability.
         * A supertype keeps the projections of the type arguments, like `MutableCollection<out String>` of `MutableList<out String>`,
         * see [substitutedProjecting], and when [isStrict], see [argumentsMatch], it is matched only when it is told, see [supertypesOf].
         */
        private fun classMatches(
            expected: KmType,
            name: String,
            actual: KmType,
            actualName: String,
            variance: KmVariance,
            isStrict: Boolean
        ): Boolean {
            if (name == actualName) {
                if (!actual.isJavaArray && !expected.isJavaArray) {
                    return argumentsMatch(name, expected.arguments, actual.arguments, variance, isStrict)
                }
                // a Java array, like Array<(out) String!>!, either the actual one, or the expected one,
                // is the invariant one, or else the covariant one
                val mark = Mark()
                return argumentsMatch(name, expected.arguments, actual.arguments, variance, isStrict) ||
                    retrying(mark) {
                        argumentsMatch(name, expected.javaArrayArguments(), actual.javaArrayArguments(), variance, isStrict)
                    }
            }
            // any type is an Any?, and Nothing is any type, as far as their nullability matches, see matches
            if (variance == KmVariance.OUT && (name == ANY_CLASS_NAME || actualName == NOTHING_CLASS_NAME)) return true
            if (variance == KmVariance.IN && (actualName == ANY_CLASS_NAME || name == NOTHING_CLASS_NAME)) return true
            // the type arguments of the supertype matched, like the String of Comparable<String> of String
            // any of them, when reached through different paths, see anyReached
            fun supertypeMatches(subtype: KmType, supertypeName: String, match: (supertype: KmType) -> Boolean): Boolean {
                val reached = supertypes.supertypesOf(subtype, supertypeName) ?: return matchesUntold(isStrict)
                return anyReached(reached, match)
            }
            // a supertype without type arguments is matched by its name, also when its type is not told,
            // like java.io.Serializable of String, see jvmSupertypeNamesOfBuiltins, as far as the supertypes of the subtype
            // are told when isStrict, unlike of a class which cannot be loaded, see isSubclass
            fun isMatchedByName(supertype: KmType, subtypeName: String) =
                supertype.arguments.isEmpty() && (!isStrict || supertypes.supertypeNamesOf(subtypeName).isNotEmpty())
            return when (variance) {
                KmVariance.INVARIANT -> false
                KmVariance.OUT -> supertypes.isSubclass(actualName, name) && (
                    isMatchedByName(expected, actualName) || supertypeMatches(actual, name) { supertype ->
                        argumentsMatch(name, expected.arguments, supertype.arguments, variance, isStrict)
                    }
                )
                KmVariance.IN -> supertypes.isSubclass(name, actualName) && (
                    isMatchedByName(actual, name) || supertypeMatches(expected, actualName) { supertype ->
                        argumentsMatch(actualName, supertype.arguments, actual.arguments, variance, isStrict)
                    }
                )
            }
        }

        /**
         * Whether the [expected] type matches the [actual] one, either of them the mutable alternative of a Java collection,
         * see [mutableAlternative], after its read-only one does not, see [classMatches],
         * strictly, without what is not told matching any type, see [argumentsMatch],
         * so that the mutable alternative adds only the extensions which can be called, as far as they are told,
         * like on `MutableList<String>`, but not on `MutableList<in String>` for `(Mutable)List<String!>!`,
         * while a type parameter of the class still stands for any type, see [matches],
         * also telling, in an invariant position, whether a type is between the mutable and the read-only one,
         * the bounds of the flexible type it is the same type as, matched in a covariant and a contravariant position,
         * like `MutableList<out String>`, a supertype of `MutableList<String!>`, and a subtype of `List<String!>`,
         * each of them [retrying], forgetting what the ones not matched inferred.
         */
        private fun matchesMutable(expected: KmType, actual: KmType, variance: KmVariance): Boolean {
            val name = (expected.classifier as KmClassifier.Class).name
            val actualName = (actual.classifier as KmClassifier.Class).name
            return classMatches(expected, name, actual, actualName, variance, isStrict = true)
        }

        /**
         * Infers the type parameter of the given [id] from the [actual] type in a position of the given [variance],
         * telling whether it can be, given the types it is inferred from in the other positions:
         * a subtype of each type in a contravariant position, or an invariant one,
         * and a supertype of each type in a covariant position, or an invariant one,
         * like not `T` of `Pair<in A, out B>` for `Pair<String, Any>`, a subtype of `String`, and a supertype of `Any`,
         * matched with their type arguments, see [typeMatcher], like not `T` of `Pair<T, T>` for `Pair<List<String>, List<Int>>`,
         * a Java collection anything between its mutable and read-only bounds, like `MutableList<String>`
         * for `(Mutable)List<String!>!`, and a platform type either nullable, or not,
         * while the types in the positions of the same variance can be the type parameter inferred
         * as their common supertype, or subtype,
         * and satisfying its bounds, see [satisfiesBounds],
         * while a trivial constraint of a star projection infers nothing, see [isTrivialStarConstraint].
         */
        private fun infer(id: Int, actual: KmType, variance: KmVariance, isStrict: Boolean): Boolean {
            if (actual.classifier !is KmClassifier.Class) return matchesUntold(isStrict = false)
            if (isTrivialStarConstraint(actual, variance)) return true
            if (boundsChecked.isEmpty()) constrainedBy += Triple(id, actual, variance)
            if (isCollecting) return true
            val others = inferred.getOrPut(id) { mutableListOf() }
            val isInferred = others.all { (other, otherVariance) ->
                if (variance == KmVariance.INVARIANT && otherVariance == KmVariance.INVARIANT) {
                    typeMatcher.matches(other, actual, KmVariance.INVARIANT, isStrict)
                } else {
                    // a subtype of the other one, unless either of them is contravariant, or covariant, respectively
                    (variance == KmVariance.IN || otherVariance == KmVariance.OUT ||
                        typeMatcher.matches(other, actual, KmVariance.OUT, isStrict)) &&
                        (variance == KmVariance.OUT || otherVariance == KmVariance.IN ||
                            typeMatcher.matches(actual, other, KmVariance.OUT, isStrict))
                }
            }
            // the same type in a position of the same variance tells nothing more
            if (others.none { (other, otherVariance) -> otherVariance == variance && other.hasSameStructure(actual) }) {
                others += actual to variance
                inferredIds += id
                if (isInferringFromBounds) typesInferredFromBounds += actual
            }
            return isInferred && satisfiesBounds(id, actual, variance, isStrict)
        }

        /**
         * Whether this bound refers to the type parameter of the given [id], only to it, and only in positions of the [expected] variance,
         * like `T : List<T>` covariantly, unlike `T : Comparable<T>`, see [satisfiesRecursiveBound].
         */
        private fun KmType.isSelfReferringOnlyIn(id: Int, expected: KmVariance): Boolean {
            fun KmType.refersOnlyIn(variance: KmVariance): Boolean = when (val classifier = classifier) {
                is KmClassifier.TypeParameter -> classifier.id == id && variance == expected
                is KmClassifier.TypeAlias -> false
                is KmClassifier.Class -> {
                    val declared = declaredVariances(classifier.name)?.takeIf { it.size == arguments.size }
                    declared != null && arguments.indices.all { index ->
                        val argument = arguments[index]
                        val type = argument.type ?: return@all true
                        val projected = projectedVariance(declared[index], argument.variance!!) ?: return@all true
                        type.refersOnlyIn(variance.compose(projected))
                    }
                }
            }
            return !refersOnlyTo { it != id } && refersOnlyIn(KmVariance.OUT)
        }

        /**
         * Whether the [actual] type, a type parameter is inferred from in a position of the given [variance],
         * is a star projection, see [projected], telling only that the type parameter is a subtype of `Any?`,
         * like `T` of `List<T>` in a contravariant position for `List<*>`, or a supertype of `Nothing`,
         * like `T` of `Comparable<T>` in a covariant position for `Comparable<*>`, which kotlinc does not infer it from,
         * unlike the same types given explicitly, or the other way round, like `T` of `List<T>` in a covariant position,
         * inferred as `Any?`, see [isInferred].
         * A star projection is told by the instance of its type, see [STAR], from the same type given explicitly.
         */
        private fun isTrivialStarConstraint(actual: KmType, variance: KmVariance): Boolean =
            (actual === STAR.second && variance == KmVariance.IN) ||
                (actual === STAR_OF_CONTRAVARIANT.second && variance == KmVariance.OUT)

        /**
         * Whether the type parameter of the given [id], inferred from the [actual] type in a position of the given [variance],
         * can satisfy its bounds, like not `T : Number` of `List<T>` for `List<String>`:
         * the [actual] type is a subtype of each of them, when the type parameter is its supertype,
         * in a covariant position, or an invariant one, with the type parameter itself substituted for it,
         * like `List<String>`, which is no `List<List<String>>`, of `T : List<T>`, see [selfSubstituted], while in a contravariant one,
         * it can be their common subtype, like `T : Number` of `I<T>` for `I<String>`, where `interface I<in T>`,
         * which still infers the other type parameters a bound refers to, if it can, like `E` of `T : List<E>`,
         * also a bound of a type parameter bound, like `E` of `U : List<E>` for `T : U`, see [inferringBounds],
         * but not a type parameter bound itself, like `U` of `T : U`, which is only another supertype of their common subtype,
         * while the bounds referring to the type parameter itself are checked once all is inferred, see [satisfiesRecursiveBounds].
         * The bounds of a type parameter already being checked are not checked again,
         * like `T` of `T : List<U>`, `U : List<T>`, inferred again from the type argument of `List<T>`.
         */
        private fun satisfiesBounds(id: Int, actual: KmType, variance: KmVariance, isStrict: Boolean): Boolean {
            val bounds = typeParameters[id]?.upperBounds.orEmpty()
            if (bounds.isEmpty() || !boundsChecked.add(id)) return true
            return try {
                if (variance == KmVariance.IN) {
                    val subtype = if (actual.isNullable) actual.notNullable() else actual
                    val wasInferringFromBounds = isInferringFromBounds
                    isInferringFromBounds = true
                    try {
                        inferringBounds(id).forEach { bound ->
                            val inferredType = if (bound.isNullable) actual else subtype
                            retrying(Mark()) {
                                matches(bound.substituted(mapOf(id to inferredType)), inferredType, KmVariance.OUT, isStrict)
                            }
                        }
                    } finally {
                        isInferringFromBounds = wasInferringFromBounds
                    }
                    true
                } else {
                    bounds.all { bound ->
                        val (substituted, inferredType) = bound.selfSubstituted(id, actual, variance)
                        retrying(Mark()) { matches(substituted, inferredType, KmVariance.OUT, isStrict) } ||
                            selfSupertypes(bound, id, actual, variance).any { supertype ->
                                retrying(Mark()) {
                                    matches(bound.substituted(mapOf(id to supertype)), supertype, KmVariance.OUT, isStrict)
                                }
                            } ||
                            canBeWidened(bound, id, actual, variance)
                    }
                }
            } finally {
                boundsChecked.remove(id)
            }
        }

        /**
         * Whether a supertype of the [actual] type, a type parameter is inferred from in a covariant position of the given [variance],
         * can satisfy the [bound] referring to it only covariantly, which kotlinc can widen the type arguments of,
         * like `List<SL1>` of `List<SL2>` for `T : List<T>`, where `interface SL1 : List<SL1>`, and `interface SL2 : SL1`,
         * unless the actual type is nullable, and the bound is not, or its class is no subclass of the one of the bound,
         * or its supertype of the class of the bound takes a type argument for the type parameter,
         * which no subtype of the bound can be a supertype of,
         * like `String` of `List<String>`, as no supertype of `String` is a `List`, leniently not checked any further.
         */
        private fun canBeWidened(bound: KmType, id: Int, actual: KmType, variance: KmVariance): Boolean {
            if (variance != KmVariance.OUT || !bound.isSelfReferringOnlyIn(id, KmVariance.OUT)) return false
            if (actual.isNullable && !bound.isNullable) return false
            val name = (bound.classifier as? KmClassifier.Class)?.name ?: return true
            val actualName = (actual.classifier as? KmClassifier.Class)?.name ?: return true
            if (actualName == NOTHING_CLASS_NAME) return true
            if (!supertypes.isSubclass(actualName, name)) return false
            val reached = if (actualName == name) listOf(actual) else supertypes.supertypesOf(actual, name) ?: return true
            return reached.any { supertype ->
                supertype.arguments.size != bound.arguments.size || bound.arguments.indices.all { index ->
                    val isSelf = (bound.arguments[index].type?.classifier as? KmClassifier.TypeParameter)?.id == id
                    val argumentName = (supertype.arguments[index].type?.classifier as? KmClassifier.Class)?.name
                    !isSelf || argumentName == null || argumentName == NOTHING_CLASS_NAME || supertypes.isSubclass(argumentName, name)
                }
            }
        }

        /**
         * The supertypes of the [actual] type of the class of the [bound] referring to the type parameter of the given [id],
         * which kotlinc can infer the type parameter as instead, in a covariant position of the given [variance],
         * like `List<SelfList>` of `MutableList<SelfList>` for `T : List<T>`, where `interface SelfList : List<SelfList>`.
         */
        private fun selfSupertypes(bound: KmType, id: Int, actual: KmType, variance: KmVariance): List<KmType> {
            if (variance != KmVariance.OUT || bound.refersOnlyTo { it != id }) return emptyList()
            val name = (bound.classifier as? KmClassifier.Class)?.name ?: return emptyList()
            if ((actual.classifier as? KmClassifier.Class)?.name == name) return emptyList()
            // as nullable as the actual type
            return supertypes.supertypesOf(actual, name).orEmpty()
                .map { supertype -> if (actual.isNullable) supertype.copy().also { it.isNullable = true } else supertype }
        }

        /**
         * This bound of the type parameter of the given [id] with the type kotlinc infers the type parameter as substituted for it,
         * paired with that type: the [actual] one it is inferred from in a position of the given [variance],
         * but `Nothing?` in a covariant one, for which kotlinc infers it as the nullable bound with `Nothing?` substituted,
         * like `Comparable<Nothing?>?` of `T : Comparable<T>?`, which is no `Comparable<Comparable<Nothing?>?>?`,
         * unlike `List<Nothing?>?` of `T : List<T>?`.
         */
        private fun KmType.selfSubstituted(id: Int, actual: KmType, variance: KmVariance): Pair<KmType, KmType> {
            if (refersOnlyTo { it != id }) return this to actual
            val isNullableNothing = actual.isNullable && (actual.classifier as? KmClassifier.Class)?.name == NOTHING_CLASS_NAME
            val isBoundOfNullableNothing = isNullableNothing && isNullable && variance == KmVariance.OUT
            val inferredType = if (isBoundOfNullableNothing) substituted(mapOf(id to actual)) else actual
            return substituted(mapOf(id to inferredType)) to inferredType
        }

        /**
         * The bounds of the type parameter of the given [id] inferring the other type parameters they refer to,
         * like `List<E>` of `T : List<E>`, also the ones of a type parameter bounding it, like `List<E>` of `U : List<E>`
         * for `T : U`, which kotlinc infers `E` of as well, but not the ones referring to the type parameter they bound,
         * like `Comparable<A>` of `A : Comparable<A>`, see [satisfiesBounds].
         */
        private fun inferringBounds(id: Int, visited: MutableSet<Int> = HashSet()): List<KmType> {
            if (!visited.add(id)) return emptyList()
            return typeParameters[id]?.upperBounds.orEmpty().flatMap { bound ->
                when (val classifier = bound.classifier) {
                    is KmClassifier.TypeParameter -> inferringBounds(classifier.id, visited).filter { inferring ->
                        inferring.refersOnlyTo { it != classifier.id }
                    }
                    else -> listOf(bound).filter { !it.refersOnlyTo { other -> other == id || other !in typeParameters } }
                }
            }
        }

        // whether a type referring to a type parameter of the extension was matched as, or against, a projection,
        // or as a nullable type, like T?, whose inference is not modelled, see isInferredByCall
        private var isMatchedBeyondInference
            get() = matched.isMatchedBeyondInference
            set(value) {
                matched.isMatchedBeyondInference = value
            }

        /**
         * Whether the type parameters of the extension are inferred from the types matched, like [isInferred],
         * but as kotlinc infers them for a call without arguments, like `x.ext()`, from all of them at once, see [CallInference],
         * when the types were only collected, see [isCallInferenceModelled], or `null` when that is not modelled,
         * like when what is not told was matched, see [isUntold], or a projection, or a nullable use, of a type parameter.
         */
        fun isInferredByCall(referred: Lazy<Set<Int>>): Boolean? {
            if (!isClassTypeParameterTyped()) return false
            if (isUntold || isMatchedBeyondInference) return null
            return try {
                CallInference().isInferred(referred.value)
            } catch (_: InferenceNotModelled) {
                null
            }
        }

        /**
         * The inference of the type parameters of the extension by kotlinc (K2), for a call without arguments, like `x.ext()`,
         * a model of its constraint system, as far as the types of this matcher tell it:
         * the constraints of the declared upper bounds, like `T <: Comparable<T>`, then the ones of the receiver,
         * the types each type parameter is inferred from, see [infer], a lower one of a covariant position, an upper one
         * of a contravariant one, and an equality one of an invariant one, each of them incorporated into the other ones:
         * a lower one is a subtype of each upper one of the same type parameter, like `B <: Comparable<A>` of `B <: A`,
         * and `A <: Comparable<A>`, and it is substituted for the type parameter in the constraints referring to it,
         * approximated, see [approximating], like `T <: List<String>` of `T <: List<T>`, and `T <: String`.
         * Then the type parameters are fixed one by one, the readiest one first, see [readinessOf], as the common supertype
         * of its lower constraints, or else as the intersection of the upper ones, see [resultTypeOf], which is substituted
         * for it in the constraints of the other ones, and checked against its own ones.
         * The call compiles unless a type parameter the receiver refers to is not constrained by a type when it is fixed,
         * or a constraint is contradicted, like `Any <: Comparable<Any>`, by the receiver, or else while fixing, unless
         * a type parameter is inferred as an intersection no type can be a subtype of, see [isEmptyIntersection],
         * which kotlinc only warns about, also of the contradiction then.
         * A type parameter the receiver does not refer to, nor a bound of one it does, is inferred as well, when its bounds
         * refer to the other ones, like `C` of `C : A`, and else it has to be given explicitly, which is not modelled.
         * What is not modelled throws [InferenceNotModelled], like a type argument which is a projection matched against
         * a type referring to a type parameter, a common supertype of unrelated classes, or a constraint system which does not settle,
         * like the one of `<T : U, U : R, R> Mx<List<T>, R>`, which kotlinc does not finish either.
         */
        private inner class CallInference {

            // the constraints of the type parameters not fixed yet, in the order of their declaration
            private var constraints = LinkedHashMap<Int, MutableList<InferenceConstraint>>()

            // what the constraints added now are derived from, the bounds, the receiver, or a type parameter being fixed
            private var position = InferencePosition.DECLARED_UPPER_BOUND

            // the constraints to add, and to incorporate, in the order they were derived
            private val pending = ArrayDeque<Pair<Int, InferenceConstraint>>()

            // whether a constraint is contradicted while adding the ones of the receiver, a receiver type mismatch for kotlinc
            private var isContradictedByReceiver = false

            // whether a constraint is contradicted while fixing a type parameter, which kotlinc reports as an error,
            // unless it warns about an empty intersection, see hasEmptyIntersection
            private var isContradictedByFixing = false

            // whether a type parameter is constrained by, or fixed as, an intersection no type can be a subtype of
            private var hasEmptyIntersection = false

            // the constraints processed so far, not modelled beyond MAX_INFERENCE_STEPS of them
            private var steps = 0

            fun isInferred(referred: Set<Int>): Boolean {
                // a type parameter the receiver does not refer to, nor a bound of one it does, is inferred as well,
                // when its bounds refer to an inferred one, like C of C : A, or else it is given explicitly,
                // see ReceiverMatcher.isInferred
                val inferable = referred.filterTo(LinkedHashSet()) { it in typeParameters }
                do {
                    val bounded = typeParameters.values.filter { typeParameter ->
                        typeParameter.id !in inferable && typeParameter.upperBounds.any { bound -> !bound.refersOnlyTo { it !in inferable } }
                    }
                } while (inferable.addAll(bounded.map { it.id }))
                // with a type parameter given explicitly, kotlinc reports the violated bound of an inferred one as an error,
                // where it only warns when all of them are inferred, which is not modelled
                if (inferable.size < typeParameters.size) throw InferenceNotModelled
                typeParameters.keys.forEach { if (it in inferable) constraints[it] = ArrayList() }
                typeParameters.values.filter { it.id in inferable }.forEach { typeParameter ->
                    typeParameter.upperBounds.forEach { bound ->
                        if (!bound.isModelled() || bound.isSelfTypeBound()) throw InferenceNotModelled
                        addSubtype(typeParameterType(typeParameter.id), bound, emptySet(), isFromDeclaredUpperBound = false)
                        process()
                    }
                }
                position = InferencePosition.RECEIVER
                // Nothing, or Nothing?, is inferred by kotlinc in a way which is not modelled, see isCallableOn
                if (constrainedBy.any { (_, type, _) -> type.refersToNothingClass() }) throw InferenceNotModelled
                constrainedBy.forEach { (id, type, variance) ->
                    if (!type.isModelled() || !type.refersToNoTypeParameter() || type.refersToFunctionType()) throw InferenceNotModelled
                    val kind = when (variance) {
                        KmVariance.OUT -> InferenceConstraintKind.LOWER
                        KmVariance.IN -> InferenceConstraintKind.UPPER
                        KmVariance.INVARIANT -> InferenceConstraintKind.EQUALITY
                    }
                    // an invariant position is a lower, and an upper, constraint, which make an equality one
                    if (kind != InferenceConstraintKind.UPPER) enqueue(id, InferenceConstraintKind.LOWER, type, emptySet())
                    if (kind != InferenceConstraintKind.LOWER) enqueue(id, InferenceConstraintKind.UPPER, type, emptySet())
                }
                process()
                // the upper types no type can be a subtype of, of a type parameter without lower constraints
                constraints.values.forEach { list ->
                    val upperTypes = list.filter { it.kind == InferenceConstraintKind.UPPER && it.isProper }.map { it.type }
                    if (upperTypes.size > 1 && list.none { it.kind == InferenceConstraintKind.LOWER } && isEmptyIntersection(upperTypes)) {
                        hasEmptyIntersection = true
                    }
                }
                position = InferencePosition.FIXATION
                while (constraints.isNotEmpty()) {
                    val id = constraints.keys.maxBy { readinessOf(it) }
                    if (!hasProperConstraint(id)) return false
                    fix(id, resultTypeOf(id))
                }
                return !isContradictedByReceiver && (!isContradictedByFixing || hasEmptyIntersection)
            }

            // whether the constraint is of a type referring to no type parameter, and not of a declared upper bound,
            // which kotlinc fixes a type parameter by
            private val InferenceConstraint.isProperArgument: Boolean
                get() = isProper && position != InferencePosition.DECLARED_UPPER_BOUND

            // whether the constraint is of a type referring to no type parameter, which a type parameter can be fixed as
            private val InferenceConstraint.isProper: Boolean
                get() = type.refersToNoTypeParameter()

            private fun hasProperConstraint(id: Int): Boolean = constraints.getValue(id).any { it.isProperArgument }

            /**
             * How ready the type parameter of the given [id] is to be fixed, the more the readier, the first one declared
             * of the readiest ones fixed first, like kotlinc: the one constrained by a type, see [isProperArgument],
             * with no constraint of a type with type arguments referring to another type parameter not fixed yet,
             * like `Comparable<A>` of `B`, with a constraint other than a lower `Nothing`, and one not derived from a declared
             * upper bound, a reified one, one without a lower `Nothing`, with an equality constraint,
             * and with a lower one other than `Nothing`, in that order of significance.
             */
            private fun readinessOf(id: Int): Int {
                val list = constraints.getValue(id)
                val proper = list.filter { it.isProperArgument }
                fun InferenceConstraint.isLowerNothing() = kind == InferenceConstraintKind.LOWER && type.isNothingClass
                val qualities = listOf(
                    proper.isNotEmpty(),
                    list.none { constraint ->
                        constraint.type.arguments.isNotEmpty() && !constraint.type.refersOnlyTo { it == id || it !in typeParameters }
                    },
                    proper.any { !it.isLowerNothing() },
                    !proper.filter { constraint ->
                        !(constraint.kind == InferenceConstraintKind.LOWER && constraint.type.isNothingClass && !constraint.type.isNullable) &&
                            !(constraint.kind == InferenceConstraintKind.UPPER && constraint.type.isNullableAny())
                    }.all { it.isFromDeclaredUpperBound },
                    typeParameters.getValue(id).isReified,
                    list.none { it.isLowerNothing() },
                    proper.any { it.kind == InferenceConstraintKind.EQUALITY },
                    proper.any { it.kind != InferenceConstraintKind.UPPER && !it.type.isNothingClass }
                )
                return qualities.fold(0) { readiness, quality -> readiness * 2 + if (quality) 1 else 0 }
            }

            /**
             * The type kotlinc fixes the type parameter of the given [id] as:
             * the one of an equality constraint, or else the common supertype of the lower constraints, see [commonSupertype],
             * when it satisfies all the constraints, or else the intersection of the upper ones, see [intersection],
             * not nullable, when an upper one referring to a type parameter is not.
             */
            private fun resultTypeOf(id: Int): KmType {
                val proper = constraints.getValue(id).filter { it.isProper }
                proper.firstOrNull { it.kind == InferenceConstraintKind.EQUALITY }?.let { return it.type }
                val lower = proper.filter { it.kind == InferenceConstraintKind.LOWER }.map { it.type }
                val upper = proper.filter { it.kind == InferenceConstraintKind.UPPER }.map { it.type }
                val subtype = if (lower.isEmpty()) null else commonSupertype(lower, depth = -lower.maxOf { it.depth() })
                val supertype = if (upper.isEmpty()) null else intersection(upper).let { type ->
                    val others = constraints.getValue(id).filter { it.kind == InferenceConstraintKind.UPPER && !it.isProper }
                    if (others.isEmpty()) type else intersectionOf(type.components().map { if (it.isNullable) it.notNullable() else it })
                }
                if (subtype == null || supertype == null) return subtype ?: supertype ?: throw InferenceNotModelled
                fun isSuitable(result: KmType) = proper.all { constraint ->
                    (constraint.kind == InferenceConstraintKind.LOWER || isSubtype(result, constraint.type)) &&
                        (constraint.kind == InferenceConstraintKind.UPPER || isSubtype(constraint.type, result))
                } && (
                    !(result.isNothingClass && result.isNullable) ||
                        proper.all { it.kind != InferenceConstraintKind.UPPER || it.isOfDeclaredUpperBound }
                    )
                return if (isSuitable(subtype) || !isSuitable(supertype)) subtype else supertype
            }

            // whether the constraint is a declared upper bound, or is derived from one
            private val InferenceConstraint.isOfDeclaredUpperBound: Boolean
                get() = isFromDeclaredUpperBound || position == InferencePosition.DECLARED_UPPER_BOUND

            /**
             * Fixes the type parameter of the given [id] as the [result] type, see [resultTypeOf],
             * incorporating it as an equality constraint, like kotlinc, each lower constraint being its subtype,
             * and it being a subtype of each upper one, and substituting it for the type parameter in the constraints
             * of the other ones.
             */
            private fun fix(id: Int, result: KmType) {
                if (result.isIntersection && isEmptyIntersection(result.components())) hasEmptyIntersection = true
                enqueue(id, InferenceConstraintKind.EQUALITY, result, emptySet())
                process()
                constraints.remove(id)
                constraints.values.forEach { list -> list.removeAll { !it.type.refersOnlyTo { referred -> referred != id } } }
            }

            // the state of the inference, to go back to, see constrainsSubtypeOfIntersection
            private inner class Saved {
                val constraints = this@CallInference.constraints.mapValuesTo(LinkedHashMap()) { (_, list) -> ArrayList(list) }
                val pending = this@CallInference.pending.toList()
                val isContradictedByReceiver = this@CallInference.isContradictedByReceiver
                val isContradictedByFixing = this@CallInference.isContradictedByFixing
                val hasEmptyIntersection = this@CallInference.hasEmptyIntersection

                fun restore() {
                    this@CallInference.constraints = constraints.mapValuesTo(LinkedHashMap()) { (_, list) -> ArrayList(list) }
                    this@CallInference.pending.clear()
                    this@CallInference.pending += pending
                    this@CallInference.isContradictedByReceiver = isContradictedByReceiver
                    this@CallInference.isContradictedByFixing = isContradictedByFixing
                    this@CallInference.hasEmptyIntersection = hasEmptyIntersection
                }
            }

            private val isContradicted: Boolean
                get() = isContradictedByReceiver || isContradictedByFixing

            private fun enqueue(
                id: Int,
                kind: InferenceConstraintKind,
                type: KmType,
                derivedFrom: Set<Int>,
                isFromDeclaredUpperBound: Boolean = false
            ) {
                pending += id to InferenceConstraint(kind, type, position, isFromDeclaredUpperBound, derivedFrom)
            }

            // adds the pending constraints, and the ones incorporating them derives
            private fun process() {
                while (pending.isNotEmpty()) {
                    if (++steps > MAX_INFERENCE_STEPS) throw InferenceNotModelled
                    val (id, constraint) = pending.removeFirst()
                    if (constraint.kind != InferenceConstraintKind.EQUALITY) {
                        // T <: T, and the T <: Any? of a declared upper bound, tell nothing
                        if ((constraint.type.classifier as? KmClassifier.TypeParameter)?.id == id) continue
                        if (constraint.position == InferencePosition.DECLARED_UPPER_BOUND &&
                            constraint.kind == InferenceConstraintKind.UPPER && constraint.type.isNullableAny()
                        ) {
                            continue
                        }
                    }
                    val list = constraints[id] ?: throw InferenceNotModelled
                    incorporate(id, added(list, constraint) ?: continue)
                }
            }

            /**
             * Adds the [constraint] to the [list] of the constraints of a type parameter, telling the one to incorporate,
             * or `null` if one of the same type is already there, unless that one is of a declared upper bound,
             * and this one is not, while a lower one, and an upper one, of the same type make an equality constraint.
             */
            private fun added(list: MutableList<InferenceConstraint>, constraint: InferenceConstraint): InferenceConstraint? {
                for (previous in list.toList()) {
                    if (previous.key != constraint.key) continue
                    val isDeclared = previous.position == InferencePosition.DECLARED_UPPER_BOUND
                    val isUseless = !(isDeclared && constraint.position != InferencePosition.DECLARED_UPPER_BOUND) &&
                        (previous.kind == InferenceConstraintKind.EQUALITY || previous.kind == constraint.kind)
                    if (isUseless) {
                        val isFixing = constraint.position == InferencePosition.FIXATION && constraint.kind == InferenceConstraintKind.EQUALITY
                        return previous.takeIf { isFixing }
                    }
                    if (previous.kind != constraint.kind) {
                        val equality = if (constraint.kind == InferenceConstraintKind.EQUALITY) {
                            constraint
                        } else {
                            InferenceConstraint(
                                InferenceConstraintKind.EQUALITY,
                                constraint.type,
                                if (constraint.position == InferencePosition.DECLARED_UPPER_BOUND) previous.position else constraint.position,
                                constraint.isFromDeclaredUpperBound,
                                constraint.derivedFrom
                            )
                        }
                        // an equality constraint stands for the lower, and the upper, ones of the same type
                        list.removeAll { it.key == equality.key && it.kind != InferenceConstraintKind.EQUALITY }
                        list += equality
                        return equality
                    }
                }
                list += constraint
                return constraint
            }

            /**
             * Incorporates the [constraint] of the type parameter of the given [id] into the other ones, like kotlinc:
             * each lower constraint of the type parameter is a subtype of each upper one,
             * and the type of the constraint is substituted for the type parameter in the constraints referring to it,
             * see [incorporateInto], unless it refers to the type parameter itself, like `T <: Comparable<T>`.
             */
            private fun incorporate(id: Int, constraint: InferenceConstraint) {
                if (!constraint.type.refersOnlyTo { it != id }) return
                val list = constraints.getValue(id).toList()
                if (constraint.kind != InferenceConstraintKind.LOWER) {
                    list.forEach { other ->
                        if (other !== constraint && other.kind != InferenceConstraintKind.UPPER) {
                            addInitial(other.type, constraint.type, constraint.derivedFrom + other.derivedFrom, isFromDeclaredUpperBound = false)
                        }
                    }
                }
                if (constraint.kind != InferenceConstraintKind.UPPER) {
                    list.forEach { other ->
                        if (other !== constraint && other.kind != InferenceConstraintKind.LOWER) {
                            val isFromDeclaredUpperBound = other.position == InferencePosition.DECLARED_UPPER_BOUND &&
                                other.type.classifier !is KmClassifier.TypeParameter
                            addInitial(constraint.type, other.type, constraint.derivedFrom + other.derivedFrom, isFromDeclaredUpperBound)
                        }
                    }
                }
                if (id in constraint.derivedFrom) return
                constraints.entries.toList().forEach { (other, otherList) ->
                    otherList.toList().forEach { otherConstraint ->
                        if (!otherConstraint.type.refersOnlyTo { it != id }) incorporateInto(id, constraint, other, otherConstraint)
                    }
                }
            }

            /**
             * Substitutes the type of the [constraint] of the type parameter of the given [id] for it in the [otherConstraint]
             * of the type parameter [other], which refers to it, like kotlinc: the very type of an equality constraint,
             * and else the type approximated, see [approximating], kept only when it still tells the type of the constraint,
             * as a type argument which is not projected, like `Comparable<B>` of `A <: Comparable<A>` for `B <: A`,
             * or when it is derived by fixing a type parameter, and unless it tells nothing, like `Comparable<Nothing>`.
             */
            private fun incorporateInto(id: Int, constraint: InferenceConstraint, other: Int, otherConstraint: InferenceConstraint) {
                if (id in otherConstraint.derivedFrom) return
                val isGeneric = otherConstraint.type.arguments.isNotEmpty()
                val isEquality = constraint.kind == InferenceConstraintKind.EQUALITY
                val isUpper = constraint.kind == InferenceConstraintKind.UPPER
                // the supertype, and the subtype, the type parameter stands for
                val supertype = if (isEquality || isUpper) constraint.type else NULLABLE_ANY_TYPE
                val subtype = if (isEquality || !isUpper) constraint.type else NOTHING_TYPE
                fun add(kind: InferenceConstraintKind) {
                    val toSuper = kind == InferenceConstraintKind.UPPER
                    val type = when {
                        isEquality -> otherConstraint.type.substituted(mapOf(id to constraint.type))
                        isGeneric -> otherConstraint.type.approximating(id, supertype, subtype, toSuper)
                        // of the other kind, it tells nothing
                        (otherConstraint.kind == InferenceConstraintKind.UPPER) != isUpper -> if (toSuper) NULLABLE_ANY_TYPE else NOTHING_TYPE
                        else -> constraint.type
                    }
                    if (!type.refersOnlyTo { it != other }) return
                    val isFixing = otherConstraint.position == InferencePosition.FIXATION || constraint.position == InferencePosition.FIXATION
                    if (!isEquality && !isFixing && !type.tellsInvariantly(constraint.type.classifier)) return
                    if (!toSuper && type.isNothingClass && !type.isNullable) return
                    if (toSuper && type.isNullableAny()) return
                    val isOfNothing = otherConstraint.type.refersToNothing(onlyNotNullable = false) ||
                        constraint.type.refersToNothing(onlyNotNullable = false)
                    if (!isOfNothing && type.refersToNothing(onlyNotNullable = true)) return
                    enqueue(other, kind, type, otherConstraint.derivedFrom + constraint.derivedFrom + id)
                }
                // an equality constraint referring to a type parameter is not derived
                if (otherConstraint.kind == InferenceConstraintKind.EQUALITY || (isGeneric && otherConstraint.kind == InferenceConstraintKind.LOWER)) {
                    throw InferenceNotModelled
                }
                add(otherConstraint.kind)
            }

            /**
             * This type with the type parameter of the given [id], standing for any type between its [subtype] and [supertype],
             * approximated by a supertype of the type, when [toSuper], or else by a subtype, like kotlinc approximates a captured type:
             * the [supertype] in a covariant position, the [subtype] in a contravariant one, flipping the direction,
             * and a projection in an invariant one, like `Inv<out String>`.
             */
            private fun KmType.approximating(id: Int, supertype: KmType, subtype: KmType, toSuper: Boolean): KmType {
                if ((classifier as? KmClassifier.TypeParameter)?.id == id) return if (toSuper) supertype else subtype
                if (refersOnlyTo { it != id }) return this
                val name = (classifier as? KmClassifier.Class)?.name ?: throw InferenceNotModelled
                val variances = declaredVariances(name)?.takeIf { it.size == arguments.size } ?: throw InferenceNotModelled
                val approximated = arguments.mapIndexed { index, argument ->
                    val type = argument.type
                    if (type == null || type.refersOnlyTo { it != id }) return@mapIndexed argument
                    if (argument.variance != KmVariance.INVARIANT) throw InferenceNotModelled
                    when (variances[index]) {
                        KmVariance.OUT -> KmTypeProjection(KmVariance.INVARIANT, type.approximating(id, supertype, subtype, toSuper))
                        KmVariance.IN -> KmTypeProjection(KmVariance.INVARIANT, type.approximating(id, supertype, subtype, !toSuper))
                        KmVariance.INVARIANT -> {
                            // no subtype of a type of an invariant type argument approximated
                            if (!toSuper) return NOTHING_TYPE
                            val lower = type.approximating(id, supertype, subtype, toSuper = false)
                            val upper = type.approximating(id, supertype, subtype, toSuper = true)
                            val isLowerTrivial = lower.isNothing
                            val isCaptured = (type.classifier as? KmClassifier.TypeParameter)?.id == id
                            when {
                                isCaptured && !isLowerTrivial -> KmTypeProjection(KmVariance.IN, lower)
                                upper.isNullableAny() && !isLowerTrivial -> KmTypeProjection(KmVariance.IN, lower)
                                else -> KmTypeProjection(KmVariance.OUT, upper)
                            }
                        }
                    }
                }
                return copy(arguments = approximated)
            }

            // whether this type is of the classifier, or has a type argument of it, which is not projected
            private fun KmType.tellsInvariantly(classifier: KmClassifier): Boolean {
                fun KmType.hasArgument(): Boolean = arguments.any { argument ->
                    val type = argument.type ?: return@any false
                    (argument.variance == KmVariance.INVARIANT && type.classifier == classifier) || type.hasArgument()
                }
                return this.classifier == classifier || hasArgument()
            }

            private fun KmType.refersToNothing(onlyNotNullable: Boolean): Boolean =
                (isNothingClass && !(onlyNotNullable && isNullable)) ||
                    arguments.any { it.type?.refersToNothing(onlyNotNullable) == true }

            // a constraint derived by incorporation, unless its types are the same
            private fun addInitial(subtype: KmType, supertype: KmType, derivedFrom: Set<Int>, isFromDeclaredUpperBound: Boolean) {
                if (subtype.hasSameStructure(supertype)) return
                addSubtype(subtype, supertype, derivedFrom, isFromDeclaredUpperBound)
            }

            private fun addSubtype(subtype: KmType, supertype: KmType, derivedFrom: Set<Int>, isFromDeclaredUpperBound: Boolean) {
                if (constrainsSubtype(subtype, supertype, derivedFrom, isFromDeclaredUpperBound)) return
                when (position) {
                    InferencePosition.DECLARED_UPPER_BOUND -> throw InferenceNotModelled
                    InferencePosition.RECEIVER -> isContradictedByReceiver = true
                    InferencePosition.FIXATION -> isContradictedByFixing = true
                }
            }

            /**
             * Whether the [subtype] can be a subtype of the [supertype], adding the constraints it takes of the type parameters
             * they refer to, like `C <: String` of `String <: Comparable<C>`.
             */
            private fun constrainsSubtype(
                subtype: KmType,
                supertype: KmType,
                derivedFrom: Set<Int>,
                isFromDeclaredUpperBound: Boolean
            ): Boolean {
                val subtypeId = (subtype.classifier as? KmClassifier.TypeParameter)?.id
                val supertypeId = (supertype.classifier as? KmClassifier.TypeParameter)?.id
                if (subtypeId != null || supertypeId != null) {
                    if (supertypeId != null) enqueue(supertypeId, InferenceConstraintKind.LOWER, subtype, derivedFrom, isFromDeclaredUpperBound)
                    if (subtypeId != null) enqueue(subtypeId, InferenceConstraintKind.UPPER, supertype, derivedFrom, isFromDeclaredUpperBound)
                    return true
                }
                if (supertype.isIntersection) {
                    return supertype.components().all { constrainsSubtype(subtype, it, derivedFrom, isFromDeclaredUpperBound) }
                }
                if (subtype.isIntersection) return constrainsSubtypeOfIntersection(subtype, supertype, derivedFrom, isFromDeclaredUpperBound)
                val isSimple = subtype.refersToNoTypeParameter() && supertype.refersToNoTypeParameter() &&
                    !subtype.refersToIntersection() && !supertype.refersToIntersection()
                if (isSimple) return isSubtype(subtype, supertype)
                if (subtype.isNullable && !supertype.isNullable) return false
                val name = (subtype.classifier as? KmClassifier.Class)?.name ?: throw InferenceNotModelled
                val supertypeName = (supertype.classifier as? KmClassifier.Class)?.name ?: throw InferenceNotModelled
                if (name == NOTHING_CLASS_NAME || supertypeName == ANY_CLASS_NAME) return true
                if (name != supertypeName && !supertypes.isSubclass(name, supertypeName)) return false
                val reached = if (name == supertypeName) subtype else {
                    // also a supertype of Nothing type arguments, like Co<Nothing> of interface NCo : Co<Nothing>, is not modelled
                    supertypes.supertypesOf(subtype, supertypeName)?.singleOrNull()?.takeIf { !it.refersToNothingClass() }
                        ?: throw InferenceNotModelled
                }
                val variances = declaredVariances(supertypeName)?.takeIf { it.size == supertype.arguments.size && it.size == reached.arguments.size }
                    ?: throw InferenceNotModelled
                return variances.indices.all { index ->
                    val argument = reached.arguments[index]
                    val expected = supertype.arguments[index]
                    val type = argument.type
                    val expectedType = expected.type
                    val isSimple = type?.refersToNoTypeParameter() != false && expectedType?.refersToNoTypeParameter() != false &&
                        type?.refersToIntersection() != true && expectedType?.refersToIntersection() != true
                    if (isSimple) {
                        typeMatcher.isUntold = false
                        return@all typeMatcher.argumentMatches(expected, argument, variances[index], KmVariance.OUT, isStrict = false)
                            .also { if (typeMatcher.isUntold) throw InferenceNotModelled }
                    }
                    if (type == null || expectedType == null || argument.variance != KmVariance.INVARIANT || expected.variance != KmVariance.INVARIANT) {
                        throw InferenceNotModelled
                    }
                    when (variances[index]) {
                        KmVariance.OUT -> constrainsSubtype(type, expectedType, derivedFrom, isFromDeclaredUpperBound)
                        KmVariance.IN -> constrainsSubtype(expectedType, type, derivedFrom, isFromDeclaredUpperBound)
                        KmVariance.INVARIANT -> constrainsSubtype(type, expectedType, derivedFrom, isFromDeclaredUpperBound) &&
                            constrainsSubtype(expectedType, type, derivedFrom, isFromDeclaredUpperBound)
                    }
                }
            }

            /**
             * Whether the [subtype], an intersection, can be a subtype of the [supertype], see [constrainsSubtype]:
             * any of its types of a subclass of the class of the supertype, and of multiple ones, their type arguments intersected,
             * when the class has only covariant type parameters, or else, when fixing, the first one which contradicts nothing,
             * or the first one, like kotlinc.
             */
            private fun constrainsSubtypeOfIntersection(
                subtype: KmType,
                supertype: KmType,
                derivedFrom: Set<Int>,
                isFromDeclaredUpperBound: Boolean
            ): Boolean {
                val supertypeName = (supertype.classifier as? KmClassifier.Class)?.name ?: throw InferenceNotModelled
                val candidates = subtype.components().filter { component ->
                    val name = (component.classifier as? KmClassifier.Class)?.name ?: throw InferenceNotModelled
                    (!component.isNullable || supertype.isNullable) &&
                        (name == supertypeName || supertypeName == ANY_CLASS_NAME || supertypes.isSubclass(name, supertypeName))
                }
                if (supertype.refersToNoTypeParameter() || candidates.size < 2) {
                    return candidates.any { constrainsSubtype(it, supertype, derivedFrom, isFromDeclaredUpperBound) }
                }
                // the types of the same class with only covariant type parameters as one of them, of their type arguments intersected,
                // like List<String & Comparable<CharSequence>> of List<String>, and List<Comparable<CharSequence>>
                val variances = declaredVariances(supertypeName) ?: throw InferenceNotModelled
                val reached = candidates.map { candidate ->
                    if ((candidate.classifier as KmClassifier.Class).name == supertypeName) candidate else {
                        supertypes.supertypesOf(candidate, supertypeName)?.singleOrNull()?.takeIf { !it.refersToNothingClass() }
                            ?: throw InferenceNotModelled
                    }
                }
                val isCovariant = variances.all { it == KmVariance.OUT } && reached.all { type ->
                    type.arguments.size == variances.size && type.arguments.all { it.type != null && it.variance == KmVariance.INVARIANT }
                }
                if (isCovariant) {
                    val arguments = variances.indices.map { index ->
                        KmTypeProjection(KmVariance.INVARIANT, intersection(reached.map { it.arguments[index].type!! }))
                    }
                    val intersected = classType(supertypeName).copy(arguments = arguments)
                    if (constrainsSubtype(intersected, supertype, derivedFrom, isFromDeclaredUpperBound)) return true
                }
                if (position != InferencePosition.FIXATION) throw InferenceNotModelled
                val wasContradicted = isContradicted
                candidates.forEach { candidate ->
                    val saved = Saved()
                    val isSubtype = constrainsSubtype(candidate, supertype, derivedFrom, isFromDeclaredUpperBound)
                    process()
                    if (wasContradicted || (isSubtype && !isContradicted)) return isSubtype
                    saved.restore()
                }
                return constrainsSubtype(candidates.first(), supertype, derivedFrom, isFromDeclaredUpperBound)
            }

            // whether the subtype, referring to no type parameter, is a subtype of the supertype, as far as told
            private fun isSubtype(subtype: KmType, supertype: KmType): Boolean {
                if (subtype.refersToIntersection() || supertype.refersToIntersection()) {
                    return constrainsSubtype(subtype, supertype, emptySet(), isFromDeclaredUpperBound = false)
                }
                typeMatcher.isUntold = false
                return typeMatcher.matches(supertype, subtype, KmVariance.OUT, isStrict = false)
                    .also { if (typeMatcher.isUntold) throw InferenceNotModelled }
            }

            /**
             * The intersection of the [types], also of the types of the intersections among them, without the ones
             * which are supertypes of another one, not nullable, when any of them is.
             */
            private fun intersection(types: List<KmType>): KmType {
                val components = types.flatMap { it.components() }
                val isNotNullable = components.any { !it.isNullable }
                val intersected = components.map { if (isNotNullable && it.isNullable) it.notNullable() else it }
                return intersectionOf(intersected.withoutRedundant { type, other -> isSubtype(other, type) })
            }

            // these types without the ones another one makes redundant, the first of the ones making each other redundant kept
            private fun List<KmType>.withoutRedundant(isRedundantBy: (type: KmType, other: KmType) -> Boolean): List<KmType> =
                filterIndexed { index, type ->
                    indices.none { other ->
                        other != index && isRedundantBy(type, this[other]) && (other < index || !isRedundantBy(this[other], type))
                    }
                }

            // the type of the intersection of the types, or the only one of them
            private fun intersectionOf(types: List<KmType>): KmType = types.singleOrNull() ?: KmType().also { type ->
                type.classifier = INTERSECTION_CLASSIFIER
                types.mapTo(type.arguments) { KmTypeProjection(KmVariance.INVARIANT, it) }
            }

            private val KmType.isIntersection: Boolean get() = classifier == INTERSECTION_CLASSIFIER

            // the types of this intersection, or this type itself
            private fun KmType.components(): List<KmType> = if (isIntersection) arguments.map { it.type!! } else listOf(this)

            private fun KmType.refersToIntersection(): Boolean =
                isIntersection || arguments.any { it.type?.refersToIntersection() == true }

            /**
             * The common supertype of the [types], like kotlinc: the one all the other ones are subtypes of,
             * or else the type of the only most specific class they are all subclasses of, like `List` of `List<SL2>`, and `SL1`,
             * where `interface SL1 : List<SL1>`, and `interface SL2 : SL1`, with the common supertypes of the type arguments
             * of its covariant type parameters, like `List<SL1>`, nullable, when any of them is.
             */
            private fun commonSupertype(types: List<KmType>, depth: Int): KmType {
                // a star projection of kotlinc
                if (depth > 0) throw InferenceNotModelled
                val isNullable = types.any { it.isNullable }
                fun KmType.nullable() = if (isNullable == this.isNullable) this else copy().also { it.isNullable = isNullable }
                val distinct = types.map { if (it.isNullable) it.notNullable() else it }.distinctBy { it.structureKey() }
                    .filter { !it.isNothingClass }
                if (distinct.isEmpty()) return NOTHING_TYPE.nullable()
                val supertypes = distinct.withoutRedundant { type, other -> isSubtype(type, other) }
                supertypes.singleOrNull()?.let { return it.nullable() }
                return commonSupertypeOfClasses(supertypes, depth).nullable()
            }

            private fun commonSupertypeOfClasses(types: List<KmType>, depth: Int): KmType {
                val names = types.map { (it.classifier as? KmClassifier.Class)?.name ?: throw InferenceNotModelled }
                val classSupertypes = this@ReceiverMatcher.supertypes
                // the supertypes the JVM classes add, like java.io.Serializable, are not told by their types
                val commonNames = names.map { classSupertypes.supertypeNamesOf(it) + it.replace('/', '.') }.reduce { common, other -> common intersect other }
                val candidates = (listOf(names.first()) + classSupertypes.supertypeTypesOf(names.first()).values.flatten()
                    .mapNotNull { (it.classifier as? KmClassifier.Class)?.name })
                    .distinct()
                    .filter { it.replace('/', '.') in commonNames && it != ANY_CLASS_NAME }
                if (!(commonNames - candidates.mapTo(HashSet()) { it.replace('/', '.') }).all { it == ANY_CLASS_NAME.replace('/', '.') }) {
                    throw InferenceNotModelled
                }
                val name = candidates.filter { candidate -> candidates.none { it != candidate && classSupertypes.isSubclass(it, candidate) } }
                    .ifEmpty { return classType(ANY_CLASS_NAME) }
                    .singleOrNull() ?: throw InferenceNotModelled
                val variances = declaredVariances(name) ?: throw InferenceNotModelled
                if (variances.isEmpty()) return classType(name)
                val reached = types.map { type ->
                    if ((type.classifier as KmClassifier.Class).name == name) type else {
                        classSupertypes.supertypesOf(type, name)?.singleOrNull() ?: throw InferenceNotModelled
                    }
                }
                val arguments = variances.indices.map { index ->
                    val arguments = reached.map { it.arguments.getOrNull(index) ?: throw InferenceNotModelled }
                    val argumentTypes = arguments.map { argument ->
                        argument.type?.takeIf { argument.variance == KmVariance.INVARIANT } ?: throw InferenceNotModelled
                    }
                    when {
                        argumentTypes.all { it.hasSameStructure(argumentTypes.first()) } -> argumentTypes.first()
                        variances[index] == KmVariance.OUT -> commonSupertype(argumentTypes, depth + 1)
                        else -> throw InferenceNotModelled
                    }.let { KmTypeProjection(KmVariance.INVARIANT, it) }
                }
                return classType(name).copy(arguments = arguments)
            }

            /**
             * Whether this type is one the inference is modelled for: of classes, or of the type parameters of the extension,
             * which are not nullable, nor within a nullable type, and of no flexible, raw, or inner, type.
             */
            private fun KmType.isModelled(): Boolean {
                if (flexibleTypeUpperBound != null || outerType != null || isJavaRaw || isJavaCollection || isJavaArray) return false
                val isReferring = !refersToNoTypeParameter()
                if (isReferring && (isNullable || isDefinitelyNonNull)) return false
                return when (val classifier = classifier) {
                    is KmClassifier.Class -> arguments.all { argument ->
                        val type = argument.type
                        type?.isModelled() != false && (argument.variance == KmVariance.INVARIANT || type?.refersToNoTypeParameter() != false)
                    }
                    is KmClassifier.TypeParameter -> classifier.id in typeParameters
                    is KmClassifier.TypeAlias -> false
                }
            }

            // whether this bound is of a class with a type parameter bounded by the class itself, like Enum<E : Enum<E>>,
            // which kotlinc fixes a type parameter by in its own way
            private fun KmType.isSelfTypeBound(): Boolean {
                val name = (classifier as? KmClassifier.Class)?.name ?: return false
                return classHeader(name)?.typeParameters.orEmpty().any { typeParameter ->
                    typeParameter.upperBounds.any { (it.classifier as? KmClassifier.Class)?.name == name }
                }
            }

        }

    }

    // told when the inference of the type parameters of an extension is not modelled, see ReceiverMatcher.CallInference
    private object InferenceNotModelled : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private enum class InferenceConstraintKind { LOWER, UPPER, EQUALITY }

    // what a constraint is derived from, see ReceiverMatcher.CallInference
    private enum class InferencePosition { DECLARED_UPPER_BOUND, RECEIVER, FIXATION }

    /**
     * A constraint of a type parameter of an extension, see [ReceiverMatcher.CallInference]: the [type] it is a supertype of,
     * a subtype of, or the same as, by its [kind], with the [position] it is derived from,
     * whether it is derived from a declared upper bound of another type parameter, and the type parameters
     * whose constraints it is [derivedFrom], which it is not incorporated into again.
     */
    private class InferenceConstraint(
        val kind: InferenceConstraintKind,
        val type: KmType,
        val position: InferencePosition,
        val isFromDeclaredUpperBound: Boolean,
        val derivedFrom: Set<Int>
    ) {
        val key: String = type.structureKey()
    }

    /**
     * The receiver names of the extensions on the class itself, the [className],
     * and of a JVM function interface, like `kotlin.jvm.functions.Function1`, also its function type `kotlin.Function1`.
     */
    private fun ownExtensionReceiverNames(className: String): List<String> =
        listOf(className) + jvmFunctionTypeName(className)?.let(::functionTypeReceiverNames).orEmpty()

    /**
     * The receiver names of the extensions listed for a function type class named [functionTypeName],
     * like `kotlin.Function1`, the ones on its function types, and on its supertypes, like `kotlin.Function`,
     * see [FunctionTypeClass.supertypeNames].
     */
    private fun functionTypeReceiverNames(functionTypeName: String): List<String> =
        listOf(functionTypeName) + FunctionTypeClass.of(functionTypeName)?.supertypeNames.orEmpty()

    /**
     * Lists a function type class, see [FunctionTypeClass], with its `invoke` operator,
     * and the extensions on its function types and on their supertype `kotlin.Function`.
     */
    private fun formatFunctionTypeClass(functionTypeClass: FunctionTypeClass, className: String): ClassListing {
        val extensions = functionTypeExtensions(className)
        return ClassListing.of(
            header = functionTypeClass.header,
            members = listOf(ListingItem.Declaration(listOf(functionTypeClass.invoke))),
            extensions = extensionSignatures(extensions.functions, extensions.properties, contextPackage = null)
        )
    }

    /**
     * The extensions listed for the function type class named [className], see [functionTypeReceiverNames].
     */
    private fun functionTypeExtensions(className: String): ListedExtensions {
        val receiverNames = functionTypeReceiverNames(className)
        return extensionsListedFor(receiverNames, receiverNames.toSet())
    }

    /**
     * Resolves the `invoke` operator, and the extensions, of a function type class named [memberName].
     */
    private fun resolveFunctionTypeMember(
        functionTypeClass: FunctionTypeClass,
        className: String,
        memberName: String
    ): MemberListing? {
        val members = if (memberName == "invoke") listOf(functionTypeClass.invoke) else emptyList()
        val inherited = if (functionTypeClass.kind.isReference) functionReferenceInheritedMembers(memberName) else emptyList()
        // like in the listing of the function type class
        val listedExtensions = functionTypeExtensions(className)
        val extensions = extensionSignatures(
            listedExtensions.functions.filter { it.function.name == memberName },
            listedExtensions.properties.filter { it.property.name == memberName },
            contextPackage = null
        )
        return MemberListing.of(members.map { ListingItem.Declaration(listOf(it)) } + inherited + extensions)
    }

    /**
     * The members named [memberName] which a function reference type, like `kotlin.reflect.KFunction1`,
     * inherits from `kotlin.reflect.KFunction`, the ones it declares, and the ones it inherits, see [inheritedMembers],
     * like `name` of `kotlin.reflect.KCallable`, rendered as in the package of `KFunction`.
     */
    private fun functionReferenceInheritedMembers(memberName: String): List<ListingItem.InheritedMember> {
        val declarations = classDeclarations(kotlinClassName(KFUNCTION_CLASS_NAME)) ?: return emptyList()
        val kmClass = declarations.kmClass ?: return emptyList()
        val apiClass = apiClasses.apiClass(kmClass.name) ?: return emptyList()
        val contextPackage = contextPackageOf(declarations, kmClass)
        val isOpenForOverrides = isOpenForOverrides(kmClass, apiClass)
        val typeParameters = typeParameterScopeOf(kmClass, declarations)
        val declared = apiMembersNamed(kmClass, declarations, memberName, apiClass).map { member ->
            ListingItem.InheritedMember(
                KFUNCTION_CLASS_NAME,
                formatMember(
                    member, kmClass, declarations, apiClass, contextPackage,
                    isOwnerOpenForOverrides = isOpenForOverrides, inheritedBy = ClassKind.INTERFACE,
                    typeParameters = typeParameters
                )
            )
        }
        return declared + inheritedMembers(kmClass, declarations, memberName, apiClass, contextPackage, mutableListOf())
    }

    /**
     * The `@JvmInline` annotation, which a value class with a single underlying property requires on the JVM.
     * It is rendered inline, so that the class declaration stays on a single line.
     */
    private fun formatJvmInline(isJvmInline: Boolean): String = if (isJvmInline) "@JvmInline " else ""

    /**
     * The modifiers and the [kind] of the class, like `data class ` or `object `,
     * preceded by its [contextReceivers], see [formatClassContextReceivers], following its annotations.
     */
    private fun formatClassPrefix(kmClass: KmClass, kind: ClassKind, contextReceivers: String): String = buildString {
        val isInterface = kmClass.kind == ClassKind.INTERFACE

        // a value class with multiple underlying properties (-Xvalue-classes) is not annotated
        +formatJvmInline(kmClass.isValue && kmClass.inlineClassUnderlyingPropertyName != null)
        +contextReceivers

        // Visibility and modifiers
        +formatVisibility(kmClass.visibility)
        // Skip 'abstract' for interfaces - they're always abstract
        if (kmClass.modality == Modality.ABSTRACT && !isInterface) +"abstract "
        if (kmClass.modality == Modality.OPEN) +"open "
        if (kmClass.modality == Modality.SEALED) +"sealed "
        if (kmClass.isData) +"data "
        if (kmClass.isValue) +"value "
        if (kmClass.isInner) +"inner "
        // a lambda can be passed for a fun interface, by SAM conversion
        if (kmClass.isFunInterface) +"fun "

        +formatClassKind(kind)
    }

    /**
     * The keywords of a class [kind], like `companion object `.
     */
    private fun formatClassKind(kind: ClassKind): String = when (kind) {
        ClassKind.CLASS -> "class "
        ClassKind.INTERFACE -> "interface "
        ClassKind.ENUM_CLASS -> "enum class "
        ClassKind.ENUM_ENTRY -> "enum entry "
        ClassKind.OBJECT -> "object "
        ClassKind.COMPANION_OBJECT -> "companion object "
        ClassKind.ANNOTATION_CLASS -> "annotation class "
    }

    /**
     * How the primary constructor of a class is declared in its header, see [constructorsOf].
     */
    private sealed interface HeaderConstructor {

        /**
         * Not declared: the implicit public constructor without parameters, like in `class Foo`,
         * or none, as the kind of the class already tells how it can be created, if at all, like for an object.
         */
        data object Implicit : HeaderConstructor

        /**
         * Marked with ` private constructor()`, whatever the actual constructors, see [NOT_CONSTRUCTIBLE_MARKER],
         * as the class can neither be instantiated, nor subclassed, from the user code.
         */
        data object NotConstructible : HeaderConstructor

        /**
         * Declared with its parameters, and with the [visibility] and the [deprecation], unless public and not deprecated,
         * like `class Foo(val a: Int)`, or `open class Foo protected constructor()`.
         */
        class Declared(val constructor: KmConstructor, val visibility: Visibility, val deprecation: Deprecation?) : HeaderConstructor

    }

    /**
     * The constructors of a class rendered: the [header] one, and the [secondary] ones, listed in the class body,
     * with the [primary] one, whose parameters are the ones of an annotation class.
     */
    private class Constructors(val primary: KmConstructor?, val header: HeaderConstructor, val secondary: List<KmConstructor>)

    /**
     * The constructors of the [kmClass] to render, decided in this one place, see [formatClass]:
     * the ones which can be called from the user code, unless hidden,
     * and the primary one of an enum class, whose constructor is always private,
     * while its parameters are the idiomatic way of declaring the properties of the entries,
     * and of a data class, or a value class, which cannot be declared without its primary constructor and its parameters,
     * rendered as private then, like a class without any constructor which can be called, see [HeaderConstructor.NotConstructible].
     */
    private fun constructorsOf(kmClass: KmClass, apiClass: ApiClass, foundClass: ClassDeclarations): Constructors {
        val isEnum = kmClass.kind == ClassKind.ENUM_CLASS
        // the constructor of kotlin.Enum is called only by the enum classes, which no class can extend directly
        val isCallable = { constructor: KmConstructor ->
            kmClass.name != ENUM_CLASS_NAME && apiClass.isConstructorApi(constructor.visibility) && !foundClass.isHidden(constructor)
        }
        // never of an enum class, as they are private, nor of an annotation class, which cannot have any
        val secondary = kmClass.constructors.filter { it.isSecondary && isCallable(it) }
        val primary = kmClass.constructors.firstOrNull { !it.isSecondary }
        val isPrimaryCallable = primary != null && isCallable(primary)
        val header = when {
            primary != null && (isEnum || isPrimaryCallable || kmClass.isDataOrValueClass) -> {
                // the always private constructor of an enum class is rendered only for its parameters
                val visibility = when {
                    isEnum -> Visibility.PUBLIC
                    isPrimaryCallable -> primary.visibility
                    else -> Visibility.PRIVATE
                }
                val deprecation = if (isPrimaryCallable) foundClass.deprecation(primary) else null
                // the implicit public constructor without parameters is omitted, unless there are other constructors,
                // as `class Foo { constructor(x: Int) }` has no constructor without parameters
                if (
                    primary.valueParameters.isNotEmpty() || secondary.isNotEmpty() ||
                    visibility != Visibility.PUBLIC || deprecation != null
                ) {
                    HeaderConstructor.Declared(primary, visibility, deprecation)
                } else {
                    HeaderConstructor.Implicit
                }
            }
            // the kinds of classes which would read as having the implicit public constructor without parameters,
            // unlike an object, an enum class, an interface, an annotation class,
            // or a sealed class, whose constructors are never callable from the user code
            kmClass.kind == ClassKind.CLASS && kmClass.modality != Modality.SEALED && secondary.isEmpty() ->
                HeaderConstructor.NotConstructible
            else -> HeaderConstructor.Implicit
        }
        return Constructors(primary, header, secondary)
    }

    /**
     * Lists a class: its header, see [formatClassHeader], and its body, with the nested classes, the properties,
     * the secondary constructors, the functions, and the companion object, followed by its extensions,
     * see [listedExtensions].
     *
     * The body of a built-in type, which is its declaration in the `.kotlin_builtins` resources, see [BuiltinClass],
     * or its own Kotlin metadata, like of `kotlin.UInt`, also lists its JVM-only members, like `String.chars()`,
     * but the ones hidden by its extensions, see [jvmOnlyFunctionsNotHidden],
     * and its extensions which read like its members, see [bodyReceiverSubstitution], without their receivers,
     * while the properties and the functions of a built-in type, coming from these different sources,
     * are sorted by name, like the members of its companion object, so that the overloads stay together.
     *
     * @param apiClass the [kmClass] as a class of the API.
     * @param isBuiltin whether the class is a built-in type, or its companion object.
     */
    private fun formatClass(
        kmClass: KmClass,
        apiClass: ApiClass,
        className: String,
        foundClass: ClassDeclarations,
        isBuiltin: Boolean = false
    ): ClassListing {
        val clazz = foundClass.clazz
        val isEnum = kmClass.kind == ClassKind.ENUM_CLASS
        val isAnnotation = kmClass.kind == ClassKind.ANNOTATION_CLASS
        val isSealed = kmClass.modality == Modality.SEALED
        val typeParameters = typeParameterScopeOf(kmClass, foundClass)

        // Extract the package name for context-aware type formatting
        val contextPackage = contextPackageOf(foundClass, kmClass)

        // Only the members which can be used from the user code
        val properties = kmClass.properties.inApi(apiClass, foundClass)
        val functions = kmClass.functions.inApi(apiClass, foundClass)

        // the primary constructor declared in the header, if any, and the secondary ones, see constructorsOf
        val constructors = constructorsOf(kmClass, apiClass, foundClass)
        val declaredConstructor = constructors.header as? HeaderConstructor.Declared
        val secondaryConstructors = constructors.secondary
        // whether the user code can override the members, see formatModalityModifiers
        val isOpenForOverrides = isOpenForOverrides(kmClass, apiClass)
        // The properties declared by the parameters of the rendered constructor, by the parameter name,
        // also the ones which are not part of the API of a data class, or a value class,
        // whose parameters must all declare properties, like `private val rawValue: Long`
        val propertiesOfParameters = if (kmClass.isDataOrValueClass) kmClass.properties else properties
        val constructorProperties = declaredConstructor?.constructor?.valueParameters.orEmpty().mapNotNull { param ->
            propertiesOfParameters.firstOrNull { it.isDeclaredBy(param) }?.let { param.name to it }
        }.toMap()
        val declaredInConstructor = constructorProperties.values.toSet()

        // Extensions from the index on this type and its supertypes (shown after class body with source),
        // a JVM function interface, like kotlin.jvm.functions.Function1, has the ones of its function type,
        // while a built-in type lists the ones reading like its members in its body
        val listedExtensions = listedExtensions(kmClass, className, apiClass, foundClass, isBuiltin)
        // the extensions of the companion object of a built-in type, like `String.Companion.format()`, are called on
        // the name of the type, which they are listed with, see listedExtensions, outside the body of the companion object
        val bodyExtensions = if (isBuiltin && kmClass.kind != ClassKind.COMPANION_OBJECT) {
            bodyExtensionsOf(kmClass, foundClass, typeParameters.names, listedExtensions)
        } else {
            ListedExtensions(emptyList(), emptyList())
        }
        val extensions = listedExtensions.functions - bodyExtensions.functions.toSet()
        val extensionProperties = listedExtensions.properties - bodyExtensions.properties.toSet()

        // For annotation classes, the parameters are shown inline, without body
        val annotationParameters = constructors.primary?.valueParameters
            ?.takeIf { isAnnotation && it.isNotEmpty() }

        val constructorDeclaration = when {
            annotationParameters != null -> annotationParameters.joinToString(", ", "(", ")") { param ->
                formatValueParameter(
                    param,
                    contextPackage,
                    // the property of a parameter can be hidden, while the argument can still be passed,
                    // as the parameter itself cannot be annotated with @Deprecated
                    prefix = if (properties.any { it.name == param.name }) "val " else "",
                    // the default values are only present in the JVM annotation, not in the Kotlin metadata
                    defaultValue = { clazz?.let { formatAnnotationDefaultValue(it, param.name, contextPackage) } }
                )
            }
            declaredConstructor != null -> formatConstructor(
                declaredConstructor.constructor,
                isPrimary = true,
                visibility = declaredConstructor.visibility,
                declaredConstructor.deprecation,
                contextPackage,
                typeParameters
            ) { param ->
                // a parameter not declaring a property is still needed to call the constructor
                val prop = constructorProperties[param.name]
                val isOverride = prop != null && isOverride(foundClass.overrideOwner, prop)
                // never with a setter narrower than the property, which is declared in the class body
                val declaration = prop?.let {
                    val deprecation = foundClass.deprecation(it)
                    val setterVisibility = if (it in properties) {
                        it.apiSetterVisibility(apiClass, deprecation)
                    } else {
                        // the property of a data class, or a value class, which is not part of the API, like a private one,
                        // declared as it is
                        it.declaredSetterVisibility
                    }
                    propertyDeclaration(it, setterVisibility, isOverride, deprecation)
                }
                formatValueParameter(
                    param,
                    contextPackage,
                    typeParameters,
                    prefix = when {
                        declaration == null -> ""
                        declaration.isVar -> "var "
                        else -> "val "
                    },
                    // a constructor property is never abstract, but it can be open or final
                    // the hidden property of a data class, or a value class, is still declared, see formatDeprecation
                    modifiers = if (declaration == null) "" else formatDeprecation(declaration.deprecation, withHidden = prop !in properties) +
                        formatVisibility(prop.visibility) +
                        formatModalityModifiers(prop.modality, isOverride, inInterface = false, isOpenForOverrides)
                )
            }
            constructors.header == HeaderConstructor.NotConstructible -> NOT_CONSTRUCTIBLE_MARKER
            else -> ""
        }

        // the default name of a companion object resolved directly is omitted, like in the listing of its outer class
        val name = simpleClassName(kmClass.name).takeUnless {
            kmClass.kind == ClassKind.COMPANION_OBJECT && it == DEFAULT_COMPANION_NAME
        } ?: ""
        val header = formatClassHeader(
            kmClass, formatResolvedClassName(kmClass, foundClass, name), contextPackage, typeParameters, constructorDeclaration,
            foundClass.classDeprecation
        )

        // the parameters are the only members of an annotation class, listed without its extensions
        if (annotationParameters != null) {
            return ClassListing.of(header, context = enclosingContext(kmClass, apiClass, foundClass, includingClass = false, isBuiltin))
        }

        // The companion object, unless it is not part of the API, with its members which are
        val companion = apiCompanionOf(kmClass, foundClass, apiClass)
        // the ones which are also sealed subclasses of this class are listed as such
        val companionNestedClasses = if (companion != null) {
            listedNestedClasses(
                companion.kmClass, companion.foundClass, companion.apiClass, kmClass.sealedSubclasses.mapTo(HashSet(), foundClass::located)
            )
        } else {
            emptyList()
        }

        val sealedSubclasses = if (isSealed) listedSealedSubclasses(kmClass, foundClass) else SealedSubclasses.NONE
        val nestedClasses = listedNestedClasses(kmClass, foundClass, apiClass)

        val members = buildList<ListingItem.ClassBodyItem> {
            // The sealed subclasses first, then the other nested classes, only referenced,
            // as they can be resolved by their own name
            sealedSubclasses.listed.forEach { listed ->
                add(ListingItem.Declaration(listOf(formatListedClassHeader(listed, contextPackage))))
            }
            // still telling the sealed subclasses which cannot be listed, as a when expression has to cover them,
            // escaped like in the branch covering it
            sealedSubclasses.notFound.forEach { subclassName ->
                add(ListingItem.Declaration(listOf("// sealed subclass not found: ${escapeQualifiedName(subclassName.replace('/', '.'))}")))
            }
            sealedSubclasses.notRead.forEach { subclassName ->
                add(ListingItem.Declaration(listOf("// sealed subclass could not be read: ${escapeQualifiedName(subclassName.replace('/', '.'))}")))
            }
            if (sealedSubclasses.hasOmitted) {
                add(ListingItem.Declaration(listOf(SEALED_SUBCLASSES_OMITTED)))
            }
            nestedClasses.forEach { listed ->
                add(ListingItem.Declaration(listOf(formatListedClassHeader(listed, contextPackage))))
            }

            // Properties (excluding constructor properties), each with its name
            val propertyItems = properties
                .filter { it !in declaredInConstructor }
                .map { property ->
                    property.name to formatMember(
                        Member.Property(property), kmClass, foundClass, apiClass, contextPackage, isOpenForOverrides,
                        typeParameters = typeParameters
                    )
                }

            // Secondary constructors, in the conventional order, between the properties and the functions
            val constructorItems = secondaryConstructors.map { constructor ->
                listOf(
                    formatConstructor(
                        constructor, isPrimary = false, constructor.visibility, foundClass.deprecation(constructor),
                        contextPackage, typeParameters
                    )
                )
            }

            // Functions, each with its name
            val functionItems = functions.map { function ->
                function.name to formatMember(
                    Member.Function(function), kmClass, foundClass, apiClass, contextPackage, isOpenForOverrides,
                    typeParameters = typeParameters
                )
            }

            if (isBuiltin) {
                // the members of a built-in type, sorted by name with its JVM-only members, and its extensions
                // reading like members, the constructors first, like in the source of the standard library
                constructorItems.forEach { add(ListingItem.Declaration(it)) }
                (propertyItems + functionItems +
                    builtinBodyItems(kmClass, foundClass, apiClass, typeParameters, listedExtensions, bodyExtensions))
                    .sortedBy { (name, _) -> name }
                    .forEach { (_, lines) -> add(ListingItem.Declaration(lines)) }
            } else {
                propertyItems.forEach { (_, lines) -> add(ListingItem.Declaration(lines)) }
                constructorItems.forEach { add(ListingItem.Declaration(it)) }
                functionItems.forEach { (_, lines) -> add(ListingItem.Declaration(lines)) }
            }

            // even without members of the API, the companion object is a value, like a CoroutineContext.Key,
            // rendered then without a body
            if (companion != null) {
                // a single member of the outer class, never split across pages
                add(ListingItem.CompanionObject(
                    header = formatCompanionHeader(kmClass, companion, contextPackage),
                    members = buildList {
                        // Companion nested classes, only referenced, like the ones of the outer class
                        companionNestedClasses.forEach { listed ->
                            add(listOf(formatListedClassHeader(listed, contextPackage)))
                        }

                        // Companion properties and functions, sorted by name for a built-in type, like its members
                        val companionMembers = companion.properties.map { property ->
                            property.name to formatCompanionProperty(property, companion, contextPackage)
                        } + companion.functions.map { function ->
                            function.name to listOf(formatCompanionFunction(function, companion, contextPackage))
                        }
                        (if (isBuiltin) companionMembers.sortedBy { (name, _) -> name } else companionMembers)
                            .forEach { (_, lines) -> add(lines) }
                    },
                    name = escapeQualifiedName("$className.${kmClass.companionObject}")
                ))
            }
        }

        return ClassListing.of(
            header = header,
            context = enclosingContext(kmClass, apiClass, foundClass, includingClass = false, isBuiltin),
            isEnum = isEnum,
            enumEntryNames = if (isEnum) {
                kmClass.kmEnumEntries.mapNotNull { formatEnumEntry(it, foundClass, withHidden = true) }
            } else emptyList(),
            members = members,
            extensions = extensionSignatures(extensions, extensionProperties, contextPackage)
        )
    }

    /**
     * The signature telling apart the overloads of a [function], like `plus(Int)`, without its receiver,
     * where the type parameters in scope are the [classTypeParameters], and its own ones,
     * but the ones of an extension listed in the body of its receiver class, see [bodyReceiverSubstitution].
     *
     * @param ignoringDefinitelyNotNull whether the uses as definitely not nullable, like `T & Any`, are told as just `T`,
     *   see [TypeParameterScope.ignoringDefinitelyNotNull], like for the declarations of the same JVM method,
     *   rather than told apart, like the extensions declared in two packages, read alike in the listing only when the same.
     */
    private fun overloadSignature(
        function: KmFunction,
        classTypeParameters: Map<Int, String>,
        bodyReceiverSubstitution: Map<Int, String> = emptyMap(),
        ignoringDefinitelyNotNull: Boolean = false
    ): String {
        val names = classTypeParameters + function.typeParameters.namesById() + bodyReceiverSubstitution
        val typeParameters = if (ignoringDefinitelyNotNull) {
            TypeParameterScope.ignoringDefinitelyNotNull(names)
        } else {
            TypeParameterScope.markingDefinitelyNotNull(names)
        }
        return function.name + function.valueParameters.joinToString(", ", "(", ")") {
            formatType(it.type, typeParameters = typeParameters)
        }
    }

    /**
     * The name and the types of the value parameters of a function, see [callSignature],
     * telling which extensions a member shadows, see [MemberCallSignature].
     */
    private class CallSignature(val name: String, val parameterTypes: List<String>)

    /**
     * The types of the value parameters which a member function accepts, each a set of the formatted types,
     * see [memberCallSignature], shadowing an extension of the same name, called instead,
     * when it accepts each of the parameter types of the extension, see [shadows].
     */
    private class MemberCallSignature(
        val name: String,
        val parameterTypes: List<Set<String>>,
        private val declaredParameterTypes: List<String>
    ) {

        fun shadows(signature: CallSignature): Boolean =
            name == signature.name && parameterTypes.size == signature.parameterTypes.size &&
                parameterTypes.zip(signature.parameterTypes).all { (accepted, type) -> type in accepted }

        /**
         * Whether the extension of the [signature] accepts every argument of the member, declaring its very parameter types,
         * unlike `Throwable.addSuppressed(exception: Throwable)`, which does not accept the `null`
         * which the member `addSuppressed(arg0: Throwable?)` of `java.lang.Throwable` does.
         */
        fun isCoveredBy(signature: CallSignature): Boolean =
            name == signature.name && declaredParameterTypes == signature.parameterTypes

    }

    /**
     * The type of a value [parameter], formatted with the [typeParameters], see [formatType],
     * a vararg one by its element type, like `vararg Any?`, as it is called with its elements,
     * not nullable, when [notNullable].
     */
    private fun callParameterType(parameter: KmValueParameter, typeParameters: Map<Int, String>, notNullable: Boolean = false): String {
        val elementType = parameter.varargElementType
        val type = elementType ?: parameter.type
        // a use as definitely not nullable, like `V & Any`, is always told, as a member taking it does not accept a `V`,
        // the member and the extension signatures formatted alike, regardless of the bounds
        val formatted = formatType(
            if (notNullable) type.notNullable() else type,
            typeParameters = TypeParameterScope.markingDefinitelyNotNull(typeParameters)
        )
        return if (elementType != null) "vararg $formatted" else formatted
    }

    /**
     * The call signature of an extension [function], see [CallSignature],
     * its type parameters standing for the ones of the class in the [bodyReceiverSubstitution].
     */
    private fun callSignature(function: KmFunction, bodyReceiverSubstitution: Map<Int, String>): CallSignature {
        val typeParameters = function.typeParameters.namesById() + bodyReceiverSubstitution
        return CallSignature(function.name, function.valueParameters.map { callParameterType(it, typeParameters) })
    }

    /**
     * The types which a member [function] accepts, see [MemberCallSignature], where the type parameters in scope
     * are the [classTypeParameters], and its own ones: the type of each parameter, and also the not nullable one
     * of a nullable parameter, like the platform type `Any!` of a Java parameter, see [toKmType],
     * like `java.io.PrintStream` of the member `printStackTrace(arg0: java.io.PrintStream?)` of `java.lang.Throwable`,
     * also of the element of a vararg, like the `Any?` of `Object...`.
     */
    private fun memberCallSignature(function: KmFunction, classTypeParameters: Map<Int, String>): MemberCallSignature {
        val typeParameters = classTypeParameters + function.typeParameters.namesById()
        val declaredParameterTypes = function.valueParameters.map { callParameterType(it, typeParameters) }
        return MemberCallSignature(
            function.name,
            function.valueParameters.zip(declaredParameterTypes) { parameter, declaredType ->
                val type = parameter.varargElementType ?: parameter.type
                setOfNotNull(
                    declaredType,
                    if (type.isNullable) callParameterType(parameter, typeParameters, notNullable = true) else null
                )
            },
            declaredParameterTypes
        )
    }

    /**
     * The [listedExtensions] of a built-in type, the [kmClass], reading like its members, see [bodyReceiverSubstitution],
     * which its body lists, except the ones shadowed by its members, called instead, see [memberCallSignature],
     * unless hiding them, see [jvmOnlyFunctionsNotHidden],
     * which would read like duplicates of these members,
     * and the same extension declared in two packages, like `KClass.cast()` of `kotlin.reflect.full`,
     * told apart only by the source to import it from, which the class body does not show.
     *
     * @param typeParameters the names of the type parameters in scope of the [kmClass].
     */
    private fun bodyExtensionsOf(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        typeParameters: Map<Int, String>,
        listedExtensions: ListedExtensions
    ): ListedExtensions {
        val builtinClass = declarations as? BuiltinClass
        val memberFunctions = (kmClass.functions + builtinClass?.jvmOnlyFunctions.orEmpty())
            .map { memberCallSignature(it, typeParameters) }
            .groupBy { it.name }
        val memberPropertyNames = (kmClass.properties + builtinClass?.jvmOnlyProperties.orEmpty()).mapTo(HashSet()) { it.name }
        val signatures = listedExtensions.functions.filter { !it.isObjectMember }.associateWith { extension ->
            bodyReceiverSubstitution(extension.function, kmClass)
                ?.takeIf { substitution ->
                    val signature = callSignature(extension.function, substitution)
                    extension.hidesMembers || memberFunctions[signature.name].orEmpty().none { it.shadows(signature) }
                }
                ?.let { overloadSignature(extension.function, emptyMap(), it) }
        }
        val ambiguousSignatures = signatures.values.filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return ListedExtensions(
            listedExtensions.functions.filter { extension -> signatures[extension]?.let { it !in ambiguousSignatures } == true },
            listedExtensions.properties.filter { extension ->
                !extension.isObjectMember && extension.property.name !in memberPropertyNames &&
                    bodyReceiverTypeParameters(extension.property.receiverParameterType, extension.property.typeParameters, kmClass) != null
            }
        )
    }

    /**
     * The JVM-only functions of a built-in type, the [kmClass], see [BuiltinClass.jvmOnlyFunctions],
     * but the ones hidden by one of the [extensions] annotated with `@kotlin.internal.HidesMembers`,
     * reading like a member of the same signature, see [memberCallSignature], which is called instead,
     * for every argument the member accepts, see [MemberCallSignature.isCoveredBy],
     * while a member of another signature is still called,
     * like `forEach(arg0: java.util.function.Consumer<in T>?)` of `java.lang.Iterable` next to `Iterable<T>.forEach(action)`,
     * and so is a member accepting more, like `addSuppressed(arg0: Throwable?)` of `java.lang.Throwable`,
     * called by `addSuppressed(null)`, while `fun Throwable.addSuppressed(exception: Throwable)` is called for the rest,
     * thus one of the extensions called instead of a member for some arguments, added to the [hidingExtensions], if given.
     */
    private fun jvmOnlyFunctionsNotHidden(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        extensions: List<IndexedExtension>,
        hidingExtensions: MutableList<IndexedExtension>? = null
    ): List<KmFunction> {
        val jvmOnlyFunctions = (declarations as? BuiltinClass)?.jvmOnlyFunctions.orEmpty()
        if (jvmOnlyFunctions.isEmpty()) return jvmOnlyFunctions
        val hiding = extensions.filter { it.hidesMembers && !it.isObjectMember }.mapNotNull { extension ->
            bodyReceiverSubstitution(extension.function, kmClass)?.let { extension to callSignature(extension.function, it) }
        }
        if (hiding.isEmpty()) return jvmOnlyFunctions
        val typeParameters = typeParameterScopeOf(kmClass, declarations).names
        val members = jvmOnlyFunctions.map { memberCallSignature(it, typeParameters) }
        hidingExtensions?.let { added ->
            hiding.filterTo(ArrayList()) { (_, signature) -> members.any { it.shadows(signature) } }
                .mapTo(added) { (extension, _) -> extension }
        }
        return jvmOnlyFunctions.filterIndexed { index, _ ->
            hiding.none { (_, signature) -> members[index].isCoveredBy(signature) }
        }
    }

    /**
     * The receiver class of an extension listed in its body, see [bodyReceiverTypeParameters]:
     * the [typeParameters] of the class which the ones of the extension stand for, by their ids,
     * and the type parameters in scope of the class, the [classScope], telling the not nullable ones,
     * kept together, so that no rendering renames them without telling the not nullable ones, see [TypeParameterScope.withStandingFor].
     */
    private class BodyReceiver(val typeParameters: Map<Int, KmTypeParameter>, val classScope: TypeParameterScope)

    /**
     * The declarations a built-in type lists in its body, besides its own properties and functions, each with its name:
     * its JVM-only members, see [BuiltinClass], but the ones hidden by its [listedExtensions],
     * like in its lookup, see [jvmOnlyFunctionsNotHidden], and the [bodyExtensions], see [bodyExtensionsOf].
     *
     * @param typeParameters the type parameters in scope of the [kmClass].
     */
    private fun builtinBodyItems(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        apiClass: ApiClass,
        typeParameters: TypeParameterScope,
        listedExtensions: ListedExtensions,
        bodyExtensions: ListedExtensions
    ): List<Pair<String, List<String>>> {
        val contextPackage = contextPackageOf(declarations, kmClass)
        val isOpenForOverrides = isOpenForOverrides(kmClass, apiClass)
        val builtinClass = declarations as? BuiltinClass
        // formatted like the members the kmClass declares, see formatClass
        val jvmOnlyProperties = builtinClass?.jvmOnlyProperties.orEmpty().inApi(apiClass, declarations).map { property ->
            property.name to formatMember(
                Member.Property(property), kmClass, declarations, apiClass, contextPackage, isOpenForOverrides,
                typeParameters = typeParameters
            )
        }
        val jvmOnlyFunctions = jvmOnlyFunctionsNotHidden(kmClass, declarations, listedExtensions.functions)
            .inApi(apiClass, declarations)
            .map { function ->
                function.name to formatMember(
                    Member.Function(function), kmClass, declarations, apiClass, contextPackage, isOpenForOverrides,
                    typeParameters = typeParameters
                )
            }
        val bodyExtensionProperties = bodyExtensions.properties.map { extension ->
            extension.property.name to formatProperty(
                extension.property, extension.setterVisibility, contextPackage = contextPackage,
                bodyReceiver = bodyReceiverTypeParameters(
                    extension.property.receiverParameterType, extension.property.typeParameters, kmClass
                )?.let { BodyReceiver(it, typeParameters) },
                deprecation = extension.deprecation
            )
        }
        val bodyExtensionFunctions = bodyExtensions.functions.map { extension ->
            extension.function.name to listOf(
                formatFunction(
                    extension.function, contextPackage = contextPackage,
                    bodyReceiver = bodyReceiverTypeParameters(extension.function, kmClass)?.let { BodyReceiver(it, typeParameters) },
                    deprecation = extension.deprecation
                )
            )
        }
        return jvmOnlyProperties + jvmOnlyFunctions + bodyExtensionProperties + bodyExtensionFunctions
    }

    /**
     * The type parameters of an extension [function] of a built-in type, the [kmClass], which stand for the ones of the class,
     * see [bodyReceiverTypeParameters], mapped to the names of the type parameters of the class,
     * like `T` of `fun <T> MutableList<T>.fill(value: T)` to `E` of `interface MutableList<E>`, or `null`.
     */
    private fun bodyReceiverSubstitution(function: KmFunction, kmClass: KmClass): Map<Int, String>? =
        bodyReceiverTypeParameters(function, kmClass)?.mapValues { (_, classTypeParameter) -> classTypeParameter.name }

    /**
     * The type parameters of an extension [function] of a built-in type, the [kmClass], which stand for the ones of the class,
     * told by its receiver, and by the types of its context and value parameters, see the overload taking them.
     */
    @OptIn(ExperimentalContextParameters::class)
    private fun bodyReceiverTypeParameters(function: KmFunction, kmClass: KmClass): Map<Int, KmTypeParameter>? =
        bodyReceiverTypeParameters(
            function.receiverParameterType, function.typeParameters, kmClass,
            signatureTypes = function.contextParameters.map { it.type } + function.valueParameters.map { it.type }
        )

    /**
     * Whether the [type] of a parameter of an extension accepts the same arguments when the type parameter of the [id],
     * of a type argument of the receiver projected with the [variance], is read as the type parameter of the class,
     * like `Iterable<T>` read as `Iterable<E>` of `fun <T> MutableCollection<in T>.plusAssign(elements: Iterable<T>)`,
     * where `T` stands for any subtype of `E`: when it occurs only where a subtype is accepted for `in`,
     * like the type of the parameter itself, or an `out` type argument, or only where a supertype is accepted for `out`,
     * like the parameter of a function type, like `(Map.Entry<K, V>) -> Unit` of `fun <K, V> Map<out K, V>.forEach(action)`.
     * Unlike an invariant type argument, like `Array<T>`, accepting `Array<E>` only, narrower than `Array<out E>`,
     * or the type of a parameter `key: K` of `fun <K, V> Map<out K, V>.minus(key: K)`, which accepts any supertype of `K`.
     * The variance of a type parameter of a class which cannot be read is not told, and taken as invariant.
     */
    private fun acceptsAsClassTypeParameter(type: KmType, id: Int, variance: KmVariance): Boolean {
        val required = if (variance == KmVariance.IN) 1 else -1
        fun accepts(type: KmType, polarity: Int): Boolean {
            val classifier = type.classifier
            if (classifier is KmClassifier.TypeParameter && classifier.id == id) return polarity == required
            val declaredVariances = (classifier as? KmClassifier.Class)?.name?.let(::declaredVariancesOf).orEmpty()
            return type.arguments.withIndex().all { (index, argument) ->
                val argumentType = argument.type ?: return@all true
                val argumentVariance = argument.variance?.takeIf { it != KmVariance.INVARIANT }
                    ?: declaredVariances.getOrNull(index)
                    ?: KmVariance.INVARIANT
                accepts(
                    argumentType,
                    when (argumentVariance) {
                        KmVariance.OUT -> polarity
                        KmVariance.IN -> -polarity
                        KmVariance.INVARIANT -> 0
                    }
                )
            } && type.outerType?.let { accepts(it, polarity) } != false
        }
        return accepts(type, polarity = 1)
    }

    /**
     * The variances of the type parameters of the class of the metadata [name], like `[IN, OUT]` of `kotlin/Function1`,
     * also of a suspend function type, as the metadata stores it as the function type of one more parameter,
     * the continuation, like `kotlin/Function2` of `suspend (K) -> Unit`, never by its own name, like `SuspendFunction1`,
     * or none, if it cannot be read.
     */
    private fun declaredVariancesOf(name: String): List<KmVariance> {
        parseFunctionTypeName(name)?.takeIf { it.kind == FunctionTypeKind.FUNCTION }?.let { functionType ->
            return List(functionType.arity) { KmVariance.IN } + KmVariance.OUT
        }
        return (builtinClasses[name] ?: loadKmClass(name))?.typeParameters?.map { it.variance }.orEmpty()
    }

    /**
     * The type parameters of an extension of a built-in type, the [kmClass], with the [receiver] type,
     * declaring the [typeParameters], which stand for the type parameters of the class,
     * when the extension is listed in the class body without its receiver, like a member,
     * by their ids, mapped to the type parameters of the class,
     * like `T` of `fun <T> MutableList<T>.fill(value: T)`, listed as `fun fill(value: E)` in `interface MutableList<E>`,
     * or `null` if the extension does not read like a member, callable on every instance of the class,
     * as its receiver is not the type of the class itself, and it is then listed outside the class body, with its receiver:
     * a nullable one, like of `fun String?.orEmpty()`, callable also on `null`,
     * one with type arguments other than star projections, or distinct type parameters of the extension,
     * like of `fun Iterable<Int>.average()`,
     * or with type parameters which are not just placeholders for the ones of the class,
     * bounded by other types than them, like of `fun <T : Comparable<T>> List<T>.sorted()`, callable only on some of the lists,
     * or a reified one, or another one sharing the name of a type parameter of the class, which would read like the same one,
     * or one of a projected type argument, standing for any subtype, like `T` of `MutableCollection<in T>`,
     * or any supertype, like `K` of `Map<out K, V>`, of the type parameter of the class, see [acceptsAsClassTypeParameter],
     * unless the projection is the variance of the type parameter of the class, like `out T` of `List<out T>`,
     * standing for its type argument, like `T` of `List<T>`.
     *
     * @param signatureTypes the types of the parameters of the extension, which read the type parameters
     *   of the class in place of the projected ones, see [acceptsAsClassTypeParameter],
     *   and the bounds of its other type parameters.
     */
    private fun bodyReceiverTypeParameters(
        receiver: KmType?,
        typeParameters: List<KmTypeParameter>,
        kmClass: KmClass,
        signatureTypes: List<KmType> = emptyList()
    ): Map<Int, KmTypeParameter>? {
        if (receiver == null || receiver.isNullable || (receiver.classifier as? KmClassifier.Class)?.name != kmClass.name) return null
        if (receiver.arguments.size != kmClass.typeParameters.size) return null
        // the type parameters of the extension standing for the ones of the class
        val substituted = mutableMapOf<KmTypeParameter, KmTypeParameter>()
        receiver.arguments.zip(kmClass.typeParameters) { argument, classTypeParameter ->
            // a star projection, like of `val Collection<*>.indices`, stands for any type argument
            val type = argument.type ?: return@zip
            if (type.isNullable) return null
            val id = (type.classifier as? KmClassifier.TypeParameter)?.id ?: return null
            val typeParameter = typeParameters.firstOrNull { it.id == id } ?: return null
            if (typeParameter.isReified || substituted.put(typeParameter, classTypeParameter) != null) return null
            val variance = argument.variance ?: KmVariance.INVARIANT
            if (variance != KmVariance.INVARIANT && variance != classTypeParameter.variance) {
                val otherBounds = typeParameters.filter { it.id != id }.flatMap { it.upperBounds }
                if ((signatureTypes + otherBounds).any { !acceptsAsClassTypeParameter(it, id, variance) }) return null
            }
        }
        val substitution = substituted.entries.associate { (typeParameter, classTypeParameter) ->
            typeParameter.id to classTypeParameter.name
        }
        // like the ones of the class, also within the bounds, like `T : Enum<T>` of `val <T : Enum<T>> Enum<T>.declaringJavaClass`
        val classTypeParameters = kmClass.typeParameters.namesById()
        fun List<KmType>.bounds(typeParameters: Map<Int, String>) =
            filterNot { it.isNullableAny() }.mapTo(HashSet()) {
                formatType(it, typeParameters = TypeParameterScope.markingDefinitelyNotNull(typeParameters))
            }
        if (substituted.any { (typeParameter, classTypeParameter) ->
                !classTypeParameter.upperBounds.bounds(classTypeParameters).containsAll(typeParameter.upperBounds.bounds(substitution))
            }
        ) return null
        val classTypeParameterNames = classTypeParameters.values.toSet()
        if (typeParameters.any { it.id !in substitution && it.name in classTypeParameterNames }) return null
        return substituted.entries.associate { (typeParameter, classTypeParameter) -> typeParameter.id to classTypeParameter }
    }

    /**
     * A constructor: a secondary one, like `constructor(name: String, vararg tags: String)`,
     * without the delegation call, or the primary one, following the class name, like `(val id: Int)`,
     * or ` protected constructor(val id: Int)`, as its keyword is required only with a modifier or an annotation.
     *
     * @param visibility the visibility to render, omitted with the keyword for a public primary constructor.
     * @param deprecation the deprecation to render, see [formatDeprecation].
     * @param formatParameter renders a value parameter, like the one of the primary constructor declaring a property.
     */
    private fun formatConstructor(
        constructor: KmConstructor,
        isPrimary: Boolean,
        visibility: Visibility,
        deprecation: Deprecation?,
        contextPackage: String,
        typeParameters: TypeParameterScope,
        formatParameter: (KmValueParameter) -> String = { formatValueParameter(it, contextPackage, typeParameters) }
    ): String = buildString {
        if (!isPrimary || visibility != Visibility.PUBLIC || deprecation != null) {
            if (isPrimary) +" "
            +formatDeprecation(deprecation)
            +formatVisibility(visibility)
            +"constructor"
        }
        +constructor.valueParameters.joinToString(", ", "(", ")", transform = formatParameter)
    }

    /**
     * The header of a class declaration: the modifiers and the kind, the [name], the type parameters,
     * the [constructor] declaration, if any, the supertypes and the where clause.
     *
     * @param name the name to render, escaped, possibly qualified, like `Holder.Nested`, or `Outer<T>.Inner`,
     *   or empty for a default companion object.
     * @param typeParameters the type parameters in scope, including the ones
     *   of the outer class of an inner class.
     * @param deprecation the deprecation of the class, see [formatDeprecation].
     * @param kind the kind of the class to render, like the one of a listed companion object, see [formatListedClassHeader].
     */
    private fun formatClassHeader(
        kmClass: KmClass,
        name: String,
        contextPackage: String,
        typeParameters: TypeParameterScope = TypeParameterScope.EMPTY.withDeclared(kmClass.typeParameters),
        constructor: String = "",
        deprecation: Deprecation? = null,
        kind: ClassKind = kmClass.kind
    ): String = buildString {
        +formatDeprecation(deprecation)
        // the prefix ends with a space, like in `companion object `
        +formatClassPrefix(kmClass, kind, formatClassContextReceivers(kmClass, contextPackage, typeParameters))
            .let { if (name.isEmpty()) it.trimEnd() else it + name }
        +formatTypeParameters(kmClass.typeParameters, typeParameters, contextPackage)
        +constructor
        val supertypes = formatSupertypes(kmClass, contextPackage, typeParameters)
        if (supertypes.isNotEmpty()) {
            +" : "
            +supertypes.joinToString(", ")
        }
        +formatWhereClause(kmClass.typeParameters, typeParameters, contextPackage)
    }

    /**
     * The legacy context receivers of a class, like `context(Logger) `, compiled with `-Xcontext-receivers`
     * by a Kotlin compiler before 2.2, in their own syntax, without names, as a class cannot declare context parameters,
     * followed by the [LEGACY_CONTEXT_RECEIVERS] comment, as Kotlin 2.2+ cannot compile the header copied from the listing.
     * The ones of a function or a property are read as its context parameters by kotlin-metadata-jvm,
     * rendered like `context(_: Logger) `, which Kotlin 2.2+ compiles.
     */
    private fun formatClassContextReceivers(
        kmClass: KmClass,
        contextPackage: String,
        typeParameters: TypeParameterScope
    ): String = formatContext(kmClass.legacyContextReceiverTypes.map { formatType(it, contextPackage, typeParameters) })
        .let { if (it.isEmpty()) it else "$it$LEGACY_CONTEXT_RECEIVERS " }

    /**
     * The header of a [listed] class, like a nested class, see [formatClassHeader], without its constructor,
     * where the companion object of another class, listed as a sealed subclass, is referred to as an object,
     * like `object Holder.Companion : Sealed`, as a companion object can only be declared in its own class.
     */
    private fun formatListedClassHeader(listed: ListedClass, contextPackage: String): String =
        formatClassHeader(
            listed.kmClass, formatListedClassName(listed), contextPackage, typeParameterScopeOf(listed.kmClass, listed.foundClass),
            deprecation = listed.foundClass.classDeprecation,
            kind = listed.kmClass.kind.takeUnless { it == ClassKind.COMPANION_OBJECT } ?: ClassKind.OBJECT
        )

    /**
     * The name of a [listed] class, escaped, where the outer classes qualifying an inner class are rendered
     * with the type parameters it can refer to, like `Outer<T>.Inner`, the way Kotlin writes its type,
     * as the listing class does not declare them, like for a sealed subclass declared in another class,
     * see [outerTypeParametersOf].
     */
    private fun formatListedClassName(listed: ListedClass): String {
        val segments = listed.name.split('.')
        val typeArguments = arrayOfNulls<String>(segments.size)
        outerTypeParametersOf(listed.kmClass, listed.foundClass).forEachIndexed { index, outer ->
            val segment = segments.lastIndex - 1 - index
            if (segment >= 0) typeArguments[segment] = outer.formatTypeArguments()
        }
        return segments.indices.joinToString(".") { escapeIdentifier(segments[it]) + typeArguments[it].orEmpty() }
    }

    /**
     * The [name] of the [kmClass] resolved directly, escaped, qualified, when it is an inner class,
     * with its outer classes declaring the type parameters it can refer to, like `Outer<T>.Inner`,
     * up to the outermost one declaring any, like a listed class, see [formatListedClassName],
     * as nothing else declares them, also for the classes listed in it, like `inner class Leaf : Map.Entry<A, B>`
     * of `inner class Holder<A>.Mid<B>`.
     */
    private fun formatResolvedClassName(kmClass: KmClass, declarations: ClassDeclarations, name: String): String {
        val outers = outerTypeParametersOf(kmClass, declarations)
        val qualifying = outers.take(outers.indexOfLast { it.typeParameters.isNotEmpty() } + 1).asReversed()
        return (qualifying.map { escapeIdentifier(simpleClassName(it.name)) + it.formatTypeArguments().orEmpty() } +
            escapeIdentifier(name)).joinToString(".")
    }

    /**
     * An outer class of an inner class, by its located [name], see [locatedNameOf], with the names of its [typeParameters] by their ids,
     * which the inner class can refer to, as its [declarations] declare them, unless its declaration cannot be read.
     */
    private class OuterTypeParameters(
        val name: String,
        val typeParameters: Map<Int, String>,
        val declarations: List<KmTypeParameter> = emptyList()
    ) {

        /**
         * The type parameters as the type arguments qualifying the inner class, like `<T>` of `Outer<T>.Inner`,
         * or `null` if there are none.
         */
        fun formatTypeArguments(): String? =
            typeParameters.values.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { escapeIdentifier(it) }

    }

    /**
     * The outer classes of the [kmClass], from the innermost one, as long as the class enclosed by each is inner,
     * and can therefore refer to its type parameters, see [OuterTypeParameters],
     * looked up by their located names, see [locatedNameOf], like the ones of a class relocated by shading,
     * the [kmClass] being the one of the [declarations].
     * The names of the type parameters of an outer class whose declaration cannot be read,
     * like when its class file is absent from the classpath, are not known, nor whether it is inner itself,
     * so that the ones the [kmClass] refers to, which are not of the other outer classes read,
     * like the ones of an outer class of the outer class which cannot be read, are given to it,
     * named by their ids, like `T0`, see [referencedTypeParameterIds],
     * followed by as many `_` as needed to tell them apart from the type parameters declared otherwise,
     * like `T0_` when the [kmClass], one of its outer classes read, or one of their members, declares `T0`.
     */
    private fun outerTypeParametersOf(kmClass: KmClass, declarations: ClassDeclarations): List<OuterTypeParameters> {
        val outers = mutableListOf<OuterTypeParameters>()
        // the outer classes read, whose members might declare type parameters too
        val readOuters = mutableListOf<KmClass>()
        var currentName = declarations.located(kmClass.name)
        var current: KmClass? = kmClass
        // the ids referred to which are not told by the outer classes read, once one cannot be read, at that index
        var unknown: MutableSet<Int>? = null
        var unreadableIndex = -1
        while (current?.isInner ?: unknown!!.isNotEmpty()) {
            val outerName = enclosingClassNames(currentName).lastOrNull() ?: break
            val outer = loadKmClass(outerName)
            val typeParameters = outer?.typeParameters?.namesById().orEmpty()
            // past an outer class which cannot be read, whose being inner is not known,
            // an outer class read is one only when the inner class refers to its type parameters
            if (current == null && outer != null && typeParameters.keys.none { it in unknown!! }) break
            if (outer == null && unknown == null) {
                val known = kmClass.typeParameters.mapTo(HashSet()) { it.id } + outers.flatMap { it.typeParameters.keys }
                unknown = (referencedTypeParameterIds(kmClass) - known).toMutableSet()
                unreadableIndex = outers.size
            }
            unknown?.removeAll(typeParameters.keys)
            outers += OuterTypeParameters(outerName, typeParameters, outer?.typeParameters.orEmpty())
            outer?.let { readOuters += it }
            currentName = outerName
            current = outer
        }
        if (unknown != null) {
            val declared = (readOuters + kmClass).flatMapTo(HashSet()) { declaredTypeParameterNames(it) }
            outers[unreadableIndex] = OuterTypeParameters(
                outers[unreadableIndex].name,
                unknown.sorted().associateWith { id -> generateSequence("T$id") { "${it}_" }.first { it !in declared } }
            )
        }
        return outers
    }

    /**
     * The names of the type parameters which the [kmClass] declares, and its members.
     */
    private fun declaredTypeParameterNames(kmClass: KmClass): Set<String> =
        (kmClass.typeParameters + kmClass.functions.flatMap { it.typeParameters } + kmClass.properties.flatMap { it.typeParameters })
            .mapTo(HashSet()) { it.name }

    /**
     * The ids of the type parameters which the types of the [kmClass] refer to, in its header, and in its members,
     * but the ones its members declare themselves, like `R` of `fun <R> map(f: (T) -> R): R`,
     * computed once per class, as it is needed for each of its members rendered.
     */
    private fun referencedTypeParameterIds(kmClass: KmClass): Set<Int> =
        referencedTypeParameters.getOrCompute(kmClass.name) {
            BoundedCache.Computed(computeReferencedTypeParameterIds(kmClass), isCacheable = true)
        }.value

    // the referencedTypeParameterIds of the inner classes whose outer classes cannot be read, by their metadata names
    private val referencedTypeParameters = BoundedCache<String, Set<Int>>(capacity = 256)

    @OptIn(ExperimentalContextParameters::class)
    private fun computeReferencedTypeParameterIds(kmClass: KmClass): Set<Int> {
        val ids = HashSet<Int>()
        val declared = HashSet<Int>()
        fun visitType(type: KmType?) {
            if (type == null) return
            (type.classifier as? KmClassifier.TypeParameter)?.let { ids += it.id }
            type.arguments.forEach { visitType(it.type) }
            visitType(type.outerType)
        }
        fun visitTypeParameters(typeParameters: List<KmTypeParameter>) =
            typeParameters.forEach { typeParameter -> typeParameter.upperBounds.forEach(::visitType) }
        fun visitValueParameters(parameters: List<KmValueParameter>) =
            parameters.forEach { visitType(it.type); visitType(it.varargElementType) }
        fun visitCallable(
            typeParameters: List<KmTypeParameter>,
            receiver: KmType?,
            contextParameters: List<KmValueParameter>,
            valueParameters: List<KmValueParameter>,
            returnType: KmType
        ) {
            typeParameters.mapTo(declared) { it.id }
            visitTypeParameters(typeParameters)
            visitType(receiver)
            visitValueParameters(contextParameters)
            visitValueParameters(valueParameters)
            visitType(returnType)
        }
        visitTypeParameters(kmClass.typeParameters)
        kmClass.supertypes.forEach(::visitType)
        kmClass.constructors.forEach { visitValueParameters(it.valueParameters) }
        kmClass.functions.forEach {
            visitCallable(it.typeParameters, it.receiverParameterType, it.contextParameters, it.valueParameters, it.returnType)
        }
        kmClass.properties.forEach {
            visitCallable(it.typeParameters, it.receiverParameterType, it.contextParameters, emptyList(), it.returnType)
        }
        return ids - declared
    }

    /**
     * A class listed in another one, like a nested class, by its [name] relative to the listing class.
     */
    private class ListedClass(val name: String, val kmClass: KmClass, val foundClass: ClassDeclarations)

    /**
     * The `@Deprecated` annotation of a declaration which is deprecated, but can still be used, like
     * `@Deprecated("Use uppercase() instead.", ReplaceWith("uppercase(Locale.getDefault())"), level = DeprecationLevel.ERROR) `,
     * rendered inline, so that the declaration stays on a single line, or an empty string otherwise.
     * The level is rendered unless it is the default `WARNING`, like `ERROR`, telling that the declaration
     * can still be referred to, but not called, also when it comes from `@DeprecatedSinceKotlin`, see [deprecationOf].
     * A hidden deprecation, of a declaration which is not listed, but might still be resolved, like a class, is not rendered,
     * except [withHidden], the only places being the property declared by a primary constructor parameter
     * of a data class, or a value class, which must declare a property, like
     * `data class Foo(@Deprecated("Replaced", level = DeprecationLevel.HIDDEN) val a: Int)`, telling it cannot be used,
     * and the entry of an enum class in its listing, see [formatEnumEntry],
     * and the deprecations of the accessors of a `var`, also a hidden one, rendered as `@get:Deprecated`, or `@set:Deprecated`,
     * see [Deprecation.accessors], when it is rendered as a `var`, otherwise only the [one of reading it][Deprecation.ofReading].
     * Only the first non-blank line of a multiline message is rendered, followed by `…`, to keep it compact,
     * and the expression of `ReplaceWith` without its imports.
     */
    private fun formatDeprecation(
        deprecation: Deprecation?,
        withHidden: Boolean = false
    ): String = if (deprecation == null || (deprecation.isHidden && !withHidden)) "" else buildString {
        // telling that only the reading, or the assigning, of a var is deprecated
        +when (deprecation.target) {
            Deprecation.Target.DECLARATION -> "@Deprecated("
            Deprecation.Target.GETTER -> "@get:Deprecated("
            Deprecation.Target.SETTER -> "@set:Deprecated("
        }
        +formatDeprecationArguments(deprecation)
        +") "
        deprecation.accessors.forEach { +formatDeprecation(it) }
    }

    /**
     * The arguments of the `@Deprecated` annotation of the [deprecation], see [formatDeprecation],
     * like `"Use bar()", ReplaceWith("bar()"), level = DeprecationLevel.ERROR`.
     */
    private fun formatDeprecationArguments(deprecation: Deprecation): String = buildString {
        val message = deprecation.message.trim()
        val firstLine = message.lineSequence().first().trimEnd()
        +"\""
        +firstLine.escapeKotlin('"')
        if (firstLine.length < message.length) +" …"
        +"\""
        deprecation.replaceWith?.let {
            +", ReplaceWith(\""
            +it.escapeKotlin('"')
            +"\")"
        }
        if (deprecation.level != DeprecationLevel.WARNING) +", level = DeprecationLevel.${deprecation.level.name}"
    }

    /**
     * The [deprecation] of a companion object, which Kotlin reports when its extension is called, like `Foo.of()`,
     * rendered before the extension, see [withCompanionDeprecation], after its own one,
     * within a comment, as no annotation of the extension compiles to tell it, like
     * `/* companion object deprecated: "Use the constructor", level = DeprecationLevel.ERROR */`,
     * with the arguments of its `@Deprecated` annotation, see [formatDeprecationArguments],
     * but neither `/*`, nor `*/`, which would nest, or end, the comment,
     * qualified when [isAlsoCalledOnInstance], like an extension on a supertype of both the class and its companion object,
     * as Kotlin reports it only for `Foo.ext()`, not for `foo.ext()`, like
     * `/* companion object deprecated: "Use the constructor" (when called on the class name) */`.
     */
    private fun formatCompanionDeprecation(deprecation: Deprecation?, isAlsoCalledOnInstance: Boolean = false): String =
        if (deprecation == null) "" else formatDeprecationComment("companion object", deprecation, isAlsoCalledOnInstance)

    /**
     * The [deprecation] of the [declared] class, like `companion object`, see [formatCompanionDeprecation],
     * or `class Foo`, which Kotlin reports for an extension called on the name of the class, like `Foo.ext()`,
     * but not on its instance, like `foo.ext()`, told then on the extension, rather than before the page,
     * see [EnclosingClass.deprecationOnClassName], within a comment, like
     * `/* class Foo deprecated: "Use Bar" (when called on the class name) */`.
     */
    private fun formatDeprecationComment(declared: String, deprecation: Deprecation, isAlsoCalledOnInstance: Boolean): String {
        val arguments = formatDeprecationArguments(deprecation).replace("/*", "/ *").replace("*/", "* /")
        val qualifier = if (isAlsoCalledOnInstance) " (when called on the class name)" else ""
        return "/* $declared deprecated: $arguments$qualifier */ "
    }

    private fun formatSupertypes(
        kmClass: KmClass,
        contextPackage: String,
        typeParameters: TypeParameterScope
    ): List<String> = kmClass.supertypes
        .filter { supertype ->
            // the implicit supertypes are redundant
            when ((supertype.classifier as? KmClassifier.Class)?.name) {
                "kotlin/Any" -> false
                "kotlin/Enum" -> kmClass.kind != ClassKind.ENUM_CLASS
                "kotlin/Annotation" -> kmClass.kind != ClassKind.ANNOTATION_CLASS
                else -> true
            }
        }
        .map { formatType(it, contextPackage, typeParameters) }

    /**
     * The extensions shown after a class body, or found by a member name, each with its source,
     * the package, or object, to import it from, rendered under the `// from <source>` comment, see [renderLines],
     * grouped by source, properties first, then functions.
     * Both are sorted by name, and then by source class, like each list of the extension index,
     * still sorted here, as the extensions of several receiver types, like the supertypes, are merged.
     * The overloads declared in one file keep their order in the Kotlin metadata, fixed for a given binary,
     * usually the declaration order, which tends to list the basic overload first,
     * unlike a sort by the rendered signature, where, e.g., the type parameters would come first.
     * The extensions of all the receivers are sorted together, not grouped by receiver,
     * so that the overloads callable on the class, like `Iterable.first()` and `List.first()`, are adjacent.
     *
     * @param classDeprecations the deprecations of the classes the name of the class goes through,
     *   see [EnclosingClass.deprecationOnClassName], told on each extension called also on an instance of the class,
     *   see [IndexedExtension.isAlsoCalledOnInstance], for which Kotlin does not report them.
     * @param isAlsoCalledOnInstance whether the extension called on the name of a class, told to be called on its instance too,
     *   see [IndexedExtension.isAlsoCalledOnInstance], is, see [ListingItem.Extension.isAlsoCalledOnInstance].
     * @param isCalledOnClassName whether the extension, on the receiver of this type, is called on the name of a class,
     *   see [ListingItem.Extension.isCalledOnClassName].
     */
    private fun extensionSignatures(
        functions: List<IndexedExtension>,
        properties: List<IndexedExtensionProperty>,
        contextPackage: String?,
        classDeprecations: String = "",
        isAlsoCalledOnInstance: (Boolean) -> Boolean = { false },
        isCalledOnClassName: (extension: ReceiverBounded, receiver: KmType?) -> Boolean = { _, _ -> false }
    ): List<ListingItem.Extension> {
        val functionsBySource = functions.groupBy { it.source }
        val propertiesBySource = properties.groupBy { it.source }
        val sources = (functionsBySource.keys + propertiesBySource.keys).sorted()
        return sources.flatMap { source ->
            propertiesBySource[source].orEmpty()
                .sortedWith(extensionPropertyOrder)
                .map {
                    ListingItem.Extension(
                        source,
                        formatProperty(
                            it.property, it.setterVisibility, contextPackage = contextPackage,
                            deprecation = it.deprecation, companionDeprecation = it.companionDeprecation,
                            isAlsoCalledOnInstance = it.isAlsoCalledOnInstance,
                            classDeprecations = if (it.isAlsoCalledOnInstance) classDeprecations else ""
                        ),
                        isCalledOnClassName(it, it.property.receiverParameterType),
                        isAlsoCalledOnInstance(it.isAlsoCalledOnInstance)
                    )
                } +
                functionsBySource[source].orEmpty()
                    .sortedWith(extensionFunctionOrder)
                    .map {
                        ListingItem.Extension(
                            source,
                            listOf(
                                formatFunction(
                                    it.function, contextPackage = contextPackage,
                                    deprecation = it.deprecation, companionDeprecation = it.companionDeprecation,
                                    isAlsoCalledOnInstance = it.isAlsoCalledOnInstance,
                                    classDeprecations = if (it.isAlsoCalledOnInstance) classDeprecations else ""
                                )
                            ),
                            isCalledOnClassName(it, it.function.receiverParameterType),
                            isAlsoCalledOnInstance(it.isAlsoCalledOnInstance)
                        )
                    }
        }
    }

    /**
     * The sealed subclasses of a sealed class.
     *
     * @param listed the ones which are part of the API, see [listedSealedSubclasses].
     * @param notFound the located names, see [ClassDeclarations.located], of the ones which cannot be found,
     *   as their class files are absent.
     * @param notRead the located names of the ones whose class files are present, but cannot be read,
     *   like without the Kotlin metadata.
     * @param hasOmitted whether any of them is not part of the API, like an internal, or a hidden one,
     *   so that a `when` expression covering the listed ones still needs an `else` branch.
     */
    private class SealedSubclasses(
        val listed: List<ListedClass>,
        val notFound: List<String>,
        val notRead: List<String>,
        val hasOmitted: Boolean
    ) {
        companion object {
            val NONE = SealedSubclasses(emptyList(), emptyList(), emptyList(), hasOmitted = false)
        }
    }

    /**
     * The sealed subclasses of the [kmClass] which are part of the API, see [ApiClasses],
     * by their names relative to the [kmClass] when nested within it, or else to their package, like `Holder.Nested`,
     * sorted by these names, unlike in the metadata, sorted by the fully qualified names.
     * The ones which cannot be found, or read, are not listed, as neither their kind nor their visibility is known,
     * but still told, while the ones which cannot be loaded, but whose class files can still be read, are listed,
     * like by [lookUpClass].
     * The own companion object is not listed, as it is already rendered as such, when it is part of the API,
     * or else omitted like any other subclass which is not,
     * while the companion object of another class is listed, as a branch of a `when` expression.
     * All of them are looked up by their located names, see [ClassDeclarations.located], in the package of the [kmClass],
     * the one of its [declarations], as a sealed subclass is always declared in the package of its sealed class.
     */
    private fun listedSealedSubclasses(kmClass: KmClass, declarations: ClassDeclarations): SealedSubclasses {
        val notFound = mutableListOf<String>()
        val notRead = mutableListOf<String>()
        var hasOmitted = false
        val className = declarations.located(kmClass.name)
        val listed = kmClass.sealedSubclasses.map(declarations::located).mapNotNull { subclassName ->
            val subclassClass = findClassByMetadataName(subclassName)
            val subclass = subclassClass?.kmClass
            if (subclass == null) {
                if (isAbsent(subclassName)) notFound += subclassName else notRead += subclassName
                return@mapNotNull null
            }
            if (apiClasses.apiClass(subclassName) == null) {
                hasOmitted = true
                return@mapNotNull null
            }
            if (subclassName == "$className.${kmClass.companionObject}") return@mapNotNull null
            ListedClass(subclassName.removePrefix("$className.").substringAfterLast('/'), subclass, subclassClass)
        }
        return SealedSubclasses(listed.sortedBy { it.name }, notFound, notRead, hasOmitted)
    }

    /**
     * The nested classes worth listing in the outer class, by their simple names, excluding
     * the sealed subclasses and the companion object, which are already rendered,
     * and the ones which are not part of the API, see [ApiClasses].
     * Unlike the sealed subclasses, the ones which cannot be found are not told,
     * as a `when` expression need not cover them.
     *
     * @param declarations the declarations of the [kmClass], telling the located names of the nested classes,
     *   see [ClassDeclarations.located], by which they are looked up.
     * @param apiClass the [kmClass] as a class of the API, which the nested classes are derived from.
     * @param sealedSubclasses the located names of the declared sealed subclasses to exclude, the ones of the [kmClass],
     *   or of the class enclosing a companion object, which might be nested in that companion object.
     */
    private fun listedNestedClasses(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        apiClass: ApiClass,
        sealedSubclasses: Set<String> = kmClass.sealedSubclasses.mapTo(HashSet(), declarations::located)
    ): List<ListedClass> = kmClass.nestedClasses
        .map { declarations.located("${kmClass.name}.$it") }
        .filter { it !in sealedSubclasses }
        .mapNotNull { name ->
            val nestedClass = classDeclarations(name) ?: return@mapNotNull null
            val nested = nestedClass.kmClass ?: return@mapNotNull null
            if (nested.kind == ClassKind.COMPANION_OBJECT || apiClasses.nestedApiClass(apiClass, name) == null) return@mapNotNull null
            ListedClass(simpleClassName(name), nested, nestedClass)
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
        is Enum<*> -> "${formatJavaClassName(value.declaringJavaClass, contextPackage)}.${escapeIdentifier(value.name)}"
        is Class<*> -> "${formatJavaClassName(value, contextPackage)}::class"
        is Annotation -> {
            val annotationType = value.annotationClass.java
            annotationType.declaredMethods.sortedBy { it.name }.joinToString(
                ", ",
                prefix = "${formatJavaClassName(annotationType, contextPackage)}(",
                postfix = ")"
            ) { method ->
                "${escapeIdentifier(method.name)} = ${formatAnnotationValue(method.invoke(value), contextPackage)}"
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
        val kotlinName = javaClass.kotlin.qualifiedName ?: return escapeQualifiedName(javaClass.name)
        val javaName = javaClass.packageName.let { if (it.isEmpty()) "" else "$it." } + javaClass.nestedClassName()
        val simpleName = if (kotlinName == javaName) {
            kotlinName.removePrefix("${javaClass.packageName}.")
        } else {
            // a JVM type mapped to a Kotlin built-in type, like java.lang.String
            kotlinName.substringAfterLast('.')
        }
        return escapeQualifiedName(
            if (isWellKnownType(kotlinName) || javaClass.packageName == contextPackage) simpleName else kotlinName
        )
    }

    /**
     * @param owner the class declaring the [function], to tell whether it is an override.
     * @param classTypeParameters the type parameters in scope of the class, see [TypeParameterScope],
     *   like the ones of a class inheriting the [function], given for the ones of its class.
     * @param bodyReceiver the receiver class of an extension listed in its body, see [BodyReceiver],
     *   rendered without its receiver, or `null` to render the receiver.
     * @param deprecation the deprecation of the [function], see [formatDeprecation].
     * @param companionDeprecation the deprecation of the companion object the [function] extends,
     *   see [formatCompanionDeprecation].
     * @param isAlsoCalledOnInstance whether the [function] is also called on an instance of the class,
     *   not reporting the [companionDeprecation] then, see [formatCompanionDeprecation].
     * @param classDeprecations the deprecations of the classes the call of the [function] on the name of the class goes through,
     *   see [extensionSignatures].
     * @param isOwnerOpenForOverrides whether the user code might override the members of the [owner],
     *   see [formatModalityModifiers], by default unless final.
     * @param declaration the [function] as the [owner] declares it, telling whether it is an override,
     *   when the rendered one is a copy with the type arguments of an inheriting class substituted, see [inheritedMembers].
     * @param isJvmOnlyOverride whether the [function] is an override of a JVM-only member of a built-in type,
     *   which the [owner] does not tell, see [InheritedCandidate.isJvmOnlyOverride].
     * @param jvmDescriptor the JVM descriptor of the Java method of the [function], if it is one,
     *   telling its overrides by its primitive types, see [OverrideResolver.overriddenMembers].
     */
    @OptIn(ExperimentalContextParameters::class)
    private fun formatFunction(
        function: KmFunction,
        inInterface: Boolean = false,
        owner: OverrideResolver.Owner? = null,
        contextPackage: String? = null,
        classTypeParameters: TypeParameterScope = TypeParameterScope.EMPTY,
        bodyReceiver: BodyReceiver? = null,
        deprecation: Deprecation? = null,
        companionDeprecation: Deprecation? = null,
        isAlsoCalledOnInstance: Boolean = false,
        classDeprecations: String = "",
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner?.kmClass),
        isInheritedByFinalClass: Boolean = false,
        declaration: KmFunction = function,
        isJvmOnlyOverride: Boolean = false,
        jvmDescriptor: String? = null
    ): String = buildString {
        val typeParameters = classTypeParameters.withDeclared(function.typeParameters)
            .withStandingFor(bodyReceiver?.typeParameters.orEmpty(), bodyReceiver?.classScope ?: TypeParameterScope.EMPTY)
        // the type parameters standing for the ones of the class are not declared
        val declaredTypeParameters = function.typeParameters.filter { it.id !in bodyReceiver?.typeParameters.orEmpty() }

        +formatDeprecation(deprecation)
        +classDeprecations
        +formatCompanionDeprecation(companionDeprecation, isAlsoCalledOnInstance)
        +formatContextParameters(function.contextParameters, contextPackage, typeParameters)
        +formatVisibility(function.visibility)

        val override = override(owner, declaration, jvmDescriptor)
        +formatModalityModifiers(
            function.modality, override != null || isJvmOnlyOverride, inInterface, isOwnerOpenForOverrides, isInheritedByFinalClass
        )

        // Function-specific modifiers
        if (function.isTailrec) +"tailrec "
        if (function.isSuspend) +"suspend "
        if (function.isInline) +"inline "
        if (function.isInfix) +"infix "
        if (function.isOperator) +"operator "

        +"fun "
        formatTypeParameters(declaredTypeParameters, typeParameters, contextPackage).let {
            if (it.isNotEmpty()) +"$it "
        }

        // the receiver of an extension function, unless listed in the body of its receiver class
        function.receiverParameterType?.takeIf { bodyReceiver == null }?.let { receiverType ->
            +formatReceiverType(receiverType, contextPackage, typeParameters)
            +"."
        }

        +escapeIdentifier(function.name)
        +"("

        // an override cannot declare a default value, but inherits the one of the overridden function
        val inheritedDefaultValues = override?.inheritedDefaultValues.orEmpty()
        +(function.valueParameters.withIndex().joinToString(", ") { (index, param) ->
            formatValueParameter(
                param, contextPackage, typeParameters, hasDefaultValue = index in inheritedDefaultValues
            )
        })

        +")"

        function.returnType.let { returnType ->
            val typeName = formatType(returnType, contextPackage, typeParameters)
            if (typeName != "Unit") {
                +": "
                +typeName
            }
        }

        +formatWhereClause(declaredTypeParameters, typeParameters, contextPackage)
    }

    /**
     * Format a function or constructor value parameter, like `vararg elements: T` or `limit: Int = …`.
     *
     * @param modifiers the modifiers of a constructor parameter declaring a property, like `protected open `,
     *   preceding `vararg` in the order of the Kotlin coding conventions.
     * @param prefix the `val ` or `var ` of a constructor parameter declaring a property.
     * @param hasDefaultValue whether the parameter has a default value it does not declare,
     *   like the one inherited by an override.
     * @param defaultValue the rendering of the declared default value,
     *   which is otherwise not present in the Kotlin metadata, and therefore rendered as a marker.
     */
    private fun formatValueParameter(
        parameter: KmValueParameter,
        contextPackage: String? = null,
        typeParameters: TypeParameterScope = TypeParameterScope.EMPTY,
        prefix: String = "",
        modifiers: String = "",
        hasDefaultValue: Boolean = false,
        defaultValue: () -> String? = { null }
    ): String = buildString {
        +modifiers
        if (parameter.varargElementType != null) +"vararg "
        +prefix
        +escapeIdentifier(parameter.name)
        +": "
        // a vararg parameter is typed as an array, like Array<out T>, while its element type is rendered
        +formatType(parameter.varargElementType ?: parameter.type, contextPackage, typeParameters)
        if (parameter.declaresDefaultValue || hasDefaultValue) {
            +" = "
            +(defaultValue() ?: DEFAULT_VALUE_MARKER)
        }
    }

    /**
     * Format the context parameters of a function or property with [com.xemantic.kotlin.introspection.formatContextParameters].
     */
    private fun formatContextParameters(
        parameters: List<KmValueParameter>,
        contextPackage: String?,
        typeParameters: TypeParameterScope
    ): String = formatContextParameters(parameters.map { it.name to formatType(it.type, contextPackage, typeParameters) })

    /**
     * Format a [property] of the API in the [scope], see [formatProperty],
     * with the deprecation of its [declaration], see [formatProperty], told once by the [deprecations],
     * also deciding whether its setter is part of the API.
     */
    private fun formatApiProperty(
        property: KmProperty,
        scope: DeclarationScope,
        deprecations: Deprecations?,
        inInterface: Boolean = false,
        owner: OverrideResolver.Owner? = null,
        contextPackage: String? = null,
        classTypeParameters: TypeParameterScope = TypeParameterScope.EMPTY,
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner?.kmClass),
        isInheritedByFinalClass: Boolean = false,
        declaration: KmProperty = property
    ): List<String> {
        val deprecation = deprecations?.deprecation(declaration)
        return formatProperty(
            property, property.apiSetterVisibility(scope, deprecation), inInterface, owner, contextPackage, classTypeParameters,
            deprecation = deprecation, isOwnerOpenForOverrides = isOwnerOpenForOverrides,
            isInheritedByFinalClass = isInheritedByFinalClass, declaration = declaration
        )
    }

    /**
     * Format a member, top-level or extension property, on its lines: the declaration, followed by the line
     * of its setter, like `    protected set`, when its visibility differs from the one of the property.
     *
     * @param setterVisibility the visibility of the setter, when the property can be assigned from the user code,
     *   see [apiSetterVisibility], otherwise the property is rendered as a `val`,
     *   unless it overrides a `var`, see [propertyDeclaration].
     * @param owner the class declaring the [property], to tell whether it is an override.
     * @param classTypeParameters the type parameters in scope of the class, like for [formatFunction].
     * @param bodyReceiver the receiver class of an extension listed in its body, like for [formatFunction].
     * @param deprecation the deprecation of the [property], see [formatDeprecation].
     * @param companionDeprecation the deprecation of the companion object the [property] extends, like for [formatFunction].
     * @param isAlsoCalledOnInstance whether the [property] is also called on an instance of the class, like for [formatFunction].
     * @param classDeprecations the deprecations of the classes the name of the class goes through, like for [formatFunction].
     * @param isOwnerOpenForOverrides whether the user code might override the members of the [owner], like for [formatFunction].
     * @param declaration the [property] as the [owner] declares it, like for [formatFunction].
     */
    @OptIn(ExperimentalContextParameters::class)
    private fun formatProperty(
        property: KmProperty,
        setterVisibility: Visibility?,
        inInterface: Boolean = false,
        owner: OverrideResolver.Owner? = null,
        contextPackage: String? = null,
        classTypeParameters: TypeParameterScope = TypeParameterScope.EMPTY,
        bodyReceiver: BodyReceiver? = null,
        deprecation: Deprecation? = null,
        companionDeprecation: Deprecation? = null,
        isAlsoCalledOnInstance: Boolean = false,
        classDeprecations: String = "",
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner?.kmClass),
        isInheritedByFinalClass: Boolean = false,
        declaration: KmProperty = property
    ): List<String> = buildList {
        val typeParameters = classTypeParameters.withDeclared(property.typeParameters)
            .withStandingFor(bodyReceiver?.typeParameters.orEmpty(), bodyReceiver?.classScope ?: TypeParameterScope.EMPTY)
        // the type parameters standing for the ones of the class are not declared
        val declaredTypeParameters = property.typeParameters.filter { it.id !in bodyReceiver?.typeParameters.orEmpty() }
        val isOverride = isOverride(owner, declaration)
        val propertyDeclaration = propertyDeclaration(property, setterVisibility, isOverride, deprecation)
        val renderedSetterVisibility = propertyDeclaration.setterVisibility
        add(buildString {
            +formatDeprecation(propertyDeclaration.deprecation)
            +classDeprecations
            +formatCompanionDeprecation(companionDeprecation, isAlsoCalledOnInstance)
            +formatContextParameters(property.contextParameters, contextPackage, typeParameters)
            +formatVisibility(property.visibility)

            +formatModalityModifiers(property.modality, isOverride, inInterface, isOwnerOpenForOverrides, isInheritedByFinalClass)
            if (property.isConst) +"const "

            +(if (renderedSetterVisibility != null) "var " else "val ")
            formatTypeParameters(declaredTypeParameters, typeParameters, contextPackage).let {
                if (it.isNotEmpty()) +"$it "
            }

            // the receiver of an extension property, unless listed in the body of its receiver class
            property.receiverParameterType?.takeIf { bodyReceiver == null }?.let { receiverType ->
                +formatReceiverType(receiverType, contextPackage, typeParameters)
                +"."
            }

            +escapeIdentifier(property.name)
            +": "
            +formatType(property.returnType, contextPackage, typeParameters)
            +formatWhereClause(declaredTypeParameters, typeParameters, contextPackage)
        })
        // a setter narrower than the property, like a protected one of a public property of an open class
        if (renderedSetterVisibility != null && renderedSetterVisibility != property.visibility) {
            add("    ${formatVisibility(renderedSetterVisibility)}set")
        }
    }

    /**
     * How a property is declared, by [formatProperty], or by a parameter of a primary constructor:
     * as a `var`, with the [setterVisibility] rendered, or as a `val`, when `null`,
     * with its [deprecation] rendered, which for a `val` is the one of reading it only, see [Deprecation.ofReading].
     */
    private class PropertyDeclaration(val setterVisibility: Visibility?, val deprecation: Deprecation?) {
        val isVar: Boolean get() = setterVisibility != null
    }

    /**
     * The [PropertyDeclaration] of the [property], deprecated by the [deprecation],
     * whose setter rendered is the one of the API, with the [setterVisibility], see [apiSetterVisibility],
     * or `null` when it is rendered as a `val`, except for an override of a `var`,
     * which cannot be a `val`, so that its setter is rendered with its actual visibility, like `protected set`,
     * even when it cannot be called, like in a final class.
     * A hidden setter of an override, whose visibility does not tell it, still renders the override as a `var`.
     */
    private fun propertyDeclaration(
        property: KmProperty,
        setterVisibility: Visibility?,
        isOverride: Boolean,
        deprecation: Deprecation?
    ): PropertyDeclaration {
        val renderedSetterVisibility = setterVisibility ?: if (isOverride) property.declaredSetterVisibility else null
        return PropertyDeclaration(
            renderedSetterVisibility,
            if (renderedSetterVisibility != null) deprecation else deprecation?.ofReading()
        )
    }

    private fun List<KmTypeParameter>.namesById(): Map<Int, String> = associate { it.id to it.name }

    /**
     * The type parameters which the [kmClass] can refer to, see [TypeParameterScope],
     * including the ones of its outer classes when it is an inner class, see [outerTypeParametersOf],
     * which can be declared in a class other than the one listing it, like a sealed subclass,
     * the not nullable ones like the `T` of an outer class `Outer<T : Any>`, see [notNullableTypeParameters].
     * The bounds of the type parameters of an outer class which cannot be read are not known,
     * so that they are told not nullable, as their use as definitely not nullable, like `T0 & Any`, might not compile,
     * like of a `T : Any`, while just `T0` always does.
     */
    private fun typeParameterScopeOf(kmClass: KmClass, declarations: ClassDeclarations): TypeParameterScope {
        val outers = outerTypeParametersOf(kmClass, declarations)
        val unknownBounds = outers.flatMapTo(HashSet()) { outer ->
            outer.typeParameters.keys - outer.declarations.mapTo(HashSet()) { it.id }
        }
        return TypeParameterScope(
            outers.flatMap { it.typeParameters.entries }.associate { it.key to it.value } + kmClass.typeParameters.namesById(),
            notNullableTypeParameters(outers.flatMap { it.declarations } + kmClass.typeParameters, unknownBounds)
        )
    }

    private fun KmType.isNullableAny(): Boolean =
        (classifier as? KmClassifier.Class)?.name == "kotlin/Any" && isNullable

    /**
     * Format type parameter declarations, like `<K, out V : Any>`.
     * Parameters with multiple upper bounds have them listed in the [formatWhereClause] instead.
     */
    private fun formatTypeParameters(
        declarations: List<KmTypeParameter>,
        typeParameters: TypeParameterScope,
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
                +escapeIdentifier(typeParameter.name)
                typeParameter.upperBounds.singleOrNull()?.takeUnless { it.isNullableAny() }?.let {
                    +" : "
                    +formatType(it, contextPackage, typeParameters)
                }
            }
        }
    }

    private fun formatWhereClause(
        declarations: List<KmTypeParameter>,
        typeParameters: TypeParameterScope,
        contextPackage: String? = null
    ): String {
        val constraints = declarations.filter { it.upperBounds.size > 1 }.flatMap { typeParameter ->
            typeParameter.upperBounds.map {
                "${escapeIdentifier(typeParameter.name)} : ${formatType(it, contextPackage, typeParameters)}"
            }
        }
        return if (constraints.isEmpty()) "" else " where ${constraints.joinToString(", ")}"
    }

    /**
     * Format a [function] described by kotlin-reflect, see [toKmFunction], like a JVM-only member of a built-in type,
     * see [BuiltinClass], declared in a class without type parameters, which is not told to be an override.
     * It exposes the conversion, and the rendering through [formatFunction], to `ReflectedDeclarationsTest`,
     * as no built-in type has members with the parameters it exercises, like the type [formatType] does.
     */
    internal fun formatReflectedFunction(function: KFunction<*>): String =
        formatFunction(function.toKmFunction(ReflectedTypeParameters.classTypeParameters(emptyList())))

    /**
     * Format a Java [method] converted from the Java reflection, see [toKmJavaFunction],
     * like one described by kotlin-reflect, see [formatReflectedFunction].
     */
    internal fun formatJavaFunction(method: Method): String =
        formatFunction(method.toKmJavaFunction(ReflectedTypeParameters.classTypeParameters(emptyList()), isFinalClass = false))

    /**
     * Format a [property] described by kotlin-reflect, like a function, see [formatReflectedFunction].
     */
    internal fun formatReflectedProperty(property: KProperty<*>): List<String> {
        val kmProperty = property.toKmProperty(ReflectedTypeParameters.classTypeParameters(emptyList()))
        return formatProperty(kmProperty, setterVisibility = kmProperty.setter?.visibility)
    }

    internal fun formatType(
        type: KmType,
        contextPackage: String? = null,
        typeParameters: TypeParameterScope = TypeParameterScope.EMPTY
    ): String = MetadataTypeNotation(contextPackage, typeParameters).format(type)

    /**
     * Formats a receiver [type], parenthesizing a function type, see [TypeNotation.formatReceiver].
     */
    private fun formatReceiverType(
        type: KmType,
        contextPackage: String?,
        typeParameters: TypeParameterScope
    ): String = MetadataTypeNotation(contextPackage, typeParameters).formatReceiver(type)

    /**
     * The notation of the types of the Kotlin metadata.
     *
     * @param contextPackage the package of the rendered declaration, whose types are written with simple names.
     * @param typeParameters the type parameters in scope, see [TypeParameterScope].
     */
    private inner class MetadataTypeNotation(
        private val contextPackage: String?,
        private val typeParameters: TypeParameterScope
    ) : TypeNotation<KmType>() {

        override fun decodeFunctionType(type: KmType): FunctionType<KmType>? = type.decodeFunctionType()

        override fun isMarkedSuspend(type: KmType): Boolean = type.isSuspend

        override fun isMarkedNullable(type: KmType): Boolean = type.isNullable

        override fun isDefinitelyNonNull(type: KmType): Boolean {
            val classifier = type.classifier as? KmClassifier.TypeParameter ?: return false
            return type.isDefinitelyNonNull && !type.isNullable && typeParameters.isRenderedDefinitelyNotNull(classifier.id)
        }

        override fun parameterName(type: KmType): String? = type.parameterName

        override fun formatClassType(type: KmType, nullable: Boolean): String {
            val outerType = type.outerType
            val baseName = when (val classifier = type.classifier) {
                // an inner class of a generic class, like `Outer<String>.Inner`,
                // carries the type arguments of the outer class in the outer type
                is KmClassifier.Class if outerType != null -> {
                    "${format(outerType)}.${escapeIdentifier(simpleClassName(classifier.name))}"
                }
                is KmClassifier.Class -> {
                    val fqn = classifier.name.replace("/", ".")
                    // the package is separated with '/', and nested classes with '.'
                    val typePackage = classifier.name.substringBeforeLast("/", "").replace("/", ".")
                    // Use simple name for:
                    // 1. Well-known stdlib types (kotlin.*, java.*)
                    // 2. Types from the same package as the context
                    escapeQualifiedName(
                        if (isWellKnownType(fqn) || typePackage == contextPackage) {
                            classifier.name.substringAfterLast("/")
                        } else {
                            fqn
                        }
                    )
                }
                is KmClassifier.TypeAlias -> escapeQualifiedName(classifier.name.substringAfterLast("/"))
                // a type parameter of an outer declaration might be unknown in this context
                is KmClassifier.TypeParameter -> typeParameters.names[classifier.id]?.let(::escapeIdentifier) ?: "T"
            }

            val typeArgs = type.arguments.map { projection ->
                val projType = projection.type ?: return@map "*"
                when (projection.variance) {
                    KmVariance.INVARIANT, null -> format(projType)
                    KmVariance.IN -> "in ${format(projType)}"
                    KmVariance.OUT -> "out ${format(projType)}"
                }
            }

            val nullableMarker = if (nullable) "?" else ""
            return if (typeArgs.isEmpty()) {
                baseName + nullableMarker
            } else {
                "$baseName<${typeArgs.joinToString(", ")}>$nullableMarker"
            }
        }

    }

    /**
     * The simple name of a metadata class name, like `Nested` of `pkg/Parent.Nested`,
     * where the nested classes are separated with `.`, as `$` can be part of a class name.
     */
    private fun simpleClassName(name: String): String =
        name.substringAfterLast("/").substringAfterLast(".")

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

    /**
     * Format the modality and `override` modifiers of a member, like `abstract override `,
     * in the order of the Kotlin coding conventions.
     *
     * An override is implicitly open, therefore its `final` is rendered, and so is the `open` of any other member,
     * only when the user code can subclass the owner class, see [ApiClass.isOpen], like its protected members,
     * as all its members are final to the user code otherwise,
     * like the ones of a sealed class without an open subclass, or of an enum class, even when its entries override them.
     * The members of an interface are implicitly abstract, so the ones with a default body are rendered as open,
     * also when overriding, like `open override fun f()`, telling it apart from an abstract `override fun f()`,
     * which might make abstract again the `f()` with a default body of a superinterface,
     * and the ones a final method of a Java class implements as `final`, like `final fun f()`, see `InheritedCandidate.renderedMember`.
     * A member inherited by a class which cannot be subclassed, when [isInheritedByFinalClass], see `inheritedMembers`,
     * is not rendered as open, as it cannot be overridden there.
     */
    private fun formatModalityModifiers(
        modality: Modality,
        isOverride: Boolean,
        inInterface: Boolean,
        isOwnerOpenForOverrides: Boolean,
        isInheritedByFinalClass: Boolean = false
    ): String = buildString {
        when (modality) {
            Modality.ABSTRACT -> if (!inInterface) +"abstract "
            Modality.OPEN -> if (((!isOverride && isOwnerOpenForOverrides) || inInterface) && !isInheritedByFinalClass) +"open "
            // a member of an interface is final only when inherited from a Java class implementing it, see InheritedCandidate
            Modality.FINAL -> if ((isOverride || inInterface) && isOwnerOpenForOverrides) +"final "
            Modality.SEALED -> {}
        }
        if (isOverride) +"override "
    }

    /**
     * Whether this property might be declared by the constructor [parameter], like `class Foo(val x: Int)`.
     *
     * The metadata does not tell where a property is declared, so a property of the same name is excluded
     * when it cannot be declared in the constructor: it is not declared in the class, but delegated
     * to the implementation of an interface, it has a receiver (and so the type parameters, which only a receiver
     * can use), an accessor other than the default one (with a body, also the only way of declaring a property
     * without a backing field, or only changing the visibility, like `private set`, and also of a delegated property),
     * a `lateinit` modifier, a constant initializer (also of every `const`), no implementation,
     * or a type other than the parameter type, like `class Foo(x: Int) { val x: Long = x.toLong() }`.
     * What remains, like `class Foo(x: Int) { val x: Int = x }`, cannot be distinguished from
     * a constructor property in the metadata, and has the same API, unlike `class Foo(x: Int) { val x: Int = x * 2 }`,
     * which is still rendered as a constructor property.
     * The order of the properties in the metadata would not tell them apart either: while Kotlin 2 keeps
     * the declaration order, the constructor properties first, Kotlin 1 (metadata version 1.x) sorted them by name.
     */
    private fun KmProperty.isDeclaredBy(parameter: KmValueParameter): Boolean =
        name == parameter.name &&
            kind == MemberKind.DECLARATION &&
            modality != Modality.ABSTRACT &&
            receiverParameterType == null &&
            !isLateinit &&
            !hasConstant &&
            !getter.isNotDefault &&
            setter?.isNotDefault != true &&
            returnType == parameter.type

    // kotlinc writes no FAKE_OVERRIDE members to the metadata, neither of a class file, nor of .kotlin_builtins,
    // and a member implemented by delegation is a DELEGATION one, so each override is told by matching its supertypes

    private fun isOverride(owner: OverrideResolver.Owner?, function: KmFunction): Boolean =
        owner != null && overrideResolver.isOverride(owner, function)

    /**
     * The override of a supertype function by the [function], or `null` if it is not an override.
     */
    private fun override(
        owner: OverrideResolver.Owner?,
        function: KmFunction,
        jvmDescriptor: String? = null
    ): OverrideResolver.FunctionOverride? = owner?.let { overrideResolver.override(it, function, jvmDescriptor) }

    private fun isOverride(owner: OverrideResolver.Owner?, property: KmProperty): Boolean =
        owner != null && overrideResolver.isOverride(owner, property)

    /**
     * Scans the classpath of the [classLoader], indexing the extensions and the classes found there,
     * while skipping the nested class files which cannot contain an indexed Kotlin class, see [NestedClassFiles],
     * unless [skipNestedClassFiles] is `false`, which only the tests do, telling that the index stays the same,
     * like they ask to [collectSkippedClassFiles].
     */
    internal fun scanClasspath(
        skipNestedClassFiles: Boolean = true,
        collectSkippedClassFiles: Boolean = false
    ): ClasspathScan {
        val scanner = ClasspathScanner(skipNestedClassFiles, collectSkippedClassFiles)
        val visitedRoots = mutableSetOf<File>()
        val roots = ArrayDeque(classpathRoots())

        while (roots.isNotEmpty()) {
            val root = roots.removeFirst().absoluteFile.normalize()
            if (!visitedRoots.add(root) || !root.exists()) continue

            if (root.isDirectory) {
                scanDirectory(root, scanner)
            } else {
                scanJarFile(root, roots, scanner)
            }
        }
        val funcIndex = scanner.funcIndex
        val propIndex = scanner.propIndex
        val classes = scanner.classes

        // the same classes of the API as the ones resolved, see the apiClasses of the introspector,
        // where every class file of the classpath is read, or skipped, see ClasspathScanner.scan, before deciding it,
        // while an enclosing class might still be served by the class loader from elsewhere, read then on its own
        val outsideClasses = HashMap<String, OutsideClass>()
        // read once by its name, kept for the scan also when it cannot be read, or is absent
        fun outsideClass(name: String): OutsideClass = outsideClasses.getOrPut(name) { readOutsideClass(binaryNameOf(name)) }
        val apiClasses = ApiClasses(
            scopeOf = { name ->
                classes[name]?.scope ?: if (scanner.isScanned(binaryNameOf(name))) null else outsideClass(name).scope
            },
            isAbsent = { name -> !scanner.isScanned(binaryNameOf(name)) && outsideClass(name).isAbsent }
        )
        val classesOfApi = classes.values.mapNotNull { indexedClass ->
            apiClasses.apiClass(indexedClass.locatedName)?.let { indexedClass to it }
        }

        // the member extensions of public objects, which can be imported like the top-level ones,
        // not the ones of protected objects, as they would be shown to all the users of the receiver type
        classesOfApi
            .filter { (_, apiClass) -> apiClass.scopes.all { it.visibility == Visibility.PUBLIC } }
            .forEach { (indexedClass, _) ->
                indexExtensions(indexedClass.extensions, indexedClass.extensionProperties, funcIndex, propIndex)
            }

        // Index the classes by their simple names (lowercase for case-insensitive search),
        // except for the companion objects, already rendered within their outer classes
        val clsIndex = mutableMapOf<String, MutableList<String>>()
        classesOfApi
            .map { (indexedClass, _) -> indexedClass }
            .filter { !it.scope.isCompanion }
            .forEach { indexedClass ->
                // the Kotlin name, like com.example.Outer.Nested, which can be resolved, in the package of its binary name,
                // which might not be the one of its metadata name, like when relocated by shading, see locatedNameOf
                val kotlinName = indexedClass.locatedName.replace('/', '.')
                val simpleName = kotlinName.substringAfterLast('.').lowercase()
                clsIndex.getOrPut(simpleName) { mutableListOf() }.add(kotlinName)
            }
        // and the typealiases, found like the classes
        scanner.typeAliases.keys.forEach { name ->
            clsIndex.getOrPut(name.substringAfterLast('.').lowercase()) { mutableListOf() }.add(name)
        }

        return ClasspathScan(
            extensionIndex = funcIndex,
            extensionPropertyIndex = propIndex,
            classIndex = clsIndex,
            typeAliasIndex = scanner.typeAliases,
            classes = classes.values,
            readClassFileCount = scanner.readClassFileCount,
            skippedClassFileCount = scanner.skippedClassFileCount,
            skippedClassFiles = scanner.skippedClassFiles
        )
    }

    /**
     * Scans the class files of the directory [root], one directory at a time, listing each of them once,
     * as the class files of a nested class are in the directory of its outer class, the one of their package.
     * The `META-INF` directory is skipped, as the class versions of a multi-release root are loaded only from a jar,
     * see [scanJarFile], and a directory reached again within itself, through a symbolic link cycle, like `pkg/loop -> ..`,
     * is not listed again, while the one reached also through another symbolic link is, like by the class loader,
     * as the class files are scanned by the names they are loaded by, see [ClasspathScanner.scan].
     */
    private fun scanDirectory(root: File, scanner: ClasspathScanner) {
        val rootDirectory = ScannedDirectory.of(root, enclosing = null) ?: return
        val directories = ArrayDeque(listOf(rootDirectory))
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            val (subdirectories, files) = directory.file.listFiles().orEmpty().partition { it.isDirectory }
            subdirectories
                .filter { directory !== rootDirectory || it.name != "META-INF" }
                .mapNotNullTo(directories) { ScannedDirectory.of(it, enclosing = directory) }
            scanner.scan(
                files.filter { it.isFile && isIndexedClassFile(it.name) }.map { file ->
                    ClassFileEntry(file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class"), file::readBytes)
                }
            )
        }
    }

    /**
     * A directory [file] of a directory root being scanned, see [scanDirectory], by its [realPath],
     * within its [enclosing] one, as it is reached.
     */
    private class ScannedDirectory(val file: File, val realPath: Path, val enclosing: ScannedDirectory?) {

        companion object {

            /**
             * The directory [file] within its [enclosing] one, or `null` if it is one of the directories enclosing it,
             * reached through a symbolic link cycle, or if its real path cannot be told,
             * see [Path.toRealPath], resolving also the junctions of Windows.
             */
            fun of(file: File, enclosing: ScannedDirectory?): ScannedDirectory? {
                val realPath = try {
                    file.toPath().toRealPath()
                } catch (_: IOException) {
                    return null
                }
                val isCycle = generateSequence(enclosing) { it.enclosing }.any { it.realPath == realPath }
                return if (isCycle) null else ScannedDirectory(file, realPath, enclosing)
            }

        }

    }

    /**
     * What a [scanClasspath] found, in the classpath scan order.
     *
     * @property classes the Kotlin classes found, whether part of the API or not,
     *   read only by the tests, telling that skipping the nested class files does not change what is found.
     * @property readClassFileCount the number of the class files read, or failed to be read,
     *   including the ones which cannot be parsed, but not the ones shadowed by an earlier root, see [ClasspathScanner.scan].
     * @property skippedClassFileCount the number of the nested class files skipped without reading them,
     *   see [NestedClassFiles], not shadowed by an earlier root either.
     * @property skippedClassFiles their internal names, like `com/example/Outer${'$'}Nested`,
     *   if collected, see [scanClasspath].
     */
    internal class ClasspathScan(
        val extensionIndex: Map<String, List<IndexedExtension>>,
        val extensionPropertyIndex: Map<String, List<IndexedExtensionProperty>>,
        val classIndex: Map<String, List<String>>,
        val typeAliasIndex: Map<String, String>,
        val classes: Collection<IndexedClass>,
        val readClassFileCount: Int,
        val skippedClassFileCount: Int,
        val skippedClassFiles: List<String>
    )

    /**
     * A class file of a classpath root, by the internal name of its class, like `com/example/Outer${'$'}Nested`,
     * telling how to [read] it.
     */
    private class ClassFileEntry(val name: String, val read: () -> ByteArray)

    /**
     * The state of a [scanClasspath], indexing the class files of the classpath roots, one group of them at a time.
     */
    private inner class ClasspathScanner(
        private val skipNestedClassFiles: Boolean,
        private val collectSkippedClassFiles: Boolean
    ) {

        val funcIndex = mutableMapOf<String, MutableList<IndexedExtension>>()

        val propIndex = mutableMapOf<String, MutableList<IndexedExtensionProperty>>()

        val classes = mutableMapOf<String, IndexedClass>()

        // the public typealiases not deprecated as hidden, see typeAliasIndex, the first one of a name winning, like a class
        val typeAliases = mutableMapOf<String, String>()

        // The first occurrence of a class on the classpath wins, as with class loading
        private val indexedClasses = mutableSetOf<String>()

        /**
         * Whether the class file of the class of this JVM [binaryName] was scanned, read or skipped.
         */
        fun isScanned(binaryName: String): Boolean = binaryName in indexedClasses

        var readClassFileCount = 0
            private set

        var skippedClassFileCount = 0
            private set

        val skippedClassFiles = mutableListOf<String>()

        /**
         * Indexes a group of class files, like the ones of a jar or of a directory, containing the class files
         * of the nested classes together with the ones of their outer classes, which are read first,
         * as the binary name of a nested class usually extends the one of its outer class, like `Outer${'$'}Nested`,
         * so that the nested class files which cannot contain an indexed Kotlin class are recognized before reading them.
         */
        fun scan(classFiles: List<ClassFileEntry>) {
            // no nesting is read, when not skipping, see indexClass
            val nestedClassFiles = NestedClassFiles(
                if (skipNestedClassFiles) classFiles.mapTo(HashSet()) { it.name } else emptySet()
            )
            classFiles.sortedBy { nestingDepth(it.name) }.forEach { classFile ->
                // the name of the path being also the one a class file read declares for it
                val binaryName = classFile.name.replace('/', '.')
                if (skipNestedClassFiles && nestedClassFiles.isNonKotlinNestedClass(classFile.name)) {
                    // still shadowing, like an indexed one, the class of the same name in the next roots,
                    // unless shadowed itself by an earlier root
                    if (indexedClasses.add(binaryName)) {
                        skippedClassFileCount++
                        if (collectSkippedClassFiles) skippedClassFiles += classFile.name
                    }
                } else if (!isScanned(binaryName)) {
                    readClassFileCount++
                    val bytes = try {
                        classFile.read()
                    } catch (_: Exception) {
                        // like an I/O error, shadowing the class of the same name in the next roots,
                        // like a class file which cannot be parsed, see indexClass, as the class loader does not skip it either
                        indexedClasses.add(binaryName)
                        return@forEach
                    }
                    indexClass(classFile.name, bytes, nestedClassFiles)
                }
            }
        }

        /**
         * Indexes the class read from the [bytes] of the class file of the [path], unless a class of the same name
         * is already indexed, telling the [nestedClassFiles] about its nested classes, when skipping them.
         * A class file which cannot be parsed still shadows the class of the same name in the next roots,
         * and is not absent from the classpath, like when resolving its nested classes, see [isAbsent].
         */
        private fun indexClass(path: String, bytes: ByteArray, nestedClassFiles: NestedClassFiles) {
            val reader = try {
                ClassReader(bytes)
            } catch (_: Exception) {
                indexedClasses.add(path.replace('/', '.'))
                return
            }
            val className = reader.className.replace('/', '.')
            if (!indexedClasses.add(className)) return

            val classFile = try {
                readClassFile(reader, readsNesting = skipNestedClassFiles)
            } catch (_: Exception) {
                return
            }
            val metadata = try {
                classFile.metadata?.let { KotlinClassMetadata.readLenient(it) }
            } catch (_: Exception) {
                // nothing is told about the nested classes of a class with unreadable metadata
                return
            }
            nestedClassFiles.add(reader.className, classFile, (metadata as? KotlinClassMetadata.Class)?.kmClass)
            if (metadata == null) return

            if (metadata is KotlinClassMetadata.Class) {
                val kmClass = metadata.kmClass
                // local and anonymous classes have names starting with '.'
                if (kmClass.name.startsWith('.')) return
                // the member extensions of classes can be called only in the scope of their dispatch receiver,
                // while the ones of objects can be imported, which is decided once all the enclosing classes are known
                val isObject = kmClass.kind == ClassKind.OBJECT || kmClass.kind == ClassKind.COMPANION_OBJECT
                // by the located name, so that a class relocated by shading is indexed next to the original one
                val locatedName = locatedNameOf(className, kmClass.name)
                classes.getOrPut(locatedName) {
                    // the object to import them from, by the name it is found by, see locatedNameOf
                    val source = locatedName.replace('/', '.')
                    IndexedClass(
                        name = kmClass.name,
                        binaryName = className,
                        scope = classScope(kmClass, isUsableClass(kmClass, classFile), classFile) { locatedNameOf(className, it) },
                        extensions = if (isObject) {
                            apiExtensions(kmClass.functions, classFile, className, source, isObjectMember = true, kmClass.moduleName)
                        } else emptyList(),
                        extensionProperties = if (isObject) {
                            apiExtensionProperties(kmClass.properties, classFile, className, source, isObjectMember = true, kmClass.moduleName)
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
            val packageName = kotlinPackageOf(className, classFile.metadata?.packageName.orEmpty())
            kmPackage.typeAliases.forEach { typeAlias ->
                if (typeAlias.visibility == Visibility.PUBLIC && typeAliasDeprecation(typeAlias)?.isHidden != true) {
                    typeAliases.putIfAbsent(if (packageName.isEmpty()) typeAlias.name else "$packageName.${typeAlias.name}", className)
                }
            }
            val source = packageSourceOf(className, classFile.metadata?.packageName.orEmpty())
            indexExtensions(
                apiExtensions(kmPackage.functions, classFile, className, source, isObjectMember = false, kmPackage.moduleName),
                apiExtensionProperties(kmPackage.properties, classFile, className, source, isObjectMember = false, kmPackage.moduleName),
                funcIndex,
                propIndex
            )
        }

    }

    /**
     * A class served by the [classLoader] outside the classpath roots, see [scanClasspath],
     * of the [scope], or `null` if it cannot be read, see [ClassScope],
     * and whether its class file [isAbsent], neither served, nor failing to be read with an `IOException`, nor there at all,
     * like for the classes resolved, see [isAbsent].
     */
    private class OutsideClass(val scope: ClassScope?, val isAbsent: Boolean)

    /**
     * Reads the class of this JVM [binaryName] from its class file served by the [classLoader], see [OutsideClass],
     * once more right away after a failure which might be transient, an `IOException`,
     * as a resolve does on its next attempt, see [readClassBytesOutcome], while the scan decides it once.
     */
    private fun readOutsideClass(binaryName: String): OutsideClass {
        var failure: Exception? = null
        val bytes = classLoader.readClassBytes(binaryName) { failure = it }
            ?: if (failure is IOException) {
                failure = null
                classLoader.readClassBytes(binaryName) { failure = it }
            } else null
        val isAbsent = bytes == null && failure !is IOException && !classLoader.hasClassFile(binaryName)
        val classFile = readClassFile(binaryName, bytes) ?: return OutsideClass(scope = null, isAbsent)
        val kmClass = (classFile.metadata?.let(::parseMetadata) as? KotlinClassMetadata.Class)?.kmClass
            ?: return OutsideClass(scope = null, isAbsent)
        val scope = classScope(kmClass, isUsableClass(kmClass, classFile), classFile) { locatedNameOf(binaryName, it) }
        return OutsideClass(scope, isAbsent)
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
     * to avoid reading them, while the other ones are read, as `$` can be part of a class name, like in `Outer$$Named`.
     */
    private fun isIndexedClassFile(fileName: String): Boolean =
        fileName.endsWith(".class") && fileName != "module-info.class" &&
            fileName.removeSuffix(".class").split('$').drop(1).none { it.firstOrNull()?.isDigit() == true }

    /**
     * The number of `$` in the simple name of the class with the internal name [path], see [ClasspathScanner.scan].
     */
    private fun nestingDepth(path: String): Int = path.substringAfterLast('/').count { it == '$' }

    /**
     * Scans the class files of the [jarFile], the ones served by a class loader for the running JDK,
     * which, for a multi-release jar, are the latest class versions not newer than it, see [JarFile.versionedStream],
     * named by their base entries, while the other entries of the `META-INF` directory are skipped.
     */
    private fun scanJarFile(jarFile: File, roots: ArrayDeque<File>, scanner: ClasspathScanner) {
        try {
            // opened like by a URLClassLoader
            JarFile(jarFile, true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { jar ->
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
                scanner.scan(
                    jar.versionedStream().iterator().asSequence()
                        // the name of a class version is the one of its base entry, the real one being under META-INF
                        .filter { !it.name.startsWith("META-INF/") }
                        .filter { isIndexedClassFile(it.name.substringAfterLast('/')) }
                        .map { entry ->
                            ClassFileEntry(entry.name.removeSuffix(".class")) {
                                jar.getInputStream(entry).use { it.readBytes() }
                            }
                        }
                        .toList()
                )
            }
        } catch (_: Exception) {
            // Skip unreadable JARs
        }
    }

    /*
     * The extensions of the API, declared in the class file of the className, to be indexed.
     * Once imported, even the members of an object are shown to all the users of the receiver type,
     * like the top-level ones, so only the public ones are, and only the ones which are not hidden.
     */

    private fun apiExtensions(
        functions: List<KmFunction>,
        classFile: ClassFile,
        className: String,
        source: String,
        isObjectMember: Boolean,
        moduleName: String?
    ): List<IndexedExtension> {
        val isBuiltinExtension = isBuiltinExtensionSource(className, source, moduleName)
        return functions
            .filter { it.receiverParameterType != null }
            .inApi(TopLevel, classFile)
            .map {
                IndexedExtension(
                    it, className, source, isObjectMember, classFile.deprecation(it), classFile.hidesMembers(it),
                    isBuiltinExtension = isBuiltinExtension
                )
            }
    }

    private fun apiExtensionProperties(
        properties: List<KmProperty>,
        classFile: ClassFile,
        className: String,
        source: String,
        isObjectMember: Boolean,
        moduleName: String?
    ): List<IndexedExtensionProperty> {
        val isBuiltinExtension = isBuiltinExtensionSource(className, source, moduleName)
        return properties
            .filter { it.receiverParameterType != null }
            .inApi(TopLevel, classFile)
            .map {
                val deprecation = classFile.deprecation(it)
                IndexedExtensionProperty(
                    it, className, source, isObjectMember,
                    setterVisibility = it.apiSetterVisibility(TopLevel, deprecation),
                    deprecation = deprecation,
                    isBuiltinExtension = isBuiltinExtension
                )
            }
    }

    private fun indexExtensions(
        functions: List<IndexedExtension>,
        properties: List<IndexedExtensionProperty>,
        funcIndex: MutableMap<String, MutableList<IndexedExtension>>,
        propIndex: MutableMap<String, MutableList<IndexedExtensionProperty>>
    ) {
        // an extension on a type parameter with multiple bounds is indexed under each of them, see receiverIndexKeys
        for (extension in functions) {
            val receiverType = extension.function.receiverParameterType ?: continue
            val receiverNames = receiverIndexKeys(receiverType, extension.function.typeParameters)
            val bounded = extension.copy(receiverBounds = receiverBoundsOf(receiverNames))
            receiverNames.forEach { funcIndex.getOrPut(it) { mutableListOf() }.add(bounded) }
        }
        for (extension in properties) {
            val receiverType = extension.property.receiverParameterType ?: continue
            val receiverNames = receiverIndexKeys(receiverType, extension.property.typeParameters)
            val bounded = extension.copy(receiverBounds = receiverBoundsOf(receiverNames))
            receiverNames.forEach { propIndex.getOrPut(it) { mutableListOf() }.add(bounded) }
        }
    }

    /**
     * Whether the class, declared in the metadata, can be used in the source code, regardless of its visibility,
     * which is not the case for the `$serializer` generated by kotlinx.serialization,
     * deprecated with the `HIDDEN` level, or recognized by its name and supertype, if generated by an older plugin,
     * neither for a class marked as `ACC_SYNTHETIC` by a bytecode tool.
     * The classes nested in an unusable class are not part of the API either, see [ApiClasses].
     */
    private fun isUsableClass(kmClass: KmClass, classFile: ClassFile): Boolean =
        !classFile.isSynthetic &&
            !classFile.isHiddenDeprecated &&
            !isGeneratedSerializer(kmClass)

    private fun isGeneratedSerializer(kmClass: KmClass): Boolean =
        // nested in the serializable class, unlike a hand-written serializer, which can be named anyhow
        kmClass.name.substringAfterLast('/').endsWith(".\$serializer") &&
            kmClass.supertypes.any { (it.classifier as? KmClassifier.Class)?.name == GENERATED_SERIALIZER }

    /**
     * Visits an annotation of a class or a method, collecting the attributes of a `@kotlin.Deprecated`,
     * or a `@kotlin.DeprecatedSinceKotlin`, one into the [annotations] of the declaration,
     * telling its [deprecation][deprecationOf] once all of them are visited,
     * or returns `null` for any other annotation.
     * The empty versions, the defaults of `@DeprecatedSinceKotlin`, are skipped like the absent ones.
     */
    private fun deprecationVisitor(descriptor: String, annotations: () -> DeprecationAnnotations): AnnotationVisitor? =
        when (descriptor) {
            DEPRECATED_DESCRIPTOR -> object : AnnotationVisitor(Opcodes.ASM9) {
                private val target = annotations().also { it.isDeprecated = true }

                override fun visit(name: String?, value: Any?) {
                    if (name == "message") target.message = value as? String
                }

                override fun visitEnum(name: String?, descriptor: String, value: String) {
                    if (name == "level") target.level = value
                }

                override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor? =
                    if (name == "replaceWith") {
                        object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(name: String?, value: Any?) {
                                if (name == "expression") target.replaceWith = value as? String
                            }
                        }
                    } else {
                        null
                    }
            }
            DEPRECATED_SINCE_KOTLIN_DESCRIPTOR -> object : AnnotationVisitor(Opcodes.ASM9) {
                private val target = annotations().also { it.isDeprecatedSinceKotlin = true }

                override fun visit(name: String?, value: Any?) {
                    val version = (value as? String)?.takeIf { it.isNotEmpty() }
                    when (name) {
                        "warningSince" -> target.warningSince = version
                        "errorSince" -> target.errorSince = version
                        "hiddenSince" -> target.hiddenSince = version
                    }
                }
            }
            else -> null
        }

    /**
     * Whether a declaration annotated with `@DeprecatedSinceKotlin`, like with `hiddenSince = version`,
     * is deprecated with that level, see [deprecationOf].
     * The compiler applies it to the code compiled with the API version of at least [version],
     * which defaults to the version of the compiler, and should not exceed the one of the standard library,
     * so the [stdlibVersion] of the classpath is what the up-to-date user code is assumed to be compiled with.
     * A malformed version, rejected by the compiler, does not deprecate anything.
     */
    private fun isSince(version: String): Boolean =
        parseKotlinVersion(version)?.let { it <= stdlibVersion } == true

    /**
     * Reads the `@Metadata` annotation, and the flags telling whether the class is usable in the source code,
     * and the deprecations of the class and its methods, from the class bytes, without loading the class,
     * as well as its [nesting][ClassFile.nesting], if [readsNesting].
     */
    private fun readClassFile(reader: ClassReader, readsNesting: Boolean = false): ClassFile {
        var metadata: Metadata? = null
        var classAnnotations: DeprecationAnnotations? = null
        val memberClasses = if (readsNesting) mutableListOf<MemberClass>() else null
        val nestMembers = if (readsNesting) mutableListOf<String>() else null
        // allocated only when needed, as most classes have no deprecated methods, and only interfaces need the others
        var deprecatedMethods: MutableMap<String, Deprecation>? = null
        var deprecatedEnumEntries: MutableMap<String, Deprecation>? = null
        val annotationsMethods = LazySet()
        val membersHidingMethods = LazySet()
        val isInterface = reader.access and Opcodes.ACC_INTERFACE != 0
        var classSignature: String? = null
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                classSignature = signature
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                if (descriptor == METADATA_DESCRIPTOR) {
                    MetadataVisitor { metadata = it }
                } else {
                    deprecationVisitor(descriptor) { classAnnotations ?: DeprecationAnnotations().also { classAnnotations = it } }
                }

            // only the fields of the enum entries, see ClassFile.deprecatedEnumEntries
            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?
            ): FieldVisitor? = if (metadata == null || access and Opcodes.ACC_ENUM == 0) null else object : FieldVisitor(Opcodes.ASM9) {
                private var fieldAnnotations: DeprecationAnnotations? = null

                override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor? =
                    deprecationVisitor(annotationDescriptor) {
                        fieldAnnotations ?: DeprecationAnnotations().also { fieldAnnotations = it }
                    }

                override fun visitEnd() {
                    val deprecation = fieldAnnotations?.deprecationOf(::isSince) ?: return
                    val entries = deprecatedEnumEntries ?: mutableMapOf<String, Deprecation>().also { deprecatedEnumEntries = it }
                    entries[name] = deprecation
                }
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? = if (metadata == null) {
                // the declarations of a class without the metadata, visited before the methods, are not rendered
                null
            } else {
                if (isInterface && name.endsWith(ANNOTATIONS_METHOD_SUFFIX)) annotationsMethods += name + descriptor
                object : MethodVisitor(Opcodes.ASM9) {
                    private var methodAnnotations: DeprecationAnnotations? = null

                    override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor? {
                        if (annotationDescriptor == HIDES_MEMBERS_DESCRIPTOR) membersHidingMethods += name + descriptor
                        return deprecationVisitor(annotationDescriptor) {
                            methodAnnotations ?: DeprecationAnnotations().also { methodAnnotations = it }
                        }
                    }

                    override fun visitEnd() {
                        val deprecation = methodAnnotations?.deprecationOf(::isSince) ?: return
                        val methods = deprecatedMethods ?: mutableMapOf<String, Deprecation>().also { deprecatedMethods = it }
                        methods[name + descriptor] = deprecation
                    }
                }
            }

            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                // the anonymous and local classes are not members
                if (memberClasses != null && outerName != null && innerName != null) {
                    memberClasses += MemberClass(name, outerName, innerName)
                }
            }

            override fun visitNestMember(nestMember: String) {
                nestMembers?.add(nestMember)
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return ClassFile(
            metadata = metadata,
            isSynthetic = reader.access and Opcodes.ACC_SYNTHETIC != 0,
            isInterface = isInterface,
            classDeprecation = classAnnotations?.deprecationOf(::isSince),
            nesting = memberClasses?.let { members ->
                Nesting(memberClasses = members, nestMembers = nestMembers.orEmpty())
            },
            deprecatedMethods = deprecatedMethods ?: emptyMap(),
            annotationsMethods = annotationsMethods.toSet(),
            membersHidingMethods = membersHidingMethods.toSet(),
            signature = classSignature,
            deprecatedEnumEntries = deprecatedEnumEntries ?: emptyMap()
        )
    }

    /**
     * A set of strings allocated only once the first one is added, as most of them stay empty.
     */
    private class LazySet {

        private var elements: MutableSet<String>? = null

        operator fun plusAssign(element: String) {
            (elements ?: mutableSetOf<String>().also { elements = it }) += element
        }

        fun toSet(): Set<String> = elements ?: emptySet()

    }

    /**
     * What is read from a class file, without loading the class.
     *
     * @property classDeprecation the deprecation of the class itself.
     * @property nesting what the class file tells about the classes nested in this class,
     *   read only by the classpath scan, see [NestedClassFiles].
     * @property deprecatedMethods the deprecations of the methods, by their JVM signatures,
     *   like `max(Ljava/lang/CharSequence;)Ljava/lang/Character;`, see [deprecationVisitor].
     * @property isInterface whether the JVM class is an interface, like the one of a Kotlin interface, or an annotation class.
     * @property annotationsMethods the JVM signatures of the synthetic methods for the annotations of the properties,
     *   like `getName${'$'}annotations()V`, only of an interface, as only for it these methods might be in
     *   the `DefaultImpls` class instead, see [hasAnnotationsInDefaultImpls].
     * @property membersHidingMethods the JVM signatures of the methods annotated with `@kotlin.internal.HidesMembers`,
     *   like `addSuppressed(Ljava/lang/Throwable;Ljava/lang/Throwable;)V`, see [hidesMembers].
     * @property deprecatedEnumEntries the deprecations of the entries of an enum class, by the names of their fields,
     *   which the `@Deprecated` annotation of an entry is compiled to.
     */
    private class ClassFile(
        val metadata: Metadata?,
        val isSynthetic: Boolean,
        val isInterface: Boolean = false,
        val classDeprecation: Deprecation? = null,
        val nesting: Nesting? = null,
        val deprecatedMethods: Map<String, Deprecation> = emptyMap(),
        val annotationsMethods: Set<String> = emptySet(),
        val membersHidingMethods: Set<String> = emptySet(),
        val signature: String? = null,
        val deprecatedEnumEntries: Map<String, Deprecation> = emptyMap()
    ) : Deprecations {

        /**
         * Whether the extension [function] is annotated with `@kotlin.internal.HidesMembers`,
         * which only the standard library can use, like `Throwable.addSuppressed`,
         * so that it is called instead of a member of its receiver, see [jvmOnlyFunctionsNotHidden].
         */
        fun hidesMembers(function: KmFunction): Boolean =
            membersHidingMethods.isNotEmpty() && function.signature?.toString() in membersHidingMethods

        /**
         * Whether the class is deprecated as hidden, and cannot be used in the source code.
         */
        val isHiddenDeprecated: Boolean get() = classDeprecation?.isHidden == true

        /*
         * The deprecation of a declaration of the class, like an overload deprecated as hidden,
         * kept only for the binary compatibility, which the metadata does not tell.
         * The annotation is found on the JVM method of a function or a constructor,
         * and on the synthetic method for the annotations of a property, or its getter, if annotated itself,
         * while the accessors of a `var`, when annotated themselves, are deprecated apart, see [varDeprecationOf].
         * The ACC_SYNTHETIC flag, which the compiler also sets on the hidden methods, is not relied on,
         * as @JvmSynthetic sets it on the declarations which are still callable from Kotlin,
         * while the field of a hidden @JvmField or const property does not have it.
         */

        override fun deprecation(function: KmFunction): Deprecation? = deprecation(function.signature)

        override fun deprecation(constructor: KmConstructor): Deprecation? = deprecation(constructor.signature)

        override fun deprecation(property: KmProperty): Deprecation? =
            deprecation(property, annotationsDeprecation(property))

        /**
         * The deprecation of the [property], whose own one, of the synthetic method for its annotations,
         * is the [annotationsDeprecation], followed by the ones of its accessors, deprecated apart, see [varDeprecationOf],
         * as a `var` can be read, and assigned, while a `val` is deprecated by the ones of reading it, see [Deprecation.ofReading].
         */
        fun deprecation(property: KmProperty, annotationsDeprecation: Deprecation?): Deprecation? {
            val getterDeprecation = deprecation(property.getterSignature)
            return if (property.isVar) {
                varDeprecationOf(annotationsDeprecation, getterDeprecation, deprecation(property.setterSignature))
            } else {
                varDeprecationOf(annotationsDeprecation, getterDeprecation, setterDeprecation = null)?.ofReading()
            }
        }

        fun annotationsDeprecation(property: KmProperty): Deprecation? = deprecation(property.syntheticMethodForAnnotations)

        override fun deprecation(enumEntry: KmEnumEntry): Deprecation? = deprecatedEnumEntries[enumEntry.name]

        /**
         * Whether the synthetic method for the annotations of the [property] is rather in the `DefaultImpls` class,
         * as it is for an interface, or an annotation class, which does not declare it itself.
         */
        fun hasAnnotationsInDefaultImpls(property: KmProperty): Boolean {
            val annotationsMethod = property.syntheticMethodForAnnotations ?: return false
            return isInterface && annotationsMethod.toString() !in annotationsMethods
        }

        // most classes have no deprecated methods, so that no signature is rendered to a string for them
        private fun deprecation(signature: JvmMethodSignature?): Deprecation? =
            if (deprecatedMethods.isEmpty() || signature == null) null else deprecatedMethods[signature.toString()]

    }

    /**
     * What a class file tells about the classes nested in its class.
     *
     * @property memberClasses the member classes, of this or the other classes it refers to,
     *   declared by the `InnerClasses` attribute, the ones of the other classes kept as well, as they
     *   might tell a class nested in a class which is not read, like `Outer${'$'}Nested${'$'}Deep` of a Java class compiled
     *   without the `NestMembers` attribute, before Java 11, referred to by another class scanned together with it,
     *   see [ClasspathScanner.scan]: any class of the same jar, even of another package,
     *   while only a class of the same package in a directory, scanned one directory at a time;
     *   of all of them, [NestedClassFiles] keeps only the ones of the class files within the group.
     * @property nestMembers the classes compiled from the same source as this nest host,
     *   declared by the `NestMembers` attribute.
     */
    private class Nesting(
        val memberClasses: List<MemberClass>,
        val nestMembers: List<String>
    )

    /**
     * The class with the internal [name], declared as the member named [simpleName] of the [outerName] class.
     */
    private class MemberClass(
        val name: String,
        val outerName: String,
        val simpleName: String
    )

    /**
     * What the classpath scan learns, from the class files read in a group of them, see [ClasspathScanner.scan],
     * about the classes nested in them, by their internal names, telling the nested class files
     * which cannot contain an indexed Kotlin class, so that they are not even read,
     * which matters for Java libraries with thousands of nested classes:
     *
     * - the classes nested in a class without the Kotlin class metadata, like a Java class, a Kotlin file facade,
     *   a Kotlin class with the metadata stripped, like by R8, or of a kind unknown to this library,
     *   as a Kotlin class nested in them is not indexed anyway, its outer class being unknown, see `enclosingScopes`,
     * - the members of a Kotlin class generated by the compiler, like `$DefaultImpls` or `$WhenMappings`,
     *   see [GENERATED_NESTED_CLASSES], unless declared in its metadata as nested classes;
     *   any other member is read, even if not declared in the metadata,
     *   like a class generated by a compiler plugin might not be.
     *
     * Only what the class files declare is trusted, never the names of the class files,
     * as a top-level Kotlin class can be named like `JavaOuter$Name`.
     * A class nested in a class which is missing from the group, or not read, is read, as any other class,
     * like `Outer$Nested$Deep`, when `Outer$Nested` is skipped, while `Outer` declares only its own member classes,
     * as the Kotlin classes and the Java classes compiled before Java 11, without the `NestMembers` attribute, do.
     */
    private class NestedClassFiles(private val classFileNames: Set<String>) {

        // only of the class files of the group, as no other class is asked about, see isNonKotlinNestedClass,
        // and not of the classes of other libraries it refers to, like java/util/Map$Entry
        private val memberClasses = mutableMapOf<String, MemberClass>()

        private val nonKotlinClasses = mutableSetOf<String>()

        // the simple names of the nested classes and enum entries of the Kotlin classes, declared in their metadata
        private val kotlinNestedClasses = mutableMapOf<String, Set<String>>()

        fun add(name: String, classFile: ClassFile, kmClass: KmClass?) {
            val nesting = classFile.nesting ?: return
            nesting.memberClasses.forEach { if (it.name in classFileNames) memberClasses.putIfAbsent(it.name, it) }
            if (kmClass != null) {
                // an enum entry with a body is compiled to a nested class too, having the class metadata
                kotlinNestedClasses[name] = (kmClass.nestedClasses + kmClass.kmEnumEntries.map { it.name }).toSet()
            } else {
                nonKotlinClasses += name
                nonKotlinClasses += nesting.nestMembers
            }
        }

        fun isNonKotlinNestedClass(name: String): Boolean {
            if (name in nonKotlinClasses) return true
            val memberClass = memberClasses[name] ?: return false
            val isNonKotlin = memberClass.outerName in nonKotlinClasses ||
                kotlinNestedClasses[memberClass.outerName]?.let { kotlinNested ->
                    memberClass.simpleName in GENERATED_NESTED_CLASSES && memberClass.simpleName !in kotlinNested
                } == true
            // so that the classes nested in it are recognized as well
            if (isNonKotlin) nonKotlinClasses += name
            return isNonKotlin
        }

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

    /**
     * The extensions listed for a class, the ones on any of the [receiverNames], like the class and its supertypes,
     * see [listedFor].
     */
    private fun extensionsListedFor(receiverNames: Collection<String>, supertypeNames: Set<String>): ListedExtensions =
        ListedExtensions(
            extensionIndex.listedFor(receiverNames, supertypeNames, multipleBoundsExtensionIndex),
            extensionPropertyIndex.listedFor(receiverNames, supertypeNames, multipleBoundsExtensionPropertyIndex)
        )

    /**
     * The extensions of this index listed for a class, the ones on any of the [receiverNames], like the class
     * and its supertypes, each one once, skipping the ones on a type parameter with multiple bounds,
     * like `where T : A, T : B`, unless the class is a subtype of all of them, among the [supertypeNames],
     * which are then listed also when indexed under none of the [receiverNames], looked up in the [multipleBoundsIndex],
     * like `R.contains(element)` where `R : ClosedRange<T>, R : Iterable<T>` for a class implementing both,
     * which does not list the generic extensions of the standard library on any of these bounds alone,
     * see [userSupertypes].
     *
     * @param multipleBoundsIndex the extensions of this index on a type parameter with multiple bounds, see [withMultipleBounds].
     */
    private fun <E : ReceiverBounded> Map<String, List<E>>.listedFor(
        receiverNames: Collection<String>,
        supertypeNames: Set<String>,
        multipleBoundsIndex: Map<String, List<E>>
    ): List<E> {
        fun E.isSatisfied() = receiverBounds.all { it in supertypeNames }
        val listed = receiverNames.flatMap { this[it].orEmpty() }.filter { it.receiverBounds.isEmpty() || it.isSatisfied() }
        val receiverNameSet = receiverNames.toSet()
        val boundedBySupertypesOnly = supertypeNames
            .filter { it !in receiverNameSet }
            .flatMap { multipleBoundsIndex[it].orEmpty() }
            .filter { it.isSatisfied() }
        return (listed + boundedBySupertypesOnly).distinct()
    }

    private fun requirePositivePageSize(pageSize: Int) {
        require(pageSize > 0) { "pageSize must be positive, but was: $pageSize" }
    }

    override fun resolve(
        name: String,
        page: Int,
        pageSize: Int
    ): String? {
        requirePositivePageSize(pageSize)
        // a class lookup failing maybe only for now is retried once per resolve
        return transientFailures.operation { resolve(name, typeAliases = emptySet()) }?.render(name, page, pageSize)
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

        val resultPage = paginate(matchingClasses, page, pageSize)
        return (listOf("// search results for \"$query\" [page ${resultPage.number}/${resultPage.count}]") +
            resultPage.items).joinToString("\n")
    }
}

/**
 * A page of [items], the [number]th, 1-based, of [count] pages of at most [pageSize] items each.
 */
private class Page<T>(val items: List<T>, val number: Int, val count: Int, val pageSize: Int)

/**
 * The [page] of the [items], coerced into the range of the pages, of which there is at least one, even without items.
 */
private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): Page<T> {
    // computed without `items.size + pageSize - 1`, which overflows for a `pageSize` near Int.MAX_VALUE
    val count = if (items.isEmpty()) 1 else (items.size - 1) / pageSize + 1
    val number = page.coerceIn(1, count)
    // in Long, not to overflow for a large `pageSize`, whatever the page number
    val startIndex = minOf((number - 1).toLong() * pageSize, items.size.toLong()).toInt()
    val endIndex = minOf(startIndex.toLong() + pageSize, items.size.toLong()).toInt()
    return Page(items.subList(startIndex, endIndex), number, count, pageSize)
}

/**
 * A resolved declaration, paginated by its [items] by the [DefaultKotlinIntrospector.resolve],
 * where each page renders on its own, as the headers the items are listed under are repeated on every page.
 */
private sealed interface Resolved<T : ListingItem> {

    /**
     * The units of pagination, never split across pages.
     */
    val items: List<T>

    /**
     * The lines of the [page] of the [items].
     */
    fun renderPage(page: Page<T>): List<String>

    /**
     * The [page] of the declaration resolved by the [name], under the header telling the page number.
     */
    fun render(name: String, page: Int, pageSize: Int): String {
        val resolvedPage = paginate(items, page, pageSize)
        return (listOf("// $name [page ${resolvedPage.number}/${resolvedPage.count}]") + renderPage(resolvedPage))
            .joinToString("\n")
    }

}

/**
 * This resolved declaration, its every page following the [context] comment, see [ContextualResolved].
 */
private fun <T : ListingItem> Resolved<T>.withContext(context: String): Resolved<T> = ContextualResolved(this, context)

/**
 * The [resolved] declaration, its every page following the [context] comment,
 * like the deprecated typealias a member is resolved through, see `DefaultKotlinIntrospector.resolveTypeAlias`.
 */
private class ContextualResolved<T : ListingItem>(private val resolved: Resolved<T>, private val context: String) : Resolved<T> {

    override val items: List<T> get() = resolved.items

    override fun renderPage(page: Page<T>): List<String> = listOf(context) + resolved.renderPage(page)

}

/**
 * An item of a [Resolved] declaration, the unit of pagination, never split across pages,
 * either a [ClassItem], or a [MemberItem], or both.
 */
private sealed interface ListingItem {

    /**
     * An item of a [ClassListing], either a [ClassBodyItem], or an [Extension].
     */
    sealed interface ClassItem : ListingItem

    /**
     * An item of the body of a [ClassListing].
     */
    sealed interface ClassBodyItem : ClassItem

    /**
     * An item of a [MemberListing].
     */
    sealed interface MemberItem : ListingItem

    /**
     * An enum entry of a [ClassListing], rendered on the [line], together with the separator following it.
     */
    class EnumEntry(val line: String) : ClassBodyItem

    /**
     * A declaration of the body of a [ClassListing], or found by a [MemberListing], rendered there as is, on its [lines]:
     * a single one, like a function, or more, like a property with the line of its setter, never split across pages.
     */
    class Declaration(val lines: List<String>) : ClassBodyItem, MemberItem

    /**
     * The companion object of a [ClassListing], declared with the [header], with its [members], each on its lines,
     * a single item, as the members of the companion object are paged by resolving it directly by the [name].
     *
     * @param name the fully qualified name of the companion object to resolve, like `com.example.Outer.Companion`.
     */
    class CompanionObject(val header: String, val members: List<List<String>>, val name: String) : ClassBodyItem

    /**
     * A member of a companion object found by a [MemberListing], on its [lines], see [Declaration],
     * rendered within the declaration of the companion object, the [header], repeated on every page.
     */
    class CompanionMember(val header: String, val lines: List<String>) : MemberItem

    /**
     * An extension, listed under the `// from` header of its [source], repeated on every page,
     * rendered on its [lines], see [Declaration].
     *
     * @param isCalledOnClassName whether the extension found by a [MemberListing] is called on the name of the class,
     *   like `Foo.parse()` of an extension on its companion object, or of any extension of an object,
     *   so that Kotlin reports the deprecations of the classes the name goes through, see [EnclosingClass].
     * @param isAlsoCalledOnInstance whether the extension called on the name of the class is also called on its instance,
     *   like `foo.ext()` of an extension on a supertype of both the class and its companion object,
     *   for which Kotlin reports no deprecation, so that its [lines] tell the deprecations themselves,
     *   see [EnclosingClass.deprecationOnClassName].
     */
    class Extension(
        val source: String,
        val lines: List<String>,
        val isCalledOnClassName: Boolean = false,
        val isAlsoCalledOnInstance: Boolean = false
    ) : ClassItem, MemberItem

    /**
     * A member inherited from a supertype found by a [MemberListing], which the class does not override,
     * listed under the `// inherited from` header of the supertype, the [source], repeated on every page,
     * rendered on its [lines], see [Declaration].
     */
    class InheritedMember(val source: String, val lines: List<String>) : MemberItem

}

/**
 * The extensions rendered under the `// from` header of their source, repeated whenever the source changes.
 */
private fun List<ListingItem.Extension>.renderLines(): List<String> = buildList {
    var source: String? = null
    for (extension in this@renderLines) {
        if (extension.source != source) {
            // escaped like in an import directive, like `import com.example.`in`.*`
            add("// from ${escapeQualifiedName(extension.source)}")
            source = extension.source
        }
        addAll(extension.lines)
    }
}

/**
 * A class enclosing the declarations resolved directly, told by the comment on its [line] rendered before them,
 * see `DefaultKotlinIntrospector.enclosingContext`.
 *
 * @param isDeprecated whether the class is deprecated, which Kotlin reports on its name the caller writes,
 *   also when calling an extension on it, see [ListingItem.Extension.isCalledOnClassName],
 *   while a class told only as protected matters to an extension only when called on its name too, see [lineOn].
 * @param protectedLine the comment telling the outermost protected class without its deprecation,
 *   like `// in protected class Outer.Foo`, or `null` if the class is not the outermost protected one.
 * @param deprecationOnClassName the comment telling the deprecation of the class on an extension called both on the name
 *   of the class, and on its instance, see [ListingItem.Extension.isAlsoCalledOnInstance], which Kotlin reports only for the former,
 *   like `/* class Foo deprecated: "Use Bar" (when called on the class name) */`, or `null` if the class is not deprecated.
 */
private class EnclosingClass(
    val line: String,
    val isDeprecated: Boolean,
    val protectedLine: String?,
    val deprecationOnClassName: String? = null
) {

    /**
     * The comment rendered on a page of a [MemberListing], if any: the [line] of a deprecated class
     * when [isOnlyOnClassName], with a declaration called only on the name of the class, like a member of an object,
     * or of a companion object, or an extension called so, see [ListingItem.Extension.isCalledOnClassName],
     * unlike a member, or an extension, of a class, called on its instance, like `foo.bar()`,
     * also an extension called both ways, which tells the deprecation itself, see [deprecationOnClassName],
     * else the [protectedLine] when it [hasMembers], not only extensions, or [isOnClassName],
     * with a declaration called on the name of the class,
     * like the extension `Outer.Named.ext()` of `fun Bar.ext()` for `protected companion object Named : Bar`,
     * which only the subclasses of the outer class can call, unlike an extension called on an instance of a protected class.
     */
    fun lineOn(hasMembers: Boolean, isOnClassName: Boolean, isOnlyOnClassName: Boolean): String? = when {
        isDeprecated && isOnlyOnClassName -> line
        hasMembers || isOnClassName -> protectedLine
        else -> null
    }

}

/**
 * A class listed with its [header], the declaration line without the body, followed by the [items]:
 * the enum entries, and the members of the class body, see [ListingItem.ClassBodyItem],
 * then the extensions, see [ListingItem.Extension].
 *
 * @param context the comments rendered before the [header] on every page, see [EnclosingClass].
 * @param isEnum whether the class is an enum class, whose members are separated from its entries with a semicolon.
 */
private class ClassListing(
    val header: String,
    val context: List<EnclosingClass>,
    val isEnum: Boolean,
    override val items: List<ListingItem.ClassItem>
) : Resolved<ListingItem.ClassItem> {

    /**
     * The class body is shown only on a page with entries or members, and the semicolon separating the members
     * of an enum class from its entries on every such page without entries, as the last entry carries it.
     * A class without a body still has its header shown on the first page, e.g. to show the constructor.
     */
    override fun renderPage(page: Page<ListingItem.ClassItem>): List<String> = buildList {
        context.forEach { add(it.line) }
        val body = page.items.filterIsInstance<ListingItem.ClassBodyItem>()
        if (body.isNotEmpty()) {
            add("$header {")
            if (isEnum && body.none { it is ListingItem.EnumEntry }) add("    ;")
            body.forEach { item ->
                when (item) {
                    is ListingItem.EnumEntry -> add("    ${item.line}")
                    is ListingItem.Declaration -> item.lines.forEach { add("    $it") }
                    is ListingItem.CompanionObject -> renderCompanionObject(item, page.pageSize).forEach { add("    $it") }
                }
            }
            add("}")
        } else if (page.number == 1 && items.none { it is ListingItem.ClassBodyItem }) {
            add(header)
        }
        addAll(page.items.filterIsInstance<ListingItem.Extension>().renderLines())
    }

    /**
     * The [companion] object, with a hint to resolve it directly when it has more members than a page.
     */
    private fun renderCompanionObject(companion: ListingItem.CompanionObject, pageSize: Int): List<String> =
        if (companion.members.isEmpty()) listOf(companion.header) else buildList {
            add("${companion.header} {")
            if (companion.members.size > pageSize) {
                add("    // ${companion.members.size} members, resolve ${companion.name} to page through them")
            }
            companion.members.flatten().forEach { add("    $it") }
            add("}")
        }

    companion object {

        /**
         * A class listing of the [enumEntryNames], then of the [members], and then of the [extensions],
         * where the members of an enum class have to be separated from its entries with a semicolon,
         * following the last entry, or on a line of its own, if there are no entries, see [renderPage].
         */
        fun of(
            header: String,
            context: List<EnclosingClass> = emptyList(),
            isEnum: Boolean = false,
            enumEntryNames: List<String> = emptyList(),
            members: List<ListingItem.ClassBodyItem> = emptyList(),
            extensions: List<ListingItem.Extension> = emptyList()
        ): ClassListing = ClassListing(
            header,
            context,
            isEnum,
            enumEntryNames.mapIndexed { index, name ->
                ListingItem.EnumEntry(
                    name + when {
                        index < enumEntryNames.lastIndex -> ","
                        members.isNotEmpty() -> ";"
                        else -> ""
                    }
                )
            } + members + extensions
        )

    }

}

/**
 * The declarations found by a member name, like the overloads of a function, one signature per item:
 * the members, see [ListingItem.Declaration], then the members inherited from the supertypes,
 * see [ListingItem.InheritedMember], then the members of the companion object,
 * see [ListingItem.CompanionMember], and then the extensions, see [ListingItem.Extension].
 */
private class MemberListing(
    override val items: List<ListingItem.MemberItem>,
    private val context: List<EnclosingClass>,
    private val areMembersCalledOnClassName: Boolean
) : Resolved<ListingItem.MemberItem> {

    override fun renderPage(page: Page<ListingItem.MemberItem>): List<String> = buildList {
        val hasMembers = page.items.any { it !is ListingItem.Extension }
        fun hasItemOnClassName(isExtensionOnClassName: (ListingItem.Extension) -> Boolean) = page.items.any {
            when (it) {
                is ListingItem.CompanionMember -> true
                is ListingItem.Extension -> isExtensionOnClassName(it)
                is ListingItem.Declaration, is ListingItem.InheritedMember -> areMembersCalledOnClassName
            }
        }
        val isOnClassName = hasItemOnClassName { it.isCalledOnClassName }
        // an extension also called on an instance tells the deprecations of the classes itself
        val isOnlyOnClassName = hasItemOnClassName { it.isCalledOnClassName && !it.isAlsoCalledOnInstance }
        context.forEach { enclosingClass ->
            enclosingClass.lineOn(hasMembers, isOnClassName, isOnlyOnClassName)?.let { add(it) }
        }
        page.items.filterIsInstance<ListingItem.Declaration>().forEach { addAll(it.lines) }
        page.items.filterIsInstance<ListingItem.InheritedMember>().groupBy { it.source }.forEach { (source, members) ->
            add("// inherited from ${escapeQualifiedName(source)}")
            members.forEach { addAll(it.lines) }
        }
        page.items.filterIsInstance<ListingItem.CompanionMember>().groupBy { it.header }.forEach { (header, members) ->
            add("$header {")
            members.forEach { member -> member.lines.forEach { add("    $it") } }
            add("}")
        }
        addAll(page.items.filterIsInstance<ListingItem.Extension>().renderLines())
    }

    companion object {

        /**
         * The listing of the [items], or `null` if nothing is found.
         *
         * @param context the comments rendered first: the one of a protected class on every page with the members of the class,
         *   not only its extensions, or with an extension called on its name,
         *   while the one of a deprecated class only on every page with a declaration called only on its name,
         *   else without the deprecation, if protected, see [EnclosingClass.lineOn].
         * @param areMembersCalledOnClassName whether the members of the class, also the inherited ones, are called on its name,
         *   like the ones of an object, or of an enum class, called on its entry, like `Color.RED.rgb()`,
         *   unlike the ones of any other class, called on its instance, like `foo.bar()`.
         */
        fun of(
            items: List<ListingItem.MemberItem>,
            context: List<EnclosingClass> = emptyList(),
            areMembersCalledOnClassName: Boolean = false
        ): MemberListing? = if (items.isEmpty()) null else MemberListing(items, context, areMembersCalledOnClassName)

    }

}

/**
 * The type parameter of the receiver of this extension function, its only type parameter, not nullable,
 * like `T` of `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T)`,
 * which may be pinned to the class it is listed for, see `pins`, or else `null`.
 */
private val KmFunction.pinnableReceiverTypeParameter: KmTypeParameter?
    get() = pinnableReceiverTypeParameter(receiverParameterType, typeParameters)

/**
 * Whether this property is read anywhere, without a context parameter, outside whose context Kotlin reads
 * the extension property it would shadow instead, like `val Bar.rank` next to a member `context(c: Ctx) val rank`.
 */
@OptIn(ExperimentalContextParameters::class)
private val KmProperty.isReadAnywhere: Boolean get() = contextParameters.isEmpty()

/**
 * Whether this extension function has a type parameter which only a type argument given explicitly tells,
 * like `R` of `fun <R> Bar.ext()`, called as `bar.ext<Int>()`, or inferred from the expected type:
 * one which neither its receiver, nor a value parameter, nor a context parameter refers to.
 */
@OptIn(ExperimentalContextParameters::class)
private val KmFunction.hasExplicitTypeParameter: Boolean
    get() = typeParameters.isNotEmpty() && run {
        val types = listOfNotNull(receiverParameterType) + valueParameters.map { it.type } + contextParameters.map { it.type }
        typeParameters.any { typeParameter -> types.all { type -> type.refersOnlyTo { it != typeParameter.id } } }
    }

/**
 * The type parameter of the receiver of this extension property, like the one of an extension function.
 */
private val KmProperty.pinnableReceiverTypeParameter: KmTypeParameter?
    get() = pinnableReceiverTypeParameter(receiverParameterType, typeParameters)

private fun pinnableReceiverTypeParameter(receiver: KmType?, typeParameters: List<KmTypeParameter>): KmTypeParameter? {
    val receiverTypeParameter = receiver?.classifier as? KmClassifier.TypeParameter ?: return null
    if (receiver.isNullable) return null
    return typeParameters.singleOrNull()?.takeIf { it.id == receiverTypeParameter.id }
}

/**
 * Whether this function, a member, or an extension on the class named [className], like `fun Int.coerceAtLeast(minimumValue: Int)`,
 * is called instead of the extension [function] on a type parameter pinned to the class, see `withoutShadowed`,
 * like `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T)`, unless its type argument is given explicitly:
 * the value parameters of the [function] have neither a default value, nor a vararg, which this function could not be called with,
 * while this function takes the same value parameters, of the same names, as they can be named in a call,
 * and of the types of the parameters of the [function], where `T` stands for the class, see [isSameType],
 * without any type parameter of its own, or any context parameter,
 * and it is an operator, or infix, if the [function] is, as only then it is called the same way,
 * also when it is suspend, called then also outside a coroutine, failing to compile.
 */
private fun KmFunction.shadows(function: KmFunction, className: String): Boolean {
    val receiver = function.receiverParameterType ?: return false
    val receiverTypeParameter = receiver.classifier as? KmClassifier.TypeParameter ?: return false
    return !receiver.isNullable && isCalledLike(function, receiverTypeParameter.id, className)
}

/**
 * Whether this function is called like the extension [function], see [shadows],
 * where the type parameter of the [receiverTypeParameterId] stands for the class named [className], see [isSameType].
 */
@OptIn(ExperimentalContextParameters::class)
private fun KmFunction.isCalledLike(function: KmFunction, receiverTypeParameterId: Int, className: String): Boolean =
    function.valueParameters.none { it.declaresDefaultValue || it.varargElementType != null } &&
        typeParameters.isEmpty() && contextParameters.isEmpty() &&
        (isOperator || !function.isOperator) &&
        (isInfix || !function.isInfix) &&
        valueParameters.size == function.valueParameters.size &&
        valueParameters.zip(function.valueParameters).all { (parameter, shadowedParameter) ->
            parameter.name == shadowedParameter.name &&
                parameter.varargElementType == null &&
                isSameType(shadowedParameter.type, parameter.type, receiverTypeParameterId, className)
        }

/**
 * Whether this function, a Java method, can shadow an extension, see [shadows]: only when it takes no value parameter,
 * as Kotlin passes no named argument to a Java method, so that a call naming the arguments,
 * like `leaf.linkTo(next = leaf)`, calls the extension, whatever the names of the parameters of the Java method.
 */
private val KmFunction.isShadowingJavaMethod: Boolean get() = valueParameters.isEmpty()

/**
 * The extensions of this index on a type parameter with multiple bounds, see [receiverBoundsOf], under each of them,
 * looked up under every supertype of a class, see `listedFor`, unlike the many other ones.
 */
private fun <E : ReceiverBounded> Map<String, List<E>>.withMultipleBounds(): Map<String, List<E>> =
    mapValues { (_, extensions) -> extensions.filter { it.receiverBounds.isNotEmpty() } }.filterValues { it.isNotEmpty() }

/**
 * The type arguments of a type of a class, substituted for its type parameters in its supertypes,
 * see [DefaultKotlinIntrospector.substitutedProjecting].
 *
 * @property projections the type arguments by the ids of the type parameters they are given for,
 *   a projection repeating the variance of its type parameter, like `out String` of `Node<out E>`, being no projection.
 * @property substitution their types by the same ids, `Any?` of a star projection, projected again.
 * @property projected the ids of the type parameters given a star projection, or another projection.
 */
private class TypeProjections(val projections: Map<Int, KmTypeProjection>) {
    val substitution: Map<Int, KmType> = projections.mapValues { (_, projection) -> projection.type ?: NULLABLE_ANY_TYPE }
    val projected: Set<Int> = projections.filterValues { it.type == null || it.variance != KmVariance.INVARIANT }.keys
}

// the read-only collection types of the mutable ones, like kotlin/collections/List of kotlin/collections/MutableList, see collectionVariants
private val READ_ONLY_COLLECTION_TYPES: Map<String, String> = MUTABLE_COLLECTION_TYPES.entries.associate { (readOnly, mutable) -> mutable to readOnly }

// the most variants of the types of the value parameters of a member compared with a member of a Java class, see collectionVariants,
// as each collection type of a Java member doubles them, like a Map<String, List<String>> of four
private const val MAX_RIVALRY_VARIANTS = 64

// whether the JVM array type is one of a primitive type, like int[], unlike int[][], see isMergedJvmParameter
private val AsmType.isPrimitiveArray: Boolean get() = dimensions == 1 && elementType.isPrimitive

// the JVM names of the boxed primitive types of Java, like java/lang/Integer, see isMergedJvmParameter
private val BOXED_JVM_NAMES: Set<String> = listOf(
    Boolean::class, Char::class, Byte::class, Short::class, Int::class, Float::class, Long::class, Double::class
).mapTo(HashSet()) { AsmType.getInternalName(it.javaObjectType) }

// the type of Any?, never modified, see TypeProjections
private val NULLABLE_ANY_TYPE = classType("kotlin/Any", nullable = true)

// the depth of the type arguments of this type, like 2 of List<String>, the deepest one of the types of an intersection
private fun KmType.depth(): Int {
    val depth = arguments.maxOfOrNull { it.type?.depth() ?: 1 } ?: 0
    return if (classifier == INTERSECTION_CLASSIFIER) depth else depth + 1
}

// the classifier of the type of an intersection, of its types as the type arguments, see CallInference
private val INTERSECTION_CLASSIFIER = KmClassifier.TypeAlias("&")

// the type of Nothing, never modified
private val NOTHING_TYPE = classType("kotlin/Nothing")

// the most constraints the inference of the type parameters of an extension adds, not modelled beyond, see CallInference
private const val MAX_INFERENCE_STEPS = 2000

// the type arguments of a class without type parameters, or of the class itself, see TypeProjections
private val NO_PROJECTIONS = TypeProjections(emptyMap())

/**
 * The type arguments of the [type] of the [kmClass], see [TypeProjections],
 * or `null` if they do not match, like of a raw Java type.
 */
private fun projectionsOf(kmClass: KmClass, type: KmType): TypeProjections? {
    if (kmClass.typeParameters.size != type.arguments.size) return null
    return TypeProjections(
        kmClass.typeParameters.zip(type.arguments).associate { (typeParameter, argument) ->
            val argumentType = argument.type
            val isRedundant = argument.variance != KmVariance.INVARIANT && argument.variance == typeParameter.variance
            typeParameter.id to if (argumentType != null && isRedundant) {
                KmTypeProjection(KmVariance.INVARIANT, argumentType)
            } else {
                argument
            }
        }
    )
}

/**
 * The variance of a type argument of the [outer] projection, projected again by the [inner] one,
 * like `out` of `out` of `Collection<out E>` of `E` projected as `out String`,
 * or `null` if they contradict each other, like `out` and `in`, standing for a star projection then.
 */
private fun combinedVariance(outer: KmVariance, inner: KmVariance): KmVariance? = when {
    outer == KmVariance.INVARIANT || outer == inner -> inner
    inner == KmVariance.INVARIANT -> outer
    else -> null
}

/**
 * The variance of a type argument of a type parameter of the [declared] variance, [projected] at the use site,
 * like `out` of `Box<out Number>`, or `null` if the projection contradicts it, like `in` of an `out` type parameter,
 * which stands for a star projection then.
 */
private fun projectedVariance(declared: KmVariance, projected: KmVariance): KmVariance? = when {
    declared == KmVariance.INVARIANT || declared == projected -> projected
    projected == KmVariance.INVARIANT -> declared
    else -> null
}

/**
 * The variance of a position of the [inner] variance within a position of this variance,
 * like a contravariant one within a contravariant one, which is covariant.
 */
private fun KmVariance.compose(inner: KmVariance): KmVariance = when {
    this == KmVariance.INVARIANT || inner == KmVariance.INVARIANT -> KmVariance.INVARIANT
    this == KmVariance.OUT -> inner
    inner == KmVariance.OUT -> KmVariance.IN
    else -> KmVariance.OUT
}

/**
 * Whether the nullability of the [expected] type matches the one of the [actual] type in a position of the [variance],
 * like a nullable one in a covariant position, which is a supertype of the not nullable one,
 * while a platform type of Java matches either, also an expected one, like a type a type parameter is inferred from,
 * see `ReceiverMatcher.infer`, unless made nullable, like `W<String!>!` substituted for `T` of `W<T?>`, which is `W<String!>?`.
 */
private fun isNullabilityMatched(expected: KmType, actual: KmType, variance: KmVariance): Boolean = when {
    // a platform type of Java, see javaSignatureType
    (actual.flexibleTypeUpperBound != null && !actual.isNullable) ||
        (expected.flexibleTypeUpperBound != null && !expected.isNullable) -> true
    variance == KmVariance.INVARIANT -> expected.isNullable == actual.isNullable
    variance == KmVariance.OUT -> expected.isNullable || !actual.isNullable
    else -> actual.isNullable || !expected.isNullable
}

/**
 * This type, a supertype told by the signature of a Java class, with the type arguments of the [substitution],
 * by the ids of the type parameters of the Java class, substituted for them,
 * its type arguments, also the nested ones, as the platform types of Java, like `Comparable<Base!>`,
 * flexible from not nullable to nullable, see [isNullabilityMatched],
 * also a not nullable type argument substituted for a type parameter, like `Box<String!>` of `Box<T>` for `T` of `String`,
 * or `Box<V!>` for a type parameter `V` of the class whose supertype it is, like of `class K<V> : JavaBox<V>()`,
 * but not an erased type argument of a raw Kotlin supertype, see [isJavaErasure], unless substituted for a type parameter,
 * but not a nullable one, like `Box<String?>` for `T` of `String?`, nor the nested type arguments of either,
 * declared by Kotlin.
 * A Java collection of the signature, like `java.util.List<T>`, read-only as kotlin-reflect tells it,
 * is marked as flexible from mutable to read-only as well, like `(Mutable)List<String!>!`,
 * see [isJavaCollection], kept through substitution, unlike the upper bound of a flexible type,
 * while the other users of the type see the read-only one,
 * and a Java array, like `String[]`, as flexible from invariant to covariant, like `Array<(out) String!>!`, see [isJavaArray].
 */
private fun KmType.javaSignatureType(substitution: Map<Int, KmType>): KmType = copy(
    arguments = arguments.map { argument ->
        val type = argument.type ?: return@map argument
        val classifier = type.classifier
        val argumentType = if (classifier is KmClassifier.TypeParameter) {
            // an erased type argument of a raw Kotlin supertype substituted for a type variable of Java is a platform type,
            // like CharSequence! of List<E> for E of CharSequence, see isJavaErasure
            substitution[classifier.id]?.copy()?.also { type -> type.annotations.removeAll { it.className == JAVA_ERASURE_MARKER } }
                ?: return@map argument
        } else if (type.isJavaErasure) {
            // an erased type argument of a raw Kotlin supertype, also its type arguments, like String of MutableList<String>
            return@map argument
        } else {
            type.javaSignatureType(substitution)
        }
        val argumentClassifier = argumentType.classifier
        // an erased type argument of a raw Kotlin supertype is the type kotlinc erases it to, see JAVA_ERASURE_MARKER
        if (!argumentType.isNullable && argumentClassifier !is KmClassifier.TypeAlias && !argumentType.isJavaErasure) {
            argumentType.flexibleTypeUpperBound =
                KmFlexibleTypeUpperBound(argumentType.copy().also { it.isNullable = true }, typeFlexibilityId = null)
            if (classifier is KmClassifier.Class && argumentClassifier is KmClassifier.Class) {
                if (argumentClassifier.name in MUTABLE_COLLECTION_TYPES) {
                    argumentType.annotations += KmAnnotation(JAVA_COLLECTION_MARKER, emptyMap())
                }
                if (argumentClassifier.name == ARRAY_CLASS_NAME) argumentType.annotations += KmAnnotation(JAVA_ARRAY_MARKER, emptyMap())
            }
        }
        KmTypeProjection(argument.variance, argumentType)
    },
    outerType = outerType?.javaSignatureType(substitution)
)

// the marker of a Java collection of the signature of a Java class, see javaSignatureType, never rendered
private const val JAVA_COLLECTION_MARKER = "com/xemantic/kotlin/introspection/JavaCollection"

/**
 * The metadata names of the supertypes which kotlinc sees the JDK classes, by their metadata names, implement purely,
 * with their own type arguments, not as platform types, see `FakePureImplementationsProvider` of kotlinc,
 * like `MutableList<E>` of `java.util.ArrayList<E>`, also when implemented through a Java supertype,
 * like `MutableSet<E>` of `java.util.LinkedHashSet<E>`, through `HashSet<E!>`, see `javaSupertypeTypes`,
 * walked before the Java supertypes, so that the supertypes of these are walked from them, like `MutableCollection<E>`,
 * like kotlinc prefers a supertype reached through Kotlin to the one reached through Java, see `supertypeNames`.
 */
internal val PURE_IMPLEMENTATIONS: Map<String, String> =
    listOf("java/util/ArrayList", "java/util/LinkedList").associateWith { "kotlin/collections/MutableList" } +
        listOf("java/util/HashSet", "java/util/TreeSet", "java/util/LinkedHashSet").associateWith { "kotlin/collections/MutableSet" } +
        listOf(
            "java/util/HashMap", "java/util/TreeMap", "java/util/LinkedHashMap",
            "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentSkipListMap"
        ).associateWith { "kotlin/collections/MutableMap" } +
        mapOf(
            "java/util/function/UnaryOperator" to "java/util/function/Function",
            "java/util/function/BinaryOperator" to "java/util/function/BiFunction"
        )

// a wildcard bounded by Object in a generic signature, see objectWildcardsOf
private const val OBJECT_WILDCARD = "+Ljava/lang/Object;"

/**
 * The positions of the wildcards bounded by `Object`, like `? extends Object`, in the supertypes of a generic class
 * [signature], seen by Kotlin as `out Any!`, unlike the unbounded wildcard `?`, a star projection,
 * which kotlin-reflect does not tell apart, by the internal names of the supertypes, like `java/util/List`,
 * each position the indices of the type arguments leading to the wildcard,
 * like `[0, 1]` of `Map<String, ? extends Object>` in `List<Map<String, ? extends Object>>`,
 * the component type of an array being its type argument, like of `Array<T>`,
 * and only the ones of the innermost class of an inner class type, like of a type of the Kotlin metadata,
 * or none if the signature cannot be read.
 */
private fun objectWildcardsOf(signature: String): Map<String, Set<List<Int>>> {
    val wildcards = HashMap<String, Set<List<Int>>>()

    // a type at the [path] of a supertype, the type argument of the [wildcard], if any,
    // adding the positions of the wildcards bounded by Object to the [found] ones of the supertype
    class TypeVisitor(
        private val path: List<Int>,
        private val found: MutableList<List<Int>>,
        private val wildcard: Char? = null
    ) : SignatureVisitor(Opcodes.ASM9) {
        private var name = ""
        private var index = 0

        override fun visitClassType(name: String) {
            this.name = name
        }

        override fun visitInnerClassType(name: String) {
            this.name += "$$name"
            // only the type arguments of the innermost class, the ones of the outer classes being the outer type
            index = 0
            found.removeAll { it.size > path.size && it.subList(0, path.size) == path }
        }

        override fun visitArrayType(): SignatureVisitor = TypeVisitor(path + 0, found)

        override fun visitTypeArgument() {
            index++
        }

        override fun visitTypeArgument(wildcard: Char): SignatureVisitor = TypeVisitor(path + index++, found, wildcard)

        override fun visitEnd() {
            if (wildcard == SignatureVisitor.EXTENDS && name == "java/lang/Object" && index == 0) found += path
            if (path.isEmpty() && found.isNotEmpty()) wildcards[name] = found.toSet()
        }
    }

    return try {
        SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
            override fun visitClassBound(): SignatureVisitor = IGNORING_SIGNATURE_VISITOR
            override fun visitInterfaceBound(): SignatureVisitor = IGNORING_SIGNATURE_VISITOR
            override fun visitSuperclass(): SignatureVisitor = TypeVisitor(emptyList(), mutableListOf())
            override fun visitInterface(): SignatureVisitor = TypeVisitor(emptyList(), mutableListOf())
        })
        wildcards
    } catch (_: IllegalArgumentException) {
        emptyMap()
    } catch (_: IndexOutOfBoundsException) {
        emptyMap()
    }
}

// a visitor of a part of a generic signature which is not needed, see objectWildcardsOf
private val IGNORING_SIGNATURE_VISITOR = object : SignatureVisitor(Opcodes.ASM9) {}

// the marker of a Java array of the signature of a Java class, see javaSignatureType, never rendered
private const val JAVA_ARRAY_MARKER = "com/xemantic/kotlin/introspection/JavaArray"

private const val ARRAY_CLASS_NAME = "kotlin/Array"

/**
 * Whether this type is a Java array of the signature of a Java class, like `String[]`,
 * seen by Kotlin as flexible from invariant to covariant, like `Array<(out) String!>!`, see [javaSignatureType].
 */
private val KmType.isJavaArray: Boolean get() = annotations.any { it.className == JAVA_ARRAY_MARKER }

/**
 * The type arguments of this type, covariant if it is a Java array, see [isJavaArray], like `out String!` of `String[]`.
 */
private fun KmType.javaArrayArguments(): List<KmTypeProjection> = if (!isJavaArray) arguments else arguments.map { argument ->
    argument.type?.takeIf { argument.variance == KmVariance.INVARIANT }?.let { KmTypeProjection(KmVariance.OUT, it) } ?: argument
}

/**
 * Whether this type is a Java collection of the signature of a Java class, like `java.util.List<T>`,
 * seen by Kotlin as flexible from mutable to read-only, like `(Mutable)List<T!>!`, see [javaSignatureType].
 */
private val KmType.isJavaCollection: Boolean get() = annotations.any { it.className == JAVA_COLLECTION_MARKER }

/**
 * The mutable alternative of this Java collection, see [isJavaCollection], like `MutableList<String!>` of `List<String!>`,
 * or `null` if it is not one.
 */
private fun KmType.mutableAlternative(): KmType? {
    if (!isJavaCollection) return null
    val name = (classifier as? KmClassifier.Class)?.name ?: return null
    val mutableName = MUTABLE_COLLECTION_TYPES[name] ?: return null
    return copy().also { type ->
        type.classifier = KmClassifier.Class(mutableName)
        type.annotations.removeAll { it.className == JAVA_COLLECTION_MARKER }
    }
}

/**
 * The bounds of this raw type of a Java supertype, see [isJavaRaw], its erasure, like `MutableMap<Any?, Any?>` of `Map`,
 * the mutable alternative of a Java collection, see [mutableAlternative], with the erasures of the bounds of its type parameters,
 * as kotlin-reflect tells them, and its star projection, like `Map<*, *>`, or this type twice, if it is not raw.
 */
private fun KmType.rawBounds(): Pair<KmType, KmType> {
    if (!isJavaRaw) return this to this
    val erasure = (mutableAlternative() ?: copy()).also { type -> type.annotations.removeAll { it.className == JAVA_RAW_MARKER } }
    val star = copy(arguments = arguments.map { KmTypeProjection.STAR }).also { type ->
        type.annotations.removeAll { it.className == JAVA_RAW_MARKER || it.className == JAVA_COLLECTION_MARKER }
    }
    return erasure to star
}

/**
 * Whether a type argument of this type, also of its outer type, is a platform type of Java, see [javaSignatureType],
 * like `String!` of `MutableCollection<String!>`, a supertype reached through a Java class,
 * but not a nested one, like `String!` of `Box<List<String!>>`, as kotlinc tells it,
 * nor one which is no longer a platform type, see [isPlatformType], like `W<String!>?` of `W<String!>!` given for `T?`.
 */
private val KmType.hasPlatformArguments: Boolean
    get() = generateSequence(this) { it.outerType }.any { type ->
        // a Java collection, a Java array, or a raw type, is a platform type as well,
        // and so is an erasure not told exactly, see JAVA_INEXACT_ERASURE_MARKER, only of a direct type argument, like kotlinc
        type.arguments.any { argument ->
            val argumentType = argument.type
            argumentType != null && (argumentType.isPlatformType ||
                argumentType.annotations.any { it.className == JAVA_INEXACT_ERASURE_MARKER })
        }
    }

/**
 * Whether this type is a platform type of Java, see [javaSignatureType], flexible in its nullability, like `String!`,
 * or else, when substituted for a type parameter used as nullable, or as definitely not nullable, which leaves it either,
 * like `W<String!>?` of `T?`, or `W<String!>` of `T & Any`, in its mutability, like `(Mutable)List<String!>?`, see [isJavaCollection],
 * in the variance of its elements, see [isJavaArray], or a raw type, see [isJavaRaw].
 */
private val KmType.isPlatformType: Boolean
    get() = (flexibleTypeUpperBound != null && !isNullable) || isJavaCollection || isJavaArray || isJavaRaw

/**
 * Whether this type of a supertype reached through more paths than [MAX_SUPERTYPE_PATHS] covers the [other] one,
 * matching any type the other one matches, see [ClassSupertypes.types], the same type,
 * but for a platform type of Java in place of the same class, or of its mutable one, see [coversClass],
 * like `Box<(Mutable)List<String!>!>` covering `Box<MutableList<String>>`, and `Box<List<String?>>`,
 * so that one covering others replaces them, and one covered adds nothing.
 */
private fun KmType.coversType(other: KmType): Boolean {
    if (hasSameStructure(other)) return true
    if (arguments.size != other.arguments.size || (outerType == null) != (other.outerType == null)) return false
    if (!coversClass(other) && (classifier != other.classifier || isNullable != other.isNullable ||
            isSuspend != other.isSuspend || isDefinitelyNonNull != other.isDefinitelyNonNull ||
            (flexibleTypeUpperBound == null) != (other.flexibleTypeUpperBound == null) ||
            isJavaCollection != other.isJavaCollection || isJavaArray != other.isJavaArray || isJavaRaw != other.isJavaRaw)
    ) {
        return false
    }
    val outer = outerType
    val otherOuter = other.outerType
    return (outer == null || otherOuter == null || outer.coversType(otherOuter)) && arguments.indices.all { index ->
        val argument = arguments[index]
        val otherArgument = other.arguments[index]
        val type = argument.type
        val otherType = otherArgument.type
        if (type == null || otherType == null) type == null && otherType == null else {
            argument.variance == otherArgument.variance && type.coversType(otherType)
        }
    }
}

// whether this platform type of Java is of the class of the other type, not a platform type, or of its mutable one,
// also a platform one, like (Mutable)List<String!>! of MutableList<String>!, see coversType,
// but not one made nullable, like W<String!>! substituted for T of W<T?>, which kotlinc sees nullable, like W<String!>?
private fun KmType.coversClass(other: KmType): Boolean {
    if (flexibleTypeUpperBound == null || isNullable || isJavaArray != other.isJavaArray || isJavaRaw != other.isJavaRaw ||
        (other.flexibleTypeUpperBound != null && (classifier == other.classifier || other.isJavaCollection))
    ) {
        return false
    }
    val name = (classifier as? KmClassifier.Class)?.name ?: return false
    return classifier == other.classifier ||
        (isJavaCollection && (other.classifier as? KmClassifier.Class)?.name == MUTABLE_COLLECTION_TYPES[name])
}

/**
 * Whether this type has the same structure as the [other] one, as told by [structureKey], without building the keys,
 * comparing the same properties as [appendStructure] does, to be kept in line with it.
 */
private fun KmType.hasSameStructure(other: KmType): Boolean {
    if (this === other) return true
    if (classifier != other.classifier || isNullable != other.isNullable || isSuspend != other.isSuspend ||
        isDefinitelyNonNull != other.isDefinitelyNonNull ||
        (flexibleTypeUpperBound == null) != (other.flexibleTypeUpperBound == null) ||
        isJavaCollection != other.isJavaCollection || isJavaArray != other.isJavaArray || isJavaRaw != other.isJavaRaw ||
        arguments.size != other.arguments.size
    ) {
        return false
    }
    fun KmType?.isSame(otherType: KmType?) = if (this == null || otherType == null) this === otherType else hasSameStructure(otherType)
    return outerType.isSame(other.outerType) && arguments.indices.all { index ->
        arguments[index].variance == other.arguments[index].variance &&
            arguments[index].type.isSame(other.arguments[index].type)
    }
}

/**
 * The structure of this type, as a key, like `kotlin/Comparable<INVARIANT java/lang/Integer!>`,
 * telling the classifiers, the type arguments with their projections, whether suspend, the nullability, also definitely
 * not nullable, and the flexibility, but not the annotations of a function type, which do not tell its supertypes.
 */
private fun KmType.structureKey(): String = StringBuilder().also { appendStructure(it) }.toString()

// appends the structure of this type to the key, see structureKey
private fun KmType.appendStructure(key: StringBuilder) {
    outerType?.let { it.appendStructure(key); key.append('.') }
    when (val classifier = classifier) {
        is KmClassifier.Class -> key.append(classifier.name)
        is KmClassifier.TypeParameter -> key.append('#').append(classifier.id)
        is KmClassifier.TypeAlias -> key.append('=').append(classifier.name)
    }
    if (arguments.isNotEmpty()) {
        key.append('<')
        arguments.forEachIndexed { index, argument ->
            if (index > 0) key.append(',')
            val type = argument.type
            if (type == null) key.append('*') else { key.append(argument.variance).append(' '); type.appendStructure(key) }
        }
        key.append('>')
    }
    if (isSuspend) key.append('^')
    if (isNullable) key.append('?')
    if (isDefinitelyNonNull) key.append('&')
    if (flexibleTypeUpperBound != null) key.append('!')
    if (isJavaCollection) key.append('~')
    if (isJavaArray) key.append('#')
    if (isJavaRaw) key.append('%')
}

/**
 * Whether the [shadowedType] of a parameter of an extension on the type parameter of the [receiverTypeParameterId]
 * is the [type], where the type parameter stands for the class named [className], with the same nullability,
 * like `T` for `Int`, or `ClosedRange<T>` for `ClosedRange<Int>`, but not `T?` for `Int`,
 * while any other type parameter is never told to be the same type.
 */
private fun isSameType(shadowedType: KmType, type: KmType, receiverTypeParameterId: Int, className: String): Boolean {
    if (shadowedType.isNullable != type.isNullable) return false
    val classifier = type.classifier as? KmClassifier.Class ?: return false
    return when (val shadowedClassifier = shadowedType.classifier) {
        is KmClassifier.TypeParameter ->
            shadowedClassifier.id == receiverTypeParameterId && classifier.name == className && type.arguments.isEmpty()
        is KmClassifier.Class ->
            shadowedClassifier.name == classifier.name &&
                shadowedType.arguments.size == type.arguments.size &&
                shadowedType.arguments.zip(type.arguments).all { (shadowedArgument, argument) ->
                    val shadowedArgumentType = shadowedArgument.type
                    val argumentType = argument.type
                    shadowedArgument.variance == argument.variance && when {
                        shadowedArgumentType == null || argumentType == null -> shadowedArgumentType == argumentType
                        else -> isSameType(shadowedArgumentType, argumentType, receiverTypeParameterId, className)
                    }
                }
        is KmClassifier.TypeAlias -> false
    }
}

/**
 * The keys of the extension index under which an extension with the given receiver [type] is listed,
 * a single one, unless the receiver is a type parameter with multiple bounds.
 *
 * Extensions on a type parameter bounded by a class, like `fun <T : Comparable<T>> T.coerceAtLeast(…)`,
 * are keyed by the bound, like `kotlin.Comparable`, and with multiple bounds, like `where T : A, T : B`, by each of them,
 * to be listed only for the classes satisfying all of them, see [receiverBoundsOf].
 * Extensions on an unbounded type parameter, like `fun <T> T.let()`, are not listed for any class, as they would
 * flood every listing, so their key contains `[`, which cannot appear in a class name,
 * nor in a segment of its package (JVMS §4.2.2).
 * A suspend function type, like `suspend () -> Unit`, is stored in the metadata as `Function1<Continuation<Unit>, Any?>`,
 * so it is keyed as `kotlin.coroutines.SuspendFunction0` instead, not to be listed as an extension of `kotlin.Function1`.
 *
 * @param typeParameters the type parameters of the extension, declaring the bounds of a type parameter receiver.
 */
internal fun receiverIndexKeys(type: KmType, typeParameters: List<KmTypeParameter> = emptyList()): List<String> =
    when (type.classifier) {
        is KmClassifier.TypeParameter -> typeParameterReceiverKeys(type, typeParameters)
        else -> listOf(classReceiverIndexKey(type))
    }

/**
 * The key of a receiver [type] which is not a type parameter, see [receiverIndexKeys].
 */
private fun classReceiverIndexKey(type: KmType): String = when (val classifier = type.classifier) {
    is KmClassifier.Class -> type.takeIf { it.isSuspend }
        ?.decodeRuntimeFunctionType()
        ?.let { FunctionTypeKind.SUSPEND_FUNCTION.kotlinName(it.parameters.size) }
        ?: classifier.name.replace('/', '.')
    is KmClassifier.TypeAlias -> classifier.name.replace('/', '.')
    is KmClassifier.TypeParameter -> TYPE_PARAMETER_RECEIVER_KEY
}

/**
 * The keys of a type parameter receiver [type], the upper bounds of the type parameter, see [receiverIndexKeys].
 * A bound which is a type parameter itself, like `U` of `fun <U : CharSequence, T : U> T.foo()`,
 * stands for its own bounds, and, if it is unbounded, like `R` of `C : R`, constrains nothing.
 * `Any` constrains nothing either, unless it is the only bound, like of `val <T : Any> T.javaClass`,
 * listed then for `Any` itself.
 * The nullability of a bound does not matter, like of the receiver type of any other extension.
 */
private fun typeParameterReceiverKeys(type: KmType, typeParameters: List<KmTypeParameter>): List<String> {
    val typeParametersById = typeParameters.associateBy { it.id }
    val bounds = linkedSetOf<String>()
    val visited = mutableSetOf<Int>()
    fun collectBounds(id: Int) {
        if (!visited.add(id)) return
        for (bound in typeParametersById[id]?.upperBounds.orEmpty()) {
            val classifier = bound.classifier
            when {
                classifier is KmClassifier.TypeParameter -> collectBounds(classifier.id)
                // the implicit bound of any type parameter
                classifier is KmClassifier.Class && classifier.name == "kotlin/Any" && bound.isNullable -> Unit
                else -> bounds += classReceiverIndexKey(bound)
            }
        }
    }
    collectBounds((type.classifier as KmClassifier.TypeParameter).id)
    val constrainingBounds = bounds - ANY_RECEIVER_KEY
    return when {
        constrainingBounds.isNotEmpty() -> constrainingBounds.toList()
        bounds.isNotEmpty() -> listOf(ANY_RECEIVER_KEY)
        else -> listOf(TYPE_PARAMETER_RECEIVER_KEY)
    }
}

/**
 * The order of extension functions by name, and then by the declaring class.
 */
private val extensionFunctionOrder: Comparator<IndexedExtension> = compareBy({ it.function.name }, { it.sourceClass })

/**
 * The order of extension properties, like the [extensionFunctionOrder].
 */
private val extensionPropertyOrder: Comparator<IndexedExtensionProperty> =
    compareBy({ it.property.name }, { it.sourceClass })

/**
 * The extension index with each list sorted in the [extensionFunctionOrder],
 * so that the output does not depend on the classpath scan order, which differs between file systems.
 * The sort is stable, so overloads declared in one file keep their order in the Kotlin metadata,
 * fixed for a given binary.
 */
@JvmName("sortedExtensionIndex")
internal fun Map<String, List<IndexedExtension>>.sortedByNameAndSourceClass(): Map<String, List<IndexedExtension>> =
    mapValues { (_, extensions) -> extensions.sortedWith(extensionFunctionOrder) }

/**
 * The extension property index with each list sorted in the [extensionPropertyOrder].
 */
@JvmName("sortedExtensionPropertyIndex")
internal fun Map<String, List<IndexedExtensionProperty>>.sortedByNameAndSourceClass(): Map<String, List<IndexedExtensionProperty>> =
    mapValues { (_, properties) -> properties.sortedWith(extensionPropertyOrder) }

private const val TYPE_PARAMETER_RECEIVER_KEY = "[type parameter]"

/**
 * The source to import the top-level declarations of the file facade class named [binaryName] from,
 * shown in the `// from …` header: its Kotlin package, the [packageName] of its metadata,
 * when it differs from the JVM package, like of a facade compiled with `@JvmPackageName`,
 * or else its JVM package, or the facade class name for the default package, which has no name.
 */
private fun packageSourceOf(binaryName: String, packageName: String): String =
    kotlinPackageOf(binaryName, packageName).ifEmpty { binaryName }

/**
 * The Kotlin package of the file facade class named [binaryName], the [packageName] of its metadata,
 * when it differs from the JVM package, see [packageSourceOf], or else its JVM package, empty for the default package.
 */
private fun kotlinPackageOf(binaryName: String, packageName: String): String =
    packageName.ifEmpty { binaryName.substringBeforeLast('.', "") }

private const val ANY_RECEIVER_KEY = "kotlin.Any"

private const val COMPARABLE_CLASS_NAME = "kotlin.Comparable"

/**
 * Whether the class, or the package, of this [name] is of the `kotlin.*` or `java.*` packages,
 * whose extensions are not listed for the classes of the other packages, see [DefaultKotlinIntrospector.userSupertypes].
 */
private fun isOfStandardPackage(name: String): Boolean = "$name.".let { it.startsWith("kotlin.") || it.startsWith("java.") }

// the number of distinct types of a supertype reached through different paths which are told and walked, see supertypeNames,
// as their number can double at each level of a hierarchy, like through Kotlin and through Java, 2^6 of 6 such levels,
// the ones reached first, which the class is matched against, an extension matching only the other ones not listed,
// but the ones covering others, which replace them, see coversType,
// and the ones without platform type arguments, which replace the ones with them, see keptSupertypes
private const val MAX_SUPERTYPE_PATHS = 64

// the classes given for the type parameters of a generic class, see instantiated, the first one for most of the bounds,
// like T : CharSequence, T : Comparable<T>, or T : Any, the other one for T : Number
private val INSTANTIATING_CLASS_NAMES = listOf("kotlin/String", "kotlin/Int")

// the most times an extension is matched again, choosing other supertypes reached through different paths,
// see matchesThroughAnyPaths, as their combinations multiply, an extension matching only a later one not listed
private const val MAX_PATH_CHOICE_MATCHES = 256

/**
 * The names of the members of `kotlin.Any` whose overrides the compiler reports no hidden deprecation of,
 * like `@Deprecated(level = HIDDEN) override fun toString()`, which can still be called,
 * while it does report the other levels, and the hidden one of an override of `equals`.
 */
private val UNREPORTED_HIDDEN_ANY_MEMBERS = setOf("toString", "hashCode")

/**
 * Whether the function, of a class, overrides one of the [UNREPORTED_HIDDEN_ANY_MEMBERS], told by its Kotlin signature,
 * taking nothing, as the JVM signature of `toString()` is mangled in a value class, like `toString-impl(Ljava/lang/String;)`,
 * which cannot override `hashCode()`,
 * while a top-level function of the same JVM signature, of a file facade, overrides nothing.
 */
@OptIn(ExperimentalContextParameters::class)
private val KmFunction.isUnreportedHiddenAnyMember: Boolean
    get() = name in UNREPORTED_HIDDEN_ANY_MEMBERS && valueParameters.isEmpty() && contextParameters.isEmpty() &&
        receiverParameterType == null && typeParameters.isEmpty()

/**
 * The message Kotlin reports the use of a Java member annotated with `@java.lang.Deprecated` with.
 */
private const val JAVA_DEPRECATION_MESSAGE = "Deprecated in Java"

/**
 * The supertypes which Kotlin/JVM sees for the JVM classes of the built-in types, see `jvmSupertypeNamesOfBuiltins`,
 * by their names, with their JVM classes.
 */
private val JVM_SUPERTYPES_OF_BUILTINS = listOf(
    "java.io.Serializable" to java.io.Serializable::class.java,
    "kotlin.Cloneable" to java.lang.Cloneable::class.java
)

/**
 * Whether the extensions declared in the class of the JVM binary name [sourceClass], imported from the [source],
 * the Kotlin package, or the object, declaring them, of the Kotlin module named [moduleName] in its metadata,
 * are shown with the built-in types:
 * only the ones of the public `kotlin.*` packages, not the ones of the internal packages, like `kotlin.jvm.internal`,
 * told by the Kotlin package, not by the JVM one, which a file facade relocates with `@JvmPackageName`,
 * while the JVM package is in `kotlin.*` too, like the one of `@JvmPackageName("kotlin.collections.jdk8")`,
 * unlike the one of the standard library relocated by shading, like `shaded.kotlin.text`,
 * not to list its extensions twice,
 * and only the ones of the modules of the standard library, or of kotlin-reflect, see [builtinExtensionModules],
 * whose extensions, like `KClass.memberFunctions` of `kotlin.reflect.full`,
 * can be called by the user code depending on it, even if some of them, like `KClass.cast()`,
 * have the same signature as the ones of the standard library, imported from another package,
 * but not the ones of the other libraries declaring `kotlin.*` packages, like kotlin-test, kotlin-scripting,
 * or kotlin-metadata-jvm, whose `String.isLocalClassName()` would read like a member of `String`,
 * told apart by the Kotlin module declaring them, which is kept also when merged into one jar, like a shadow jar.
 */
internal fun isBuiltinExtensionSource(sourceClass: String, source: String, moduleName: String?): Boolean =
    sourceClass.startsWith("kotlin.") && (source == "kotlin" || source.startsWith("kotlin.")) &&
        !isInternalPackageSource(source) && moduleName in builtinExtensionModules

/**
 * Whether the [source], the Kotlin package, or the object, declaring extensions, is an internal package,
 * like `kotlin.reflect.jvm.internal`, or `kotlinx.serialization.internal`, not meant to be used by the user code.
 */
private fun isInternalPackageSource(source: String): Boolean = source.split('.').contains("internal")

/**
 * The Kotlin modules whose extensions are shown with the built-in types, see [isBuiltinExtensionSource]:
 * the standard library, with `kotlin-stdlib-jdk7` and `kotlin-stdlib-jdk8`,
 * separate jars before Kotlin 1.8, merged into it since, still declaring their modules,
 * and `kotlin-stdlib-jre7` and `kotlin-stdlib-jre8`, which they replaced in Kotlin 1.2,
 * and kotlin-reflect, `kotlin-reflection`, named `kotlin-reflect-api` from Kotlin 1.2.40 to 1.3.11.
 */
private val builtinExtensionModules = setOf(
    "kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8", "kotlin-stdlib-jre7", "kotlin-stdlib-jre8",
    "kotlin-reflection", "kotlin-reflect-api"
)

/**
 * The Kotlin name of the function type class compiled to the JVM interface named [className],
 * like `kotlin.Function1` of `kotlin.jvm.functions.Function1`, or `null` if it is not one.
 */
private fun jvmFunctionTypeName(className: String): String? =
    functionTypeOfJvmInterface(className.replace('.', '/'))?.replace('/', '.')

/**
 * The names of the function type classes found by the [KotlinIntrospector.search], the ones compiled to
 * their own JVM interfaces, like `kotlin.Function22`, and `kotlin.coroutines.SuspendFunction21`,
 * whose continuation is the last runtime parameter of `kotlin.jvm.functions.Function22`,
 * while the higher ones, compiled to `kotlin.jvm.functions.FunctionN`, can still be resolved,
 * and the function reference types of the same arities, like `kotlin.reflect.KFunction22`.
 */
internal val searchableFunctionTypeNames: List<String> = FunctionTypeKind.entries.flatMap { kind ->
    val maxArity = if (kind.isSuspend) MAX_JVM_FUNCTION_INTERFACE_ARITY - 1 else MAX_JVM_FUNCTION_INTERFACE_ARITY
    (0..maxArity).map { kind.kotlinName(it) }
}

/**
 * This class index, by the lowercase simple names, with the [searchableFunctionTypeNames] as well,
 * as the compiler synthesizes the function type classes, there are no class files of them to scan.
 */
private fun Map<String, List<String>>.withFunctionTypeClasses(): Map<String, List<String>> {
    val index = mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
    searchableFunctionTypeNames.forEach { kotlinName ->
        index.getOrPut(kotlinName.substringAfterLast('.').lowercase()) { mutableListOf() }.add(kotlinName)
    }
    return index
}

/**
 * The bounds a class has to be a subtype of, all of them, for an extension under the given [receiverIndexKeys]
 * to be listed for it, the multiple bounds of a type parameter receiver, like `where T : A, T : B`.
 * An extension under a single key is listed for any class looking it up.
 */
internal fun receiverBoundsOf(receiverIndexKeys: List<String>): Set<String> =
    if (receiverIndexKeys.size > 1) receiverIndexKeys.toSet() else emptySet()

/**
 * The legacy context receivers of a class, see [DefaultKotlinIntrospector.formatClassContextReceivers],
 * the only read of the deprecated [KmClass.contextReceiverTypes], which has no replacement,
 * as a class cannot declare context parameters, so that a kotlin-metadata-jvm upgrade removing it breaks only this place.
 * Mutable, so that the tests can add them to the metadata of a class, which the current compiler no longer compiles.
 */
@OptIn(ExperimentalContextReceivers::class)
@Suppress("DEPRECATION")
internal val KmClass.legacyContextReceiverTypes: MutableList<KmType>
    get() = contextReceiverTypes

/**
 * Whether this function is shaped like an accessor of a synthetic property, see `syntheticPropertyOf`:
 * neither an extension, nor generic, nor of context parameters, taking [parameterCount] value parameters, none a vararg.
 */
@OptIn(ExperimentalContextParameters::class)
private fun KmFunction.isAccessorShaped(parameterCount: Int): Boolean =
    receiverParameterType == null && typeParameters.isEmpty() && contextParameters.isEmpty() &&
        valueParameters.size == parameterCount && valueParameters.all { it.varargElementType == null }

/**
 * Whether this type is `kotlin.Unit`, returned by a Java `void` method.
 */
private val KmType.isUnit: Boolean get() = (classifier as? KmClassifier.Class)?.name == UNIT_CLASS_NAME

private const val UNIT_CLASS_NAME = "kotlin/Unit"

/**
 * Whether this type is the [other] one, of the same classifier and type arguments, regardless of the nullability of either,
 * like `Boolean` and `Boolean?`, see `syntheticPropertyOf`.
 */
private fun KmType.isSameIgnoringNullability(other: KmType): Boolean =
    classifier == other.classifier && arguments.size == other.arguments.size &&
        arguments.zip(other.arguments).all { (argument, otherArgument) ->
            val type = argument.type
            val otherType = otherArgument.type
            argument.variance == otherArgument.variance &&
                if (type == null || otherType == null) type == otherType else type.isSameIgnoringNullability(otherType)
        }
