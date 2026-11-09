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
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.Type as AsmType
import java.io.File
import java.io.IOException
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
    val companionDeprecation: Deprecation? = null
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
    val companionDeprecation: Deprecation? = null
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

        private const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

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
     * @param aliasedClass the class the last of the [typeAliases] expands to, see [enclosingContext].
     */
    private fun resolve(name: String, typeAliases: Set<String> = emptySet(), aliasedClass: String? = null): Resolved<*>? {
        val parts = unescapeQualifiedName(name).split(".")

        // Try to find where the class name ends and member name begins
        // by progressively trying to load the class
        for (i in parts.size downTo 1) {
            val className = parts.take(i).joinToString(".")
            val memberName = if (i < parts.size) parts.drop(i).joinToString(".") else null

            // a function type class, synthesized by the compiler, like kotlin.Function1
            FunctionTypeClass.of(className)?.let { functionTypeClass ->
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
                    resolveMember(kmClass, apiClass, className, declarations, memberName, isBuiltin = true)
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
                        resolveMember(kmClass, apiClass, className, foundClass, memberName, aliasedClass = aliasedClass)
                    } else {
                        formatClass(kmClass, apiClass, className, foundClass, aliasedClass = aliasedClass)
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
     * following the declaration of the typealias, when deprecated, telling that it cannot be used as is,
     * unless with type arguments, like `typealias Names = List<String>`, not substituted in the members of the class,
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
            val expanded = typeAlias.expandedType
            if (expanded.arguments.isNotEmpty() || expanded.isNullable) return null
            val className = (expanded.classifier as? KmClassifier.Class)?.name ?: return null
            val member = resolve(
                "${escapeQualifiedName(className.replace('/', '.'))}.${escapeQualifiedName(memberName)}", typeAliases + name,
                aliasedClass = className
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
     *
     * @param areFunctionsReflected whether kotlin-reflect tells the functions of the class,
     *   as it might fail on all of them at once, see [reflectedJavaClass].
     */
    private inner class JavaClass(
        override val clazz: Class<*>,
        override val kmClass: KmClass,
        private val functions: Map<KmFunction, JavaFunction>,
        private val properties: Map<KmProperty, JavaProperty>,
        val areFunctionsReflected: Boolean = true
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
         * like `E!`, of its value parameters, see [JavaFunction.notNullable].
         */
        fun overriddenMembers(member: Member): Set<String> {
            val overridden = member.overridden(overrideResolver, overrideOwner)
            if (member !is Member.Function) return overridden
            val notNullable = functions[member.declaration]?.notNullable ?: return overridden
            return overridden + overrideResolver.overriddenMembers(overrideOwner, notNullable)
        }

        /**
         * The JVM method of the [function], telling its overrides, see [inheritedMembers].
         */
        fun jvmMethodOf(function: KmFunction): JvmMethod? = jvmMethodOf(functions[function]?.method)

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
     * so that its overrides are matched also with these parameters [notNullable], see [withNotNullableParameters].
     */
    private class JavaFunction(val kFunction: KFunction<*>?, private val javaMethod: Method?, val notNullable: KmFunction) {

        /**
         * The Java method of the function, the one it is converted from, see [toKmJavaFunction],
         * or the one of the [kFunction], or `null` if it cannot be reflected.
         */
        val method: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
            javaMethod ?: reflectedValue(default = null) { kFunction?.javaMethod }
        }

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
        generateSequence(inheriting.superclass) { it.superclass }.takeWhile { it != declaring }.firstNotNullOfOrNull { superclass ->
            superclass.declaredMethods.firstOrNull { it.overrides() }
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
    private fun jvmMethodOf(method: Method?): JvmMethod? = reflectedValue(default = null) {
        method?.let {
            val descriptor = AsmType.getMethodDescriptor(it)
            JvmMethod(javaMemberKey(it.declaringClass.name, it.name, descriptor), JvmMethodSignature(it.name, descriptor))
        }
    }

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
        // a Java class described as a built-in type, like java.util.ListIterator as ListIterator
        val isBuiltin = reflectedValue(default = false) { kClass.qualifiedName != clazz.canonicalName }
        val isJavaViewed = isBuiltin && clazz.name in JAVA_VIEWED_BUILTIN_INTERFACES
        val kmClass = kClass.toKmJavaClassHeader(kotlinClassName(clazz), supertypeName = ::javaSupertypeMetadataName)
        val typeParameters = ReflectedTypeParameters.classTypeParameters(kmClass.typeParameters)
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
                    functions[kmFunction] = JavaFunction(function, javaMethod = null, kmFunction.withNotNullableParameters())
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
            ReflectedTypeParameters(clazz.typeParameters.map { it.name }.zip(kmClass.typeParameters.map { it.id }).toMap())
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
                    functions[kmFunction] = JavaFunction(kFunction = null, method, kmFunction.withNotNullableParameters())
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
        JavaClass(clazz, kmClass, functions, properties, areFunctionsReflected = reflectedFunctions != null)
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
     * see [ClassFile.hasAnnotationsInDefaultImpls].
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
            } else emptySet()
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
     */
    private fun resolveMember(
        kmClass: KmClass,
        apiClass: ApiClass,
        className: String,
        declarations: ClassDeclarations,
        memberName: String,
        isBuiltin: Boolean = false,
        aliasedClass: String? = null
    ): MemberListing? {
        val contextPackage = contextPackageOf(declarations, kmClass)
        // like in the listing of the class, also the extensions a built-in type lists in its body, with their receivers,
        // only the ones of the name, which alone can hide its JVM-only members of the name, see jvmOnlyFunctionsNotHidden
        // the deprecation of a companion object, reported for its extensions called on its name, like Foo.Companion.of(),
        // is told before them when resolved directly, like the one of any class, see enclosingContext
        val listedExtensions = listedExtensions(kmClass, className, apiClass, declarations, isBuiltin, memberName)
        // also the JVM-only members hidden by an extension, which are not listed, but still override the inherited ones
        val apiMembers = apiMembersNamed(kmClass, declarations, memberName, apiClass)
        val hiddenJvmOnlyFunctions = (declarations as? BuiltinClass)?.jvmOnlyFunctions.orEmpty() -
            jvmOnlyFunctionsNotHidden(kmClass, declarations, listedExtensions.functions).toSet()
        val members = declaredMembers(
            kmClass, declarations,
            apiMembers.filter { member -> (member as? Member.Function)?.function !in hiddenJvmOnlyFunctions },
            apiClass, contextPackage
        )

        val companionMembers = apiCompanionOf(kmClass, declarations, apiClass)?.let { companion ->
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
        val inherited = inheritedMembers(
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
        val companionSupertypeExtensions = if (isBuiltin) null else companionSupertypeExtensions(
            kmClass, className, apiClass, declarations, memberName
        )
        val companionFunctions = companionSupertypeExtensions?.functions.orEmpty()
        val companionProperties = companionSupertypeExtensions?.properties.orEmpty()
        val companionFunctionDeclarations = companionFunctions.mapTo(HashSet()) { it.function }
        val companionPropertyDeclarations = companionProperties.mapTo(HashSet()) { it.property }
        val extensions = extensionSignatures(
            (listedExtensions.functions + hidingExtensions).distinct().filter { it.function !in companionFunctionDeclarations } +
                companionFunctions,
            listedExtensions.properties.filter { it.property !in companionPropertyDeclarations } + companionProperties,
            contextPackage
        ) { extension, receiver ->
            // told by their declarations, the very instances of the index, as two receivers may look the same, like T
            val isOnCompanion = when (extension) {
                is IndexedExtension -> extension.function in companionFunctionDeclarations
                is IndexedExtensionProperty -> extension.property in companionPropertyDeclarations
            }
            // the ones on the companion object itself are listed for the class only, when it extends Any only
            isObject || isOnCompanion ||
                (companionName != null && (receiver?.classifier as? KmClassifier.Class)?.name == companionName)
        }
        return MemberListing.of(
            members.map { ListingItem.Declaration(it) } + inherited + companionMembers + extensions,
            context = enclosingContext(kmClass, apiClass, declarations, includingClass = true, isBuiltin, aliasedClass),
            areMembersCalledOnClassName = isObject
        )
    }

    /**
     * The extensions named [memberName] on the supertypes of the companion object of the [kmClass], named [className],
     * called on the name of the class too, like `Foo.ext()` of `fun Bar.ext()` for `companion object : Bar`,
     * which its listing leaves out, see [extendedCompanionOf], as listed for the companion object, see [listedExtensions],
     * also the ones listed for the class too, like on the companion object itself, or on a supertype of both,
     * telling the deprecation of the companion object, which Kotlin reports for them, like for `Foo.ext()`,
     * or `null` if the class has no companion object which is part of the API, or one extending `Any` only,
     * whose extensions on itself are listed for the class, see [listedExtensions],
     * or one which only subclasses can use, as it, or a class enclosing it, is protected,
     * whose extensions are called on the name of the class only within the subclasses.
     *
     * @param apiClass the [kmClass] as a class of the API.
     */
    private fun companionSupertypeExtensions(
        kmClass: KmClass,
        className: String,
        apiClass: ApiClass,
        declarations: ClassDeclarations,
        memberName: String
    ): ListedExtensions? {
        val companion = apiCompanionOf(kmClass, declarations, apiClass) ?: return null
        if (companion.apiClass.scopes.any { it.visibility == Visibility.PROTECTED }) return null
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
         * see [OverrideResolver.overriddenMembers].
         */
        fun overridden(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?): Set<String>

        /**
         * Whether this member of the [owner] is an override, see [OverrideResolver.isOverride].
         */
        fun isOverride(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?): Boolean

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
         * @param declaration the [property] as declared, unless it is rendered with substituted type arguments.
         */
        class Property(val property: KmProperty, val declaration: KmProperty = property) : Member {

            override val returnType: KmType get() = property.returnType

            override val visibility: Visibility get() = property.visibility

            override val modality: Modality get() = property.modality

            override fun withModality(modality: Modality): Member = Property(property.withModality(modality), declaration)

            override fun key(owner: KmClass): String = memberKey(owner, declaration)

            override fun overridden(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?): Set<String> =
                owner?.let { overrideResolver.overriddenMembers(it, declaration) }.orEmpty()

            override fun isOverride(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?): Boolean =
                owner != null && overrideResolver.isOverride(owner, declaration)

            override fun signature(classTypeParameters: Map<Int, String>): String =
                memberSignature(property, classTypeParameters + positionalNames(property.typeParameters))

            override fun substituted(substitution: Map<Int, KmType>, names: Map<Int, String>): Member =
                Property(property.substituted(substitution, names), declaration)

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

            override fun overridden(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?): Set<String> =
                owner?.let { overrideResolver.overriddenMembers(it, declaration) }.orEmpty()

            override fun isOverride(overrideResolver: OverrideResolver, owner: OverrideResolver.Owner?): Boolean =
                owner != null && overrideResolver.isOverride(owner, declaration)

            override fun signature(classTypeParameters: Map<Int, String>): String =
                memberSignature(function, classTypeParameters + positionalNames(function.typeParameters))

            override fun substituted(substitution: Map<Int, KmType>, names: Map<Int, String>): Member =
                Function(function.substituted(substitution, names), declaration)

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
        isOverridingNothing: Boolean = false
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
            is Member.Function -> listOf(
                formatFunction(
                    member.function, inInterface = isInterface, owner = owner, contextPackage = contextPackage,
                    classTypeParameters = typeParameters,
                    deprecation = declarations.deprecation(member.declaration),
                    isOwnerOpenForOverrides = isOwnerOpenForOverrides, isInheritedByFinalClass = isInheritedByFinalClass,
                    declaration = member.declaration,
                    isJvmOnlyOverride = isJvmOnlyOverride,
                    isOverridingNothing = isOverridingNothing
                )
            )
        }
    }

    /**
     * The JVM method of a JVM-only member of a built-in type, see [BuiltinClass.jvmMethodOf], or of a member of a Java class, see [JavaClass.jvmMethodOf]:
     * its [key], see [javaMemberKey], like `java.lang.Throwable#printStackTrace(Ljava/io/PrintStream;)V`,
     * matched with the Java members a function overrides, see [OverrideResolver.overriddenJavaMembers],
     * and its [signature], like `printStackTrace(Ljava/io/PrintStream;)V`, the one of a function overriding it on the JVM.
     */
    private class JvmMethod(val key: String, val signature: JvmMethodSignature)

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
     * A [member] named like the one looked up, declared by a supertype, the [kmClass] of the [declarations],
     * which is inherited, unless a member of another supertype overrides it, see [inheritedMembers],
     * rendered with the type arguments of the inheriting class substituted, see [Member.substituted].
     *
     * @param key the key of the member, see [memberKey].
     * @param overridden the keys of the members of the supertypes of the [kmClass] which the [member] overrides.
     * @param signature the signature of the [member] as rendered, see [Member.signature],
     *   the same for the members of the same signature of unrelated supertypes.
     * @param notNullableSignature the [signature] of the [member] with its value parameters not nullable, see [withNotNullableParameters],
     *   like the platform types of Java, like the `K!` of `computeIfAbsent` of a Java subclass of `java.util.HashMap<K, V>`,
     *   of the same signature for the compiler as a Kotlin member taking `K`, or `K?`, also of mixed nullability,
     *   like `take(first: String, second: String?)` and the `take(String, String)` of Java, see [isMergedWith],
     *   and of a member of a Java class with its redundant projections invariant, see [withoutRedundantProjections],
     *   like the `Collection<out Int>` of a `Collection<? extends Integer>`, the `Collection<Int>` of a Kotlin member,
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
        val kmClass: KmClass,
        val declarations: ClassDeclarations,
        val member: Member,
        val key: String,
        overridden: Set<String>,
        val signature: String,
        notNullableSignature: Lazy<String>,
        val typeParameters: TypeParameterScope,
        val isHidden: Boolean,
        val jvmMethod: JvmMethod?,
        val isApi: Boolean
    ) {

        val notNullableSignature: String by notNullableSignature

        /**
         * The keys of the members the [member] overrides, but the ones of the Kotlin members a Java method does not override
         * on the JVM, see [takesReferenceForPrimitiveOf], dropped once the candidates are collected, see [inheritedCandidates].
         */
        var overridden: Set<String> = overridden

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
         * Whether the [member], of a Java class, overrides a JVM-only member of a built-in type on the JVM,
         * like the `addFirst(String)` of a Java subclass of `java.util.AbstractList<String>`, through the bridge javac generates,
         * the one of `java.util.List` since JDK 21, see [BuiltinClass.jvmOnlyFunctions], which K2 sees as an override,
         * though the override resolver does not tell, see [inheritedCandidates].
         */
        var isJvmOnlyOverride: Boolean = false

        /**
         * Whether the [member], of a Java class, overrides none of the Kotlin members the override resolver tells,
         * like the `put(@NotNull Integer)` of a Java class the `put(key: Int)` of a Kotlin interface,
         * which the `put(int)` of its Java superclass implements, see [takesReferenceForPrimitiveOf],
         * nor a Java method, see [overridesJavaMember], like the `put(int, Integer)` of a Java class
         * the `put(Integer, Integer)` of its Java interface, see [overridesJavaMemberOnlyByKotlinTypes].
         */
        var isOverridingNothing: Boolean = false

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
            return overrideResolver.overriddenJavaMembers(owner, function).any { key ->
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
            val parameters = (member as? Member.Function)?.function?.valueParameters ?: return false
            val overriddenParameters = (overridden.member as? Member.Function)?.function?.valueParameters ?: return false
            return parameters.size == overriddenParameters.size && parameters.indices.any { index ->
                parameters[index].type.isNullable && !overriddenParameters[index].type.isNullable
            }
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
         * see [notNullableSignature], when one of them is a member of a Java class, taking the platform types of Java,
         * as two Kotlin members differing by it clash on the JVM,
         * unless their value parameters are of JVM types the compiler keeps apart, see [isMergedJvmParameter],
         * or overriding the same member, declared by another supertype,
         * as the members of the same supertype are its overloads.
         */
        fun isMergedWith(other: InheritedCandidate): Boolean =
            other.kmClass.name != kmClass.name && (other.member is Member.Property) == (member is Member.Property) &&
                (other.overridden.any { it in overridden } ||
                    (other.signature == signature || isJavaRivalry(other) && other.notNullableSignature == notNullableSignature) &&
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
        // and so is a member standing for the method of the Java class implementing it, see implementingJvmMethod
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
         * Whether the [member], of a Java class, takes a reference type, like `Integer`, where the [member] of the [kotlin] candidate,
         * a Kotlin one, which the override resolver matches regardless of the nullability, takes a primitive type on the JVM,
         * like the `put(key: Int)` of a Kotlin interface, which it does not override then, as the `put(int)` declared next to it does.
         */
        fun takesReferenceForPrimitiveOf(kotlin: InheritedCandidate): Boolean {
            val parameters = declaredJvmParameters ?: return false
            val kotlinParameters = kotlin.declaredJvmParameters ?: return false
            return parameters.size == kotlinParameters.size && parameters.indices.any { index ->
                val parameter = parameters[index]
                val kotlinParameter = kotlinParameters[index]
                parameter != null && kotlinParameter != null && kotlinParameter.type.isPrimitive && !parameter.type.isPrimitive
            }
        }

        /**
         * Whether the [member] overrides the one of the [other] candidate, telling it by its key,
         * or by the key of its JVM method, see [OverrideResolver.overriddenJavaMembers],
         * like the `newThread(r: Runnable)` of a Kotlin interface the `newThread(r: Runnable?)` of `java.util.concurrent.ThreadFactory`,
         * or by its JVM signature, which overrides it on the JVM, like `apply(t: String)` the `apply(s: String?)` of a Java subtype
         * of `java.util.function.Function<String, String>`.
         */
        fun overrides(other: InheritedCandidate): Boolean =
            other.key in overridden || other.jvmMethod?.let { jvmMethod ->
                jvmMethod.key in overriddenJavaMembers ||
                    jvmMethod.signature == (member as? Member.Function)?.declaration?.signature
            } == true

        /**
         * Whether the [member] overrides a Java method, see [OverrideResolver.overriddenJavaMembers],
         * like the `put(Integer)` of a Java class the one of its Java interface, of the same primitive JVM types, see [javaDescriptor].
         */
        val overridesJavaMember: Boolean get() = overriddenJavaMembers.isNotEmpty()

        /**
         * Whether the override resolver matches the [member], of a Java class, with Java methods only regardless of the primitive types,
         * like the `put(int, Integer)` of a Java class with the `put(Integer, Integer)` of its Java interface, which it does not override.
         */
        val overridesJavaMemberOnlyByKotlinTypes: Boolean
            get() = javaDescriptor?.let { descriptor -> AsmType.getArgumentTypes(descriptor).any { it.isPrimitive } } == true &&
                overriddenJavaMembers.isEmpty() && overriddenJavaMembersByKotlinTypes.isNotEmpty()

        // the JVM descriptor of the member of a Java class, telling its primitive types, see OverrideResolver.overriddenJavaMembers
        private val javaDescriptor: String? get() = jvmMethod?.signature?.descriptor?.takeIf { declarations is JavaClass }

        // the keys of the JVM methods the member overrides, see overrides
        private val overriddenJavaMembers: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
            val function = (member as? Member.Function)?.declaration
            val owner = declarations.overrideOwner
            if (function == null || owner == null) emptySet() else overrideResolver.overriddenJavaMembers(owner, function, javaDescriptor)
        }

        // the keys of the JVM methods the override resolver matches by the Kotlin types of the member,
        // see overridesJavaMemberOnlyByKotlinTypes
        private val overriddenJavaMembersByKotlinTypes: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
            val function = (member as? Member.Function)?.declaration
            val owner = declarations.overrideOwner
            if (function == null || owner == null) emptySet() else overrideResolver.overriddenJavaMembers(owner, function)
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
                    isOverridingNothing = candidate.isOverridingNothing
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
            ?: return candidate.kmClass.name.replace('/', '.')
        val nearest = reflectedValue(default = null) {
            generateSequence(superclass.value) { it.superclass }
                .takeWhile { it != declaring }
                .lastOrNull { it.isNameable() && declaring.isAssignableFrom(it) }
        }
        return (nearest?.let { kotlinClassName(it) } ?: candidate.kmClass.name).replace('/', '.')
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
        fun collect(name: String, supertype: KmType) {
            val foundName = inheritedName(declarations, name)
            val superDeclarations = inheritedDeclarations(foundName)
            val superKmClass = superDeclarations?.kmClass
            if (superDeclarations == null || superKmClass == null) {
                // named by the names the metadata refers to them by, like the supertypes of a Kotlin class,
                // so that a class reached also from a Kotlin supertype is not walked again under its relocated name
                val javaSupertypes = rawJavaSupertypes(foundName) { jvmName -> unlocatedName(declarations, jvmName) }
                javaClasses += name
                directSupertypes[name] = javaSupertypes.classNames()
                pending += javaSupertypes
                return
            }
            val substitution = supertypeSubstitution(superKmClass, supertype)
            // the supertypes of a Java class, described by kotlin-reflect by their JVM names, which might be relocated,
            // are named by the names the metadata refers to them by, like the ones of the raw Java supertypes above,
            // and so are their type arguments, substituted for the type parameters of a Kotlin supertype
            val superSupertypes = if (superDeclarations !is JavaClass) superKmClass.supertypes else {
                superKmClass.supertypes.map { it.unlocated(declarations) }
            }
            directSupertypes[name] = superSupertypes.classNames()
            pending += superSupertypes.map { it.substituted(substitution) }
            // the supertypes of a class reached through a raw supertype are raw too, like the raw ones of a Java class
            if (superDeclarations is JavaClass) {
                val rawSupertypeNames = superDeclarations.rawSupertypeNames.mapTo(HashSet()) { unlocatedName(declarations, it) }
                superSupertypes.classNames().filterTo(rawlyReached) { name in rawlyReached || it in rawSupertypeNames }
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
            apiMembers.forEach { apiMember ->
                val declared = apiMember.member
                val overridden = (superDeclarations as? JavaClass)?.overriddenMembers(declared)
                    ?: declared.overridden(overrideResolver, superDeclarations.overrideOwner)
                val erased = if (!isReachedRaw || declared !is Member.Function) null else {
                    (superDeclarations as JavaClass).erasedFunctionOf(declared.declaration)
                }
                val member = (erased?.let { Member.Function(it, (declared as Member.Function).declaration) } ?: declared)
                    .substituted(substitution, inheritingNames)
                val signature = member.signature(inheritingNames)
                candidates += InheritedCandidate(
                    superKmClass, superDeclarations, member, declared.key(superKmClass), overridden,
                    signature = signature,
                    notNullableSignature = lazy(LazyThreadSafetyMode.NONE) {
                        val function = (erased ?: (declared as? Member.Function)?.declaration)?.withNotNullableParameters()
                        // like the Collection<out Int> of a Collection<? extends Integer> of Java
                        val invariant = if (superDeclarations !is JavaClass) function else {
                            function?.withValueParameterTypes { type -> type.withoutRedundantProjections() }
                        }
                        invariant?.let { Member.Function(it).substituted(substitution, inheritingNames).signature(inheritingNames) }
                            ?: signature
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
        // a Java method taking a boxed type, like put(Integer), never overrides a Kotlin member taking a primitive type there,
        // like put(key: Int), which the override resolver matches regardless of the nullability, see takesReferenceForPrimitiveOf
        val candidatesByKey by lazy(LazyThreadSafetyMode.NONE) { candidates.groupBy { it.key } }
        // and neither does a Java method a Java one of other primitive types, like put(int, Integer) the put(Integer, Integer)
        // of a Java interface, which the override resolver matches by the Kotlin types, see overridesJavaMemberOnlyByKotlinTypes
        candidates.forEach { candidate ->
            if (candidate.declarations !is JavaClass) return@forEach
            if (candidate.overridden.isEmpty()) {
                if (candidate.overridesJavaMemberOnlyByKotlinTypes) candidate.isOverridingNothing = true
                return@forEach
            }
            val overridden = candidate.overridden.filterTo(HashSet()) { key ->
                candidatesByKey[key].orEmpty().none { candidate.takesReferenceForPrimitiveOf(it) }
            }
            if (overridden.size != candidate.overridden.size) {
                candidate.overridden = overridden
                candidate.isOverridingNothing = overridden.isEmpty() && !candidate.overridesJavaMember
            }
        }
        // the members of a JDK class reached only through a Java class of the user code lose the enhancement of their return types,
        // like the get() of java.util.function.Supplier for a subclass of a Java class implementing it without declaring it again,
        // unless reached also directly, or through the JDK, or a Kotlin class not overriding them, whatever the order of the supertypes
        if (userJavaClasses.isNotEmpty()) {
            val candidatesByName = candidates.groupBy { it.kmClass.name }
            candidates.filter { it.declarations is JavaClass && it.kmClass.name !in userJavaClasses }.forEach { candidate ->
                val keepingEnhancement = HashSet<String>()
                val reached = ArrayDeque(directSupertypes[kmClass.name].orEmpty())
                while (reached.isNotEmpty()) {
                    val name = reached.removeFirst()
                    if (!keepingEnhancement.add(name) || name in userJavaClasses) continue
                    if (candidatesByName[name].orEmpty().none { it !== candidate && it.overrides(candidate) }) {
                        reached += directSupertypes[name].orEmpty()
                    }
                }
                candidate.isEnhancementLost = candidate.kmClass.name !in keepingEnhancement
            }
        }
        // the supertypes of the return types compared, see compareSpecificity, walked once per class
        val supertypeNames = HashMap<String, Set<String>>()
        val supertypeNamesOf = { name: String ->
            supertypeNames.getOrPut(name) { supertypeNames(name, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) }) }
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
        // of `abstract class AbstractMutableList<E> : java.util.AbstractList<E>(), MutableList<E>`,
        // whose members the compiler merges with the Java methods overriding them, keeping the Kotlin types there,
        // though not for every such class, like add(null) of `abstract class X : java.util.AbstractList<String>(), MutableList<String>`,
        // which compiles, unlike set(0, null), so that the Kotlin members of such a supertype stay listed, as they were,
        // unlike the ones reached through a Java class only, which takes them as platform types, see acceptsNullUnlike
        val reachedThroughKotlin by lazy(LazyThreadSafetyMode.NONE) {
            val reached = HashSet<String>()
            val pending = ArrayDeque(if (declarations is JavaClass) emptyList() else directSupertypes[kmClass.name].orEmpty())
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                if (name !in javaClasses && reached.add(name)) pending += directSupertypes[name].orEmpty()
            }
            reached
        }
        // the Java candidates overridden by a Kotlin class below, see overridingBelow, which still override on the JVM
        // the JVM-only members they override, see javaMethodSignatures below
        val overriddenBelow = ArrayList<InheritedCandidate>()
        candidates.removeAll { candidate ->
            val javaClass = candidate.declarations as? JavaClass ?: return@removeAll false
            val overriddenKotlin = kotlinCandidates.filter { it.key in candidate.overridden }
            // the Java method K2 takes as the member, of a platform type which the overridden Kotlin member does not accept,
            // like add(null) of a Java subclass of java.util.ArrayList<String>, is inherited in its place,
            // unless one of the Kotlin members it overrides is reached through Kotlin classes only too, see reachedThroughKotlin
            val isTakingOver = overriddenKotlin.none { it.kmClass.name in reachedThroughKotlin }
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
            // not to change which one of them is listed
            val standingJvmOnly = jvmOnlyCandidates.filter { jvmOnly ->
                jvmOnly.signature == candidate.signature && !candidate.raisesVisibility(jvmOnly)
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
        // and over the implementation of a Java class above it, like intValue() of java.util.concurrent.atomic.AtomicInteger
        val unlistedOverrides = unlistedKotlinCandidates.filter { !it.isHidden }
        if (unlistedOverrides.isNotEmpty()) {
            val classMembers = candidates.filter {
                !it.isHidden && it.declarations !is JavaClass && it.kmClass.kind != ClassKind.INTERFACE
            }
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
                    overrides.collect(candidate.declarations.overrideOwner, function.declaration, jvmDescriptor)
                }
            }
        }
        if (hasJavaCandidates) collectJavaOverrides(hiddenJavaOverrides, hiddenCandidates)
        fun isOverriddenByHidden(candidate: InheritedCandidate): Boolean = candidate.key in overriddenByHidden ||
            candidate.jvmMethod?.let { hiddenJavaOverrides.overrides(it) } == true
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
        val kotlinOverridden = overriding.filter { it.declarations !is JavaClass && it.kmClass.name in reachedThroughKotlin }
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
            if (member is Member.Function && hasJavaCandidates) {
                javaOverrides.collect(owner, member.declaration)
            }
            val keys = member.overridden(overrideResolver, owner)
            overridden += keys
            kotlinOverridden += keys
            val isUntoldOverride = keys.isEmpty() && member.isOverride(overrideResolver, owner)
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
                    else -> !javaOverrides.overrides(candidate.jvmMethod) && candidate.signature !in declaredSignatures
                }
        // the JVM-only members overridden by the Java candidates on the JVM, see javaMethodSignatures,
        // which are rendered as overrides then
        val jvmOnlyOverridden = candidates.filterTo(HashSet()) { candidate ->
            candidate.declarations is BuiltinClass && candidate.jvmMethod != null && isListable(candidate) &&
                javaMethodSignatures[candidate.jvmMethod.signature]?.onEach { it.isJvmOnlyOverride = true } != null
        }
        val notOverridden = candidates.filter { isListable(it) && it !in jvmOnlyOverridden }
        // whether the member a wins over the merged member b, of a less specific return type, see compareReturnType,
        // or else declared later
        fun wins(a: InheritedCandidate, aIndex: Int, b: InheritedCandidate, bIndex: Int): Boolean =
            when (val specificity = a.compareReturnType(b, supertypeNamesOf)) {
                null -> false
                0 -> aIndex < bIndex
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
        // the compiler groups the members merged with the first one, in the order of the direct supertypes contributing them,
        // like the K2 intersection of the members of the supertypes, then with the first one left, and so on,
        // so that the members merged with one member only, but not with each other, stay apart,
        // like the put(T) of a Java JGen<Integer>, taking Object, merged with the put(t: T) of GenericPut<Int>,
        // which is merged with the put(key: Int) of PrimitivePut, but not the Java one, taking int,
        // for `abstract class Join : JGenOfInteger(), PrimitivePut, GenericPut<Int>`, unlike for `interface Join : GenericPut<Int>, …`
        // each group listing its members of the most specific return type, see wins, which stand for the implementation
        // of a class merged with them, see InheritedCandidate.implementationModality, while the member of an interface implements none,
        // also grouping the Java methods, and the JVM-only members, which a member of the same JVM signature overrides,
        // see javaOverrides, so that they are not listed, though they still implement it, when of a class,
        // like the put(Object) of JGen<Integer>, or the putIfAbsent of java.util.HashMap, see InheritedCandidate.implementingClass,
        // the putIfAbsent(key: K, value: V) of a Kotlin interface given Int and String
        fun listed(survivors: List<InheritedCandidate>): List<InheritedCandidate> {
            val overriddenOnJvm = candidates.filter { candidate ->
                candidate.jvmMethod != null && !candidate.isHidden && candidate.isApi && candidate !in survivors &&
                    javaOverrides.overrides(candidate.jvmMethod)
            }
            val ordered = (survivors + overriddenOnJvm).sortedBy { directIndices[it.kmClass.name] ?: Int.MAX_VALUE }
            val groups = IdentityHashMap<InheritedCandidate, InheritedCandidate>()
            ordered.forEach { first ->
                if (first in groups) return@forEach
                ordered.filter { it === first || (it !in groups && first.isMergedWith(it)) }.forEach { groups[it] = first }
            }
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
        if (candidates.none { it.isHidden }) return listed(notOverridden)
        val candidatesByClass = candidates.groupBy { it.kmClass.name }
        val contributions = HashMap<Pair<String, InheritedCandidate>, List<InheritedCandidate>>()
        // the members merged with the listed candidate which the class named name contributes to its subclasses:
        // its own one, or the hidden one overriding it, or else the one picked of the ones its direct supertypes contribute,
        // in the order of declaration, like the hidden f() of Left for `interface Join : Left, Right`, also when Right overrides it,
        // or none, or all of them, when none can be picked, as some cannot be compared
        fun contribution(name: String, candidate: InheritedCandidate): List<InheritedCandidate> =
            contributions.getOrPut(name to candidate) {
                val merged = candidatesByClass[name].orEmpty()
                    .filter { it === candidate || it.isMergedWith(candidate) || it.overrides(candidate) }
                    .ifEmpty {
                        val picks = directSupertypes[name].orEmpty().map { contribution(it, candidate) }
                        picks.firstOrNull { it.size > 1 } ?: picks.flatten().distinct()
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
    }

    /**
     * This type with the projections of its type arguments, also nested, which the variance of their type parameters
     * makes redundant, made invariant, like the `Collection<out Int>` of a `Collection<? extends Integer>` of Java,
     * the `Collection<Int>` of a Kotlin member for the compiler, as `Collection` is covariant,
     * or this very type, if it has none, see [InheritedCandidate.notNullableSignature].
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

        val signatures = HashSet<JvmMethodSignature>()

        /**
         * Collect the JVM methods which the [function] of the [owner] overrides, by its JVM signature too.
         */
        fun collect(owner: OverrideResolver.Owner?, function: KmFunction, jvmDescriptor: String? = null) {
            owner?.let { members += overrideResolver.overriddenJavaMembers(it, function, jvmDescriptor) }
            function.signature?.let { signatures += it }
        }

        /**
         * Whether the [method] is overridden, as one of the [members], or of the same JVM signature as one of the overriding functions.
         */
        fun overrides(method: JvmMethod): Boolean = method.key in members || method.signature in signatures

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
     */
    private fun supertypeSubstitution(kmClass: KmClass, supertype: KmType): Map<Int, KmType> = buildMap {
        var declaration: KmClass? = kmClass
        var type: KmType? = supertype
        while (declaration != null && type != null) {
            declaration.typeParameters.forEachIndexed { index, typeParameter ->
                type.arguments.getOrNull(index)?.type
                    ?.takeIf { argument -> argument.refersOnlyTo { it >= INHERITING_TYPE_PARAMETER_IDS } }
                    ?.let { put(typeParameter.id, it) }
            }
            if (!declaration.isInner) break
            declaration = classDeclarations(declaration.name.substringBeforeLast('.'))?.kmClass
            type = type.outerType
        }
    }

    /**
     * The name the supertype of the [metadataName], of the class of the [declarations], or of its supertypes, is found by,
     * see [inheritedDeclarations]: its located name, when relocated with the class, or as a dependency of it, like by shading,
     * see [OverrideResolver.locatedName], or else the [metadataName], if the class of the located name cannot be described,
     * or kotlin-reflect does not tell its functions, see [JavaClass.areFunctionsReflected],
     * like when collecting the members the class might override, see [OverrideResolver.locatedName],
     * but the located name of a class which neither can be described, unless absent from the classpath,
     * so that its supertypes are walked through from its class file, see [rawJavaSupertypes].
     */
    private fun inheritedName(declarations: ClassDeclarations, metadataName: String): String {
        val located = declarations.overrideOwner?.let { overrideResolver.locatedName(it, metadataName) } ?: return metadataName
        // kotlin-reflect might not tell the functions of a relocated Java class, like of one implementing a relocated Kotlin interface
        return when {
            inheritedDeclarations(located)?.let { it !is JavaClass || it.areFunctionsReflected } == true -> located
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
     * This type, of a Java class described by kotlin-reflect, with its classifier, and the ones of its type arguments,
     * named by the names the Kotlin metadata refers to them by, see [unlocatedName], or this very type, if none is relocated.
     */
    private fun KmType.unlocated(declarations: ClassDeclarations): KmType {
        val jvmName = (classifier as? KmClassifier.Class)?.name
        val metadataName = jvmName?.let { unlocatedName(declarations, it) }
        val unlocatedArguments = arguments.map { argument ->
            val type = argument.type ?: return@map argument
            type.unlocated(declarations).takeIf { it !== type }?.let { KmTypeProjection(argument.variance, it) } ?: argument
        }
        if (metadataName == jvmName && unlocatedArguments.indices.all { unlocatedArguments[it] === arguments[it] }) return this
        return copy(arguments = unlocatedArguments).also { type ->
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
     * the ones of its JVM class, if loaded, see [javaSupertypeNames],
     * or else the ones its class file declares, see [OverrideResolver.unloadableJavaSupertypeNames],
     * named by the [named] of their names.
     */
    private fun rawJavaSupertypes(name: String, named: (String) -> String = { it }): List<KmType> {
        val names = if (findClassByMetadataName(name)?.clazz != null) {
            javaSupertypeNames(name).map { (_, metadataName) -> metadataName }
        } else {
            overrideResolver.unloadableJavaSupertypeNames(name)
        }
        return names.map { classType(named(it)) }
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
     * and the ones of its package on `Comparable` for a comparable class, see [isComparable].
     * Either way, the ones on a type parameter with multiple bounds only when the class satisfies all of them,
     * see [receiverBoundsOf], also when none of them is among the [userSupertypes], see [listedFor],
     * and only the ones which can be called on the class, as told by the type arguments, see [isCallableOn],
     * also the ones on its companion object, see [extendedCompanionOf], telling its deprecation, see [withCompanionDeprecation],
     * and never the ones on a type parameter shadowed by a more specific overload, see [withoutShadowed].
     *
     * @param apiClass the [kmClass] as a class of the API.
     * @param declarations the declarations of the [kmClass], telling its members, which shadow the extensions.
     * @param memberName the name of the only extensions to tell, like for [resolveMember], or `null` for all of them,
     *   filtered by name first, not to tell whether each of the others can be called.
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
            val listed = extensionsListedFor(ownReceiverNames + userSupertypes(kmClass) + companionReceiverNames, satisfiedBounds)
            if (supertypes.isComparable) {
                val isComparableToItself = supertypes.isComparableToItself
                // only the ones of the standard library, like on the other types of the standard library, see userSupertypes,
                // for a class comparable to itself, satisfying their bounds,
                // and of the package of the class, like next to it, but not the ones of the other libraries,
                // while the ones with multiple bounds, satisfied by the class, are already listed
                // also the ones of an object of the package, imported from it, told by the JVM package of their class,
                // like the one of the class
                val classPackage = contextPackageOf(declarations, kmClass)
                fun ReceiverBounded.isListed() =
                    ((isComparableToItself && isBuiltinExtension) || sourceClass.substringBeforeLast('.', "") == classPackage) &&
                        receiverBounds.all { it in satisfiedBounds }
                ListedExtensions(
                    (listed.functions + extensionIndex[COMPARABLE_CLASS_NAME].orEmpty().filter { it.isListed() }).distinct(),
                    (listed.properties + extensionPropertyIndex[COMPARABLE_CLASS_NAME].orEmpty().filter { it.isListed() }).distinct()
                )
            } else {
                listed
            }
        }
        val callable = ListedExtensions(
            listed.functions.filter { extension ->
                (memberName == null || extension.function.name == memberName) &&
                    extension.function.let { function ->
                        supertypes.isCallableOn(function.receiverParameterType, function.typeParameters) {
                            function.contextParameters.map { it.type } + function.valueParameters.map { it.type } + function.returnType
                        }
                    }
            },
            listed.properties.filter { extension ->
                (memberName == null || extension.property.name == memberName) &&
                    extension.property.let { property ->
                        supertypes.isCallableOn(property.receiverParameterType, property.typeParameters) {
                            property.contextParameters.map { it.type } + property.returnType
                        }
                    }
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
        val companionDeprecation = companion?.deprecation ?: return unshadowed
        return unshadowed.withCompanionDeprecation("${kmClass.name}.${companion.name}", companionDeprecation)
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
        if (pinned.isEmpty()) return this
        val members = memberFunctions(pinned.mapTo(HashSet()) { it.function.name }).groupBy { it.name }
        val extensionsOnClass = functions.filter { extension ->
            val receiver = extension.function.receiverParameterType
            receiver != null && !receiver.isNullable && receiver.arguments.isEmpty() &&
                (receiver.classifier as? KmClassifier.Class)?.name == kmClass.name
        }
        val shadowed = pinned.filterTo(HashSet()) { extension ->
            val function = extension.function
            val overloads = members[function.name].orEmpty() +
                extensionsOnClass.filter { it.function.name == function.name && it.source == extension.source }.map { it.function }
            overloads.any { it.shadows(function, kmClass.name) }
        }
        return ListedExtensions(functions.filter { it !in shadowed }, properties)
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
     *   the [kmClass] itself, like for `Alias.create` of `typealias Alias = Foo.Nested`, or one enclosing it.
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
        fun visit(name: String, metadataName: String, type: KmType?) {
            if (type != null && types != null) {
                val known = types[name].orEmpty()
                // each one reached through another path, like MutableList<String> of a Kotlin interface
                // and MutableList<String!> of a Java one, see keptSupertypes, up to MAX_SUPERTYPE_PATHS of them,
                // one without platform type arguments replacing one with them beyond, which kotlinc would leave out
                val reached = when {
                    known.any { it.hasSameStructure(type) } -> null
                    known.size < MAX_SUPERTYPE_PATHS -> known + type
                    type.hasPlatformArguments -> null
                    else -> known.indexOfFirst { it.hasPlatformArguments }.takeIf { it >= 0 }?.let { index ->
                        known.toMutableList().also { it[index] = type }
                    }
                }
                if (reached != null) {
                    types[name] = reached
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
        val supertypes = reflectedValue(default = emptyList()) { listOfNotNull(javaClass.superclass) + javaClass.interfaces }
        // each one on its own, not to lose the others when one of them cannot be reflected
        return supertypes.mapNotNull { supertype ->
            reflectedValue(default = null) {
                val supertypeMetadataName = javaSupertypeMetadataName(supertype)
                supertypeMetadataName.replace('/', '.') to supertypeMetadataName
            }
        }
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
        val header = findClassByMetadataName(metadataName)?.javaClassHeader ?: return emptyMap()
        val projections = when {
            header.typeParameters.isEmpty() -> NO_PROJECTIONS
            type == null -> return emptyMap()
            else -> projectionsOf(header, type) ?: return emptyMap()
        }
        val pureImplementation = PURE_IMPLEMENTATIONS[metadataName]?.replace('/', '.')
        val types = header.supertypes.associate { supertype ->
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
     * `class Price : Comparable<Money>`, but the ones of the package of a comparable class, see [isComparable],
     * and the ones of the standard library for a class comparable to itself, see [isComparableToItself],
     * except the function types, like `(String) -> Unit`, and their supertype `kotlin.Function`,
     * whose extensions are listed for a class implementing them, like for a function type class,
     * see [functionTypeReceiverNames].
     * Also the ones reached through a Java supertype, see [supertypeNames].
     */
    private fun userSupertypes(kmClass: KmClass): Set<String> =
        supertypeNames(kmClass, kmClassOf = ::loadKmClass) {
            (!it.startsWith("kotlin.") && !it.startsWith("java.")) ||
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
     * as these extensions are rarely called on the enum entries, and would grow the listing of every enum class.
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
     * see [listedExtensions], but not an enum class, see [isComparableToItself], which implies it,
     * nor a class comparable only through a raw Java supertype, like `class Mid extends Base`, where
     * `class Base<T> implements Comparable<Base<T>>`, told by its name, but not by its type, see [ClassSupertypes.types],
     * comparable to the raw `Base`, on which kotlinc does not accept the extensions of the package, like on `Comparable<Money>`.
     */
    private val ClassSupertypes.isComparable: Boolean
        get() = kmClass.kind != ClassKind.ENUM_CLASS && COMPARABLE_CLASS_NAME in types

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
     * see [ReceiverMatcher.isInferred], and an extension without a receiver can be called on it as well.
     * Only what is told by the Kotlin metadata, or by the generic signatures of the Java supertypes, is matched,
     * any other extension can be called, like on a supertype reached through a raw Java type,
     * or on a type parameter whose other bounds are not checked.
     */
    private fun ClassSupertypes.isCallableOn(
        receiver: KmType?,
        typeParameters: List<KmTypeParameter>,
        otherTypes: () -> List<KmType>
    ): Boolean {
        if (receiver == null || receiver.classifier is KmClassifier.TypeAlias) return true
        // what the type parameters are inferred from, the same for each type the receiver is matched against
        val referred = lazy(LazyThreadSafetyMode.NONE) { typeParameterIdsReferredBy(listOf(receiver), typeParameters) }
        val inferredOtherwise = lazy(LazyThreadSafetyMode.NONE) { typeParameterIdsReferredBy(otherTypes(), typeParameters) }
        return when (val classifier = receiver.classifier) {
            is KmClassifier.Class -> {
                val reached = types[classReceiverIndexKey(receiver)]
                reached == null || ReceiverMatcher(this, typeParameters, receiverTypeParameterId = null, self = kmClass.name)
                    .let { matcher ->
                        matcher.anyReached(reached) { supertype ->
                            matcher.argumentsMatch(classifier.name, receiver.arguments, supertype.arguments, KmVariance.OUT) &&
                                matcher.isInferred(referred, inferredOtherwise)
                        }
                    }
            }
            is KmClassifier.TypeParameter -> {
                val bounds = receiverBoundTypes(classifier.id, typeParameters)
                fun isSatisfiedBy(self: String) = ReceiverMatcher(this, typeParameters, classifier.id, self).let { matcher ->
                    bounds.all { matcher.isSupertype(it) } && matcher.isInferred(referred, inferredOtherwise)
                }
                isSatisfiedBy(kmClass.name) || bounds
                    .flatMap { bound -> types[classReceiverIndexKey(bound)].orEmpty().flatMap { it.arguments } }
                    .mapNotNullTo(LinkedHashSet()) { (it.type?.classifier as? KmClassifier.Class)?.name }
                    .any { it.replace('/', '.') in names && isSatisfiedBy(self = it) }
            }
            is KmClassifier.TypeAlias -> true
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
     * Matches the types of an extension, declaring the [typeParameters], against the types of the supertypes of a class,
     * see [isCallableOn], inferring its type parameters from the types matched against each of them, see [infer],
     * and the type parameter receiver of the [receiverTypeParameterId] standing for the class of the metadata name [self].
     */
    private inner class ReceiverMatcher(
        private val supertypes: ClassSupertypes,
        typeParameters: List<KmTypeParameter>,
        private val receiverTypeParameterId: Int?,
        private val self: String
    ) {

        private val typeParameters = typeParameters.associateBy { it.id }

        // the types each type parameter is inferred from, with the variances of their positions
        private val inferred = HashMap<Int, MutableList<Pair<KmType, KmVariance>>>()

        // the type parameters, in the order of what is inferred, see forgetInferredSince
        private val inferredIds = ArrayList<Int>()

        // telling whether a type the type parameter is inferred from is a subtype of another one, or the same type, see infer,
        // without inferring the type parameters of the extension, whose ids the type parameters of the class can take
        private val typeMatcher by lazy(LazyThreadSafetyMode.NONE) {
            ReceiverMatcher(supertypes, emptyList(), receiverTypeParameterId = null, self)
        }

        // the type parameters whose bounds are being checked, see satisfiesBounds
        private val boundsChecked = HashSet<Int>()

        // the types inferred from the bounds of a type parameter of a contravariant position, see satisfiesBounds,
        // by their instances, which tell nothing kotlinc fixes a type parameter by, see satisfiesRecursiveBounds
        private val typesInferredFromBounds: MutableSet<KmType> = Collections.newSetFromMap(IdentityHashMap())

        // whether the bounds of a type parameter of a contravariant position are inferring, see typesInferredFromBounds
        private var isInferringFromBounds = false

        // whether a bound refers to a type parameter of the extension, like T : Comparable<T>, see satisfiesRecursiveBounds
        private val hasBoundsReferringToTypeParameters by lazy(LazyThreadSafetyMode.NONE) {
            this.typeParameters.values.any { typeParameter -> typeParameter.upperBounds.any { !it.refersToNoTypeParameter() } }
        }

        // whether what is not told was matched, like the type arguments of a class which cannot be loaded, see matchesUntold,
        // or a type parameter of the class, which stands for any type, so that what is inferred is not told, see isInferred
        private var isUntold = false

        /**
         * Whether each type parameter the receiver refers to, the [referred] ones, also through the bounds of the ones it refers to,
         * like `E` of `T : List<E>`, is inferred from the matched types, see [infer],
         * like not `T` of `I<List<T>>` for `I<List<*>>`, see [isTrivialStarConstraint], nor `E` of `R<T>` for `R<Nothing>`,
         * where kotlinc cannot infer it, unless it is the type parameter receiver standing for the class,
         * a bound of an inferred one, like `U` of `T : U`, or one of the [inferredOtherwise] ones, also through their bounds,
         * like `E` of `T : List<E>` for `value: T`, see [typeParameterIdsReferredBy],
         * and the recursive bounds of the ones inferred only from contravariant positions are satisfied, see [satisfiesRecursiveBounds],
         * as far as what is matched is told, see [isUntold].
         * A type parameter the receiver does not refer to, like `T` of `fun <reified T> Registry.register()`,
         * is given explicitly, like on any other class.
         *
         * @param inferredOtherwise the ones the types of the context and value parameters, and the return type, of the extension
         *   refer to, which a call can infer them from, the return type from the expected type,
         *   like `T` of `fun <T> Box<List<T>>.put(value: T)`, or of `fun <T> Box<List<T>>.get(): T`.
         */
        fun isInferred(referred: Lazy<Set<Int>>, inferredOtherwise: Lazy<Set<Int>>): Boolean {
            if (isUntold || typeParameters.isEmpty()) return true
            val isInferred = referred.value.filterTo(HashSet()) { id ->
                id == receiverTypeParameterId || id !in typeParameters || !inferred[id].isNullOrEmpty() || id in inferredOtherwise.value
            }
            // a type parameter bounding an inferred one is its supertype, like U of T : U
            do {
                val bounding = isInferred.flatMap { id -> typeParameters[id]?.boundingTypeParameterIds().orEmpty() }
            } while (isInferred.addAll(bounding))
            return isInferred.containsAll(referred.value) && satisfiesRecursiveBounds { inferredOtherwise.value }
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
         * Not checked, like any other contravariant bound, is a type parameter bounding multiple ones
         * fixed as types none of which is a supertype of all the other ones, or as intersections of multiple types.
         */
        private fun satisfiesRecursiveBounds(inferredOtherwise: () -> Set<Int>): Boolean {
            if (!hasBoundsReferringToTypeParameters) return true
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
                val next = unfixed.firstOrNull { id -> fixed.keys.any { id in typeParameters.getValue(it).boundingTypeParameterIds() } }
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
        private fun isSubtype(subtype: KmType, supertype: KmType): Boolean =
            typeMatcher.matches(supertype, subtype, KmVariance.OUT, isStrict = false)

        // whether this type is Any?, or Any! of Java, telling nothing about a type parameter inferred from it in a contravariant position
        private val KmType.isTriviallyAny: Boolean
            get() = isNullableAny() || ((classifier as? KmClassifier.Class)?.name == ANY_CLASS_NAME &&
                flexibleTypeUpperBound?.type?.isNullableAny() == true)

        // whether this type is Nothing, not nullable
        private val KmType.isNothing: Boolean
            get() = !isNullable && (classifier as? KmClassifier.Class)?.name == NOTHING_CLASS_NAME

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
                    boundingParameter.upperBounds.filterTo(types) { it.refersToNoTypeParameter() }
                }
                bounding += boundingParameter.boundingTypeParameterIds()
            }
            // the classes of the bounds referring to the type parameter only covariantly, like List of T : List<T>, count as well
            val covariantlySelfReferring = typeParameter.upperBounds.filter { it.isSelfReferringOnlyIn(id, KmVariance.OUT) }
            if (isEmptyIntersection(types + covariantlySelfReferring)) return null
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
         * see [isUntold], by the ones which are not matched, see [retrying].
         */
        fun anyReached(reached: List<KmType>, match: (supertype: KmType) -> Boolean): Boolean {
            val mark = Mark()
            return reached.any { supertype -> retrying(mark) { match(supertype) } }
        }

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
         * which, of an invariant type parameter, is the same as `in Nothing` as well, like `MutableList<*>` and `MutableList<in Nothing>`.
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
                // a star projection of an invariant type parameter, the same as in Nothing
                return actual.type == null && declaredVariance == KmVariance.INVARIANT && expectedVariance == KmVariance.IN &&
                    matches(expectedType, classType(NOTHING_CLASS_NAME), KmVariance.INVARIANT, isStrict)
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
            val actualClassifier = actual.classifier as? KmClassifier.Class
                // a type parameter bounded by the class itself, like `E` of `Enum<E : Enum<E>>`, stands for the class
                ?: KmClassifier.Class(supertypes.kmClass.name).takeIf { supertypes.isSelfTypeParameter(actual) }
                // any other one for any type, but for the class itself,
                // like of `inner class Outer<T>.Inner : Comparable<T>`, comparable to `T`
                ?: return (receiverTypeParameterId == null ||
                    (expected.classifier as? KmClassifier.TypeParameter)?.id != receiverTypeParameterId) &&
                    matchesUntold(isStrict = false)
            return when (val classifier = expected.classifier) {
                is KmClassifier.TypeParameter -> when (classifier.id) {
                    receiverTypeParameterId ->
                        isNullabilityMatched(expected, actual, variance) && when (variance) {
                            KmVariance.INVARIANT -> actualClassifier.name == self
                            KmVariance.IN -> supertypes.isSubclass(self, actualClassifier.name)
                            KmVariance.OUT -> supertypes.isSubclass(actualClassifier.name, self)
                        }
                    in typeParameters -> inferredFrom(expected, actual, variance)
                        ?.let { infer(classifier.id, it, variance, isStrict) } == true
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
         * like the platform type `String!`, or `null` if a not nullable type cannot be a nullable type parameter,
         * like `String` for `T?` in a contravariant, or an invariant position.
         */
        private fun inferredFrom(expected: KmType, actual: KmType, variance: KmVariance): KmType? = when {
            !expected.isNullable || actual.flexibleTypeUpperBound != null -> actual
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
        private inner class Mark(val inferredCount: Int = inferredIds.size, val isUntold: Boolean = this@ReceiverMatcher.isUntold) {

            // goes back to this point, forgetting what is inferred since, and whether what is not told was matched since
            fun restore() {
                forgetInferredSince(inferredCount)
                this@ReceiverMatcher.isUntold = isUntold
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
                            }
                    }
                }
            } finally {
                boundsChecked.remove(id)
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
         * but `Nothing?` in a covariant one, for which kotlinc infers it as the bound with `Nothing?` substituted,
         * like `Comparable<Nothing?>?` of `T : Comparable<T>?`, which is no `Comparable<Comparable<Nothing?>?>?`,
         * unlike `List<Nothing?>?` of `T : List<T>?`.
         */
        private fun KmType.selfSubstituted(id: Int, actual: KmType, variance: KmVariance): Pair<KmType, KmType> {
            if (refersOnlyTo { it != id }) return this to actual
            val isNullableNothing = actual.isNullable && (actual.classifier as? KmClassifier.Class)?.name == NOTHING_CLASS_NAME
            val inferredType = if (isNullableNothing && variance == KmVariance.OUT) substituted(mapOf(id to actual)) else actual
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
     * @param aliasedClass the class a typealias the class is resolved through expands to, see [enclosingContext].
     */
    private fun formatClass(
        kmClass: KmClass,
        apiClass: ApiClass,
        className: String,
        foundClass: ClassDeclarations,
        isBuiltin: Boolean = false,
        aliasedClass: String? = null
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
            return ClassListing.of(header, context = enclosingContext(kmClass, apiClass, foundClass, includingClass = false, isBuiltin, aliasedClass))
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
            context = enclosingContext(kmClass, apiClass, foundClass, includingClass = false, isBuiltin, aliasedClass),
            isEnum = isEnum,
            enumEntryNames = if (isEnum) kmClass.kmEnumEntries.map { escapeIdentifier(it.name) } else emptyList(),
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
     * except [withHidden], the only place being the property declared by a primary constructor parameter
     * of a data class, or a value class, which must declare a property, like
     * `data class Foo(@Deprecated("Replaced", level = DeprecationLevel.HIDDEN) val a: Int)`, telling it cannot be used,
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
     * but neither `/*`, nor `*/`, which would nest, or end, the comment.
     */
    private fun formatCompanionDeprecation(deprecation: Deprecation?): String = if (deprecation == null) "" else {
        val arguments = formatDeprecationArguments(deprecation).replace("/*", "/ *").replace("*/", "* /")
        "/* companion object deprecated: $arguments */ "
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
     * @param isCalledOnClassName whether the extension, on the receiver of this type, is called on the name of a class,
     *   see [ListingItem.Extension.isCalledOnClassName].
     */
    private fun extensionSignatures(
        functions: List<IndexedExtension>,
        properties: List<IndexedExtensionProperty>,
        contextPackage: String?,
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
                            deprecation = it.deprecation, companionDeprecation = it.companionDeprecation
                        ),
                        isCalledOnClassName(it, it.property.receiverParameterType)
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
                                    deprecation = it.deprecation, companionDeprecation = it.companionDeprecation
                                )
                            ),
                            isCalledOnClassName(it, it.function.receiverParameterType)
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
     * @param isOwnerOpenForOverrides whether the user code might override the members of the [owner],
     *   see [formatModalityModifiers], by default unless final.
     * @param declaration the [function] as the [owner] declares it, telling whether it is an override,
     *   when the rendered one is a copy with the type arguments of an inheriting class substituted, see [inheritedMembers].
     * @param isJvmOnlyOverride whether the [function] is an override of a JVM-only member of a built-in type,
     *   which the [owner] does not tell, see [InheritedCandidate.isJvmOnlyOverride].
     * @param isOverridingNothing whether the [function] overrides nothing, though the [owner] tells otherwise,
     *   see [InheritedCandidate.isOverridingNothing].
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
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner?.kmClass),
        isInheritedByFinalClass: Boolean = false,
        declaration: KmFunction = function,
        isJvmOnlyOverride: Boolean = false,
        isOverridingNothing: Boolean = false
    ): String = buildString {
        val typeParameters = classTypeParameters.withDeclared(function.typeParameters)
            .withStandingFor(bodyReceiver?.typeParameters.orEmpty(), bodyReceiver?.classScope ?: TypeParameterScope.EMPTY)
        // the type parameters standing for the ones of the class are not declared
        val declaredTypeParameters = function.typeParameters.filter { it.id !in bodyReceiver?.typeParameters.orEmpty() }

        +formatDeprecation(deprecation)
        +formatCompanionDeprecation(companionDeprecation)
        +formatContextParameters(function.contextParameters, contextPackage, typeParameters)
        +formatVisibility(function.visibility)

        val override = override(owner, declaration)?.takeUnless { isOverridingNothing }
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
            +formatCompanionDeprecation(companionDeprecation)
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
    private fun override(owner: OverrideResolver.Owner?, function: KmFunction): OverrideResolver.FunctionOverride? =
        owner?.let { overrideResolver.override(it, function) }

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
            signature = classSignature
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
        val signature: String? = null
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
     */
    class Extension(val source: String, val lines: List<String>, val isCalledOnClassName: Boolean = false) : ClassItem, MemberItem

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
 *   unlike a class told only as protected, which does not matter to an extension.
 * @param protectedLine the comment telling the outermost protected class without its deprecation,
 *   like `// in protected class Outer.Foo`, or `null` if the class is not the outermost protected one.
 */
private class EnclosingClass(val line: String, val isDeprecated: Boolean, val protectedLine: String?) {

    /**
     * The comment rendered on a page of a [MemberListing], if any: the [line] of a deprecated class
     * when [isOnClassName], with a declaration called on the name of the class, like a member of an object,
     * or of a companion object, or an extension called so, see [ListingItem.Extension.isCalledOnClassName],
     * unlike a member, or an extension, of a class, called on its instance, like `foo.bar()`,
     * else the [protectedLine] when it [hasMembers], not only extensions.
     */
    fun lineOn(hasMembers: Boolean, isOnClassName: Boolean): String? = when {
        isDeprecated && isOnClassName -> line
        hasMembers -> protectedLine
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
        val isOnClassName = page.items.any {
            when (it) {
                is ListingItem.CompanionMember -> true
                is ListingItem.Extension -> it.isCalledOnClassName
                is ListingItem.Declaration, is ListingItem.InheritedMember -> areMembersCalledOnClassName
            }
        }
        context.forEach { enclosingClass -> enclosingClass.lineOn(hasMembers, isOnClassName)?.let { add(it) } }
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
         *   not only its extensions, while the one of a deprecated class only on every page with a declaration called on its name,
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
    get() {
        val receiver = receiverParameterType ?: return null
        val receiverTypeParameter = receiver.classifier as? KmClassifier.TypeParameter ?: return null
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
@OptIn(ExperimentalContextParameters::class)
private fun KmFunction.shadows(function: KmFunction, className: String): Boolean {
    val receiver = function.receiverParameterType ?: return false
    val receiverTypeParameter = receiver.classifier as? KmClassifier.TypeParameter ?: return false
    return !receiver.isNullable &&
        function.valueParameters.none { it.declaresDefaultValue || it.varargElementType != null } &&
        typeParameters.isEmpty() && contextParameters.isEmpty() &&
        (isOperator || !function.isOperator) &&
        (isInfix || !function.isInfix) &&
        valueParameters.size == function.valueParameters.size &&
        valueParameters.zip(function.valueParameters).all { (parameter, shadowedParameter) ->
            parameter.name == shadowedParameter.name &&
                parameter.varargElementType == null &&
                isSameType(shadowedParameter.type, parameter.type, receiverTypeParameter.id, className)
        }
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

// whether the JVM type is a primitive one, like int, see isMergedJvmParameter
private val AsmType.isPrimitive: Boolean get() = sort in AsmType.BOOLEAN..AsmType.DOUBLE

// whether the JVM array type is one of a primitive type, like int[], unlike int[][], see isMergedJvmParameter
private val AsmType.isPrimitiveArray: Boolean get() = dimensions == 1 && elementType.isPrimitive

// the JVM names of the boxed primitive types of Java, like java/lang/Integer, see isMergedJvmParameter
private val BOXED_JVM_NAMES: Set<String> = listOf(
    Boolean::class, Char::class, Byte::class, Short::class, Int::class, Float::class, Long::class, Double::class
).mapTo(HashSet()) { AsmType.getInternalName(it.javaObjectType) }

// the type of Any?, never modified, see TypeProjections
private val NULLABLE_ANY_TYPE = classType("kotlin/Any", nullable = true)

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
 * see `ReceiverMatcher.infer`.
 */
private fun isNullabilityMatched(expected: KmType, actual: KmType, variance: KmVariance): Boolean = when {
    // a platform type of Java, see javaSignatureType
    actual.flexibleTypeUpperBound != null || expected.flexibleTypeUpperBound != null -> true
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
        } else {
            type.javaSignatureType(substitution)
        }
        val argumentClassifier = argumentType.classifier
        // an erased type argument of a raw Kotlin supertype is the type kotlinc erases it to, see JAVA_ERASURE_MARKER
        if (!argumentType.isNullable && argumentClassifier is KmClassifier.Class && !argumentType.isJavaErasure) {
            argumentType.flexibleTypeUpperBound =
                KmFlexibleTypeUpperBound(argumentType.copy().also { it.isNullable = true }, typeFlexibilityId = null)
            if (classifier is KmClassifier.Class && argumentClassifier.name in MUTABLE_COLLECTION_TYPES) {
                argumentType.annotations += KmAnnotation(JAVA_COLLECTION_MARKER, emptyMap())
            }
            if (classifier is KmClassifier.Class && argumentClassifier.name == ARRAY_CLASS_NAME) {
                argumentType.annotations += KmAnnotation(JAVA_ARRAY_MARKER, emptyMap())
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
 * but not a nested one, like `String!` of `Box<List<String!>>`, as kotlinc tells it.
 */
private val KmType.hasPlatformArguments: Boolean
    get() = generateSequence(this) { it.outerType }.any { type ->
        // a Java collection, a Java array, or a raw type, is a platform type as well
        type.arguments.any { it.type?.flexibleTypeUpperBound != null }
    } || hasInexactErasure

// whether this type, or one of its type arguments, also a nested one, is an erasure not told exactly, see JAVA_INEXACT_ERASURE_MARKER
private val KmType.hasInexactErasure: Boolean
    get() = annotations.any { it.className == JAVA_INEXACT_ERASURE_MARKER } ||
        arguments.any { it.type?.hasInexactErasure == true } || outerType?.hasInexactErasure == true

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

// the number of distinct types of a supertype reached through different paths which are told and walked, see supertypeNames,
// as their number can double at each level of a hierarchy, like through Kotlin and through Java,
// the ones reached first, which the class is matched against, an extension matching only the other ones not listed,
// but the ones without platform type arguments, which replace the ones with them, see keptSupertypes
private const val MAX_SUPERTYPE_PATHS = 16

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
