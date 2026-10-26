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
import org.objectweb.asm.Type as AsmType
import java.io.File
import java.io.IOException
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
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
import kotlin.reflect.jvm.javaMethod
import kotlin.time.measureTime
import kotlin.metadata.jvm.Metadata as jvmMetadata

/**
 * Represents an indexed extension function with its source location.
 */
internal data class IndexedExtension(
    val function: KmFunction,
    val sourceClass: String,  // fully qualified class name where it's defined
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

}

/**
 * Represents an indexed extension property with its source location.
 */
internal data class IndexedExtensionProperty(
    val property: KmProperty,
    val sourceClass: String,  // fully qualified class name where it's defined
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
                logger.debug {
                    "Classpath scan read ${scan.readClassFileCount} class files, " +
                        "skipped ${scan.skippedClassFileCount} nested ones"
                }
            } else {
                extensionIndex = emptyMap()
                extensionPropertyIndex = emptyMap()
                classIndex = emptyMap()
            }
        }
        // an extension on a type parameter with multiple bounds is indexed under each of them, but counted once
        val extensionsFound = extensionIndex.values.flatMapTo(HashSet()) { it }.size +
            extensionPropertyIndex.values.flatMapTo(HashSet()) { it }.size
        logger.info { "Classpath scan completed in $scanTime, found $extensionsFound extensions" }
    }

    private fun resolve(name: String): Resolved<*>? {
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

            val foundClass = findClassByName(className) ?: continue
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
                        resolveMember(kmClass, apiClass, className, foundClass, memberName)
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
        val javaClass: JavaClass? by lazy {
            clazz?.takeIf { metadataAnnotation == null }?.let { reflectedJavaClass(it) }
        }

        /**
         * The `DefaultImpls` class file of an interface, or of an annotation class, holding the synthetic methods
         * for the annotations of its properties, unless compiled with `-jvm-default=no-compatibility`,
         * read only when needed, and again after a failure which might be transient, see [FinalOnce].
         */
        private val defaultImplsFile: ClassFile? get() = defaultImplsFileRead.value

        private val defaultImplsFileRead = FinalOnce { readClassFile("$binaryName\$$DEFAULT_IMPLS") }

        // the deprecation of a declaration of this class, see ClassFile, reflected when the class file cannot be read,
        // see reflectedMembersFile, like the one of a hidden constructor, telling whether the class can be subclassed

        override fun deprecation(function: KmFunction): Deprecation? = (classFile ?: reflectedMembersFile).deprecation(function)

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
         * also the Java-only ones, whose parameter names are not known, like `Throwable.initCause(arg0: Throwable?)`,
         * see [hasSyntheticParameterNames], as Kotlin calls them all the same,
         * and never lets a call to a Java method name its arguments anyway,
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
                formatType(it, typeParameters = typeParameterNames + function.typeParameters.namesById()) + "."
            }
            receiver.orEmpty() + overloadSignature(function, typeParameterNames)
        }

        // the signatures, see signatureOf, computed once per function, like for each deprecation asked
        private val signatures = ConcurrentHashMap<KmFunction, String>()

        override fun deprecation(function: KmFunction): Deprecation? =
            reflectedFunctions[signatureOf(function)]?.let { builtinDeprecation(it.kFunction) }

        /**
         * The JVM method of a JVM-only [function], telling its overrides, see [inheritedMembers].
         */
        fun jvmMethodOf(function: KmFunction): JvmMethod? = jvmMethodOf(reflectedFunctions[signatureOf(function)]?.kFunction)

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
        private val properties: Map<KmProperty, KProperty<*>>
    ) : ClassDeclarations {

        override val classDeprecation: Deprecation? get() = null

        override val scope: ClassScope? get() = null

        // described by kotlin-reflect of the loaded class, by its very name, never relocated
        override fun located(metadataName: String): String = metadataName

        override fun deprecation(function: KmFunction): Deprecation? =
            functions[function]?.let { builtinDeprecation(it.kFunction) }

        override fun deprecation(constructor: KmConstructor): Deprecation? = null

        override fun deprecation(property: KmProperty): Deprecation? = properties[property]?.let { builtinDeprecation(it) }

        /**
         * The keys of the members of the Kotlin supertypes which the [member] of this class overrides,
         * see [OverrideResolver.overriddenMembers], regardless of the nullability of the platform types of Java,
         * like `E!`, of its value parameters, see [JavaFunction.notNullable].
         */
        fun overriddenMembers(member: Member): Set<String> {
            val overridden = member.overridden(overrideResolver, kmClass)
            if (member !is Member.Function) return overridden
            val notNullable = functions[member.declaration]?.notNullable ?: return overridden
            return overridden + overrideResolver.overriddenMembers(kmClass, notNullable)
        }

        /**
         * The JVM method of the [function], telling its overrides, see [inheritedMembers].
         */
        fun jvmMethodOf(function: KmFunction): JvmMethod? = jvmMethodOf(functions[function]?.kFunction)

        /**
         * The Java method of the [function], or `null` if it cannot be reflected.
         */
        fun javaMethodOf(function: KmFunction): Method? =
            reflectedValue(default = null) { functions[function]?.kFunction?.javaMethod }

    }

    /**
     * A function of a Java class, see [JavaClass], reflected by kotlin-reflect as the [kFunction],
     * whose value parameters of the platform types of Java, like `E!`, accepting `null`, are nullable, see [toKmFunction],
     * while a Kotlin override declares them either way, like `equals(other: Any?)`, or `add(element: E)`,
     * so that its overrides are matched also with these parameters [notNullable], see [withNotNullableParameters].
     */
    private class JavaFunction(val kFunction: KFunction<*>, val notNullable: KmFunction)

    /**
     * Whether the Java [method] is overridden by a JVM class between the [inheriting] class, if loaded, and the class declaring it,
     * one of its superclasses declaring a method of the same name and parameter types,
     * which kotlin-reflect might describe as another member, like the `size()` of `java.util.Hashtable`,
     * seen in Kotlin as the `size` of `Map`, overriding the abstract one of `java.util.Dictionary`,
     * or, of a public [method], the one resolved on the [inheriting] class being declared by a subtype of its class,
     * as a JVM class, whose superclasses can be told, tells it, unlike the Kotlin view of its methods.
     * Neither a bridge, like the one javac generates in a public class for a public method of its package-private superclass,
     * nor a private, or a static, method of the same signature overrides it.
     */
    private fun isOverriddenBelow(method: Method, inheriting: Class<*>?): Boolean = reflectedValue(default = false) {
        if (inheriting == null) return@reflectedValue false
        val declaring = method.declaringClass
        val parameterTypes = method.parameterTypes
        fun Method.overrides() = !isBridge && !Modifier.isPrivate(modifiers) && !Modifier.isStatic(modifiers) &&
            name == method.name && this.parameterTypes.contentEquals(parameterTypes)
        generateSequence(inheriting.superclass) { it.superclass }.takeWhile { it != declaring }.any { superclass ->
            superclass.declaredMethods.any { it.overrides() }
        } || (Modifier.isPublic(method.modifiers) && inheriting.getMethod(method.name, *parameterTypes).let {
            it.overrides() && it.declaringClass != declaring && declaring.isAssignableFrom(it.declaringClass)
        })
    }

    /**
     * The JVM method of the [kFunction], see [JvmMethod], telling its overrides, see [inheritedMembers].
     */
    private fun jvmMethodOf(kFunction: KFunction<*>?): JvmMethod? = reflectedValue(default = null) {
        kFunction?.javaMethod?.let { method ->
            val descriptor = AsmType.getMethodDescriptor(method)
            JvmMethod(javaMemberKey(method.declaringClass.name, method.name, descriptor), JvmMethodSignature(method.name, descriptor))
        }
    }

    /**
     * The [JavaClass] of the Java [clazz], with its members, which Kotlin calls, see [isUnsupportedByCompiler],
     * each described on its own, not to lose the others when one of them cannot be,
     * like when its signature refers to a class missing from the classpath,
     * or `null` if kotlin-reflect cannot describe the class itself.
     */
    private fun reflectedJavaClass(clazz: Class<*>): JavaClass? = reflectedValue(default = null) {
        val kClass = clazz.kotlin
        val kmClass = kClass.toKmJavaClassHeader(kotlinClassName(clazz)) { supertype ->
            overrideResolver.builtinNameOfJavaClass(supertype.name)
                ?: functionTypeOfJvmInterface(supertype.name.replace('.', '/'))
                ?: kotlinClassName(supertype)
        }
        val typeParameters = ReflectedTypeParameters.classTypeParameters(kmClass.typeParameters)
        // kotlin-reflect tells no visibility of a protected Java member, which is also visible within its package
        fun Visibility.orProtected(modifiers: Int?): Visibility =
            if (this == Visibility.LOCAL && modifiers != null && Modifier.isProtected(modifiers)) Visibility.PROTECTED else this
        val functions = IdentityHashMap<KmFunction, JavaFunction>()
        kClass.declaredMemberFunctions.forEach { function ->
            reflectedValue(default = null) {
                function.takeIf { '$' !in it.name && !isUnsupportedByCompiler(it) }
                    ?.toKmFunction(typeParameters)
                    ?.also {
                        it.modality = it.modality.finalIn(kmClass)
                        it.visibility = it.visibility.orProtected(function.javaMethod?.modifiers)
                    }
            }?.let { kmFunction ->
                kmClass.functions += kmFunction
                functions[kmFunction] = JavaFunction(function, kmFunction.withNotNullableParameters())
            }
        }
        val properties = IdentityHashMap<KmProperty, KProperty<*>>()
        kClass.declaredMemberProperties.forEach { property ->
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
                properties[kmProperty] = property
            }
        }
        JavaClass(clazz, kmClass, functions, properties)
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
     * like for every nested class listed on every page of a paged resolve.
     * It is bounded, as a found class keeps its parsed metadata, so that a long-lived introspector
     * does not keep the metadata of every class ever looked up,
     * while the [overrideResolver] keeps the declarations it needs in its own bounded caches.
     * The outcome of a lookup which might be transient is not cached, see [lookUpClass],
     * nor a miss of a lookup by a name given to resolve, see [lookUpClassByBinaryName].
     */
    private val foundClasses = BoundedCache<String, ClassLookup>(capacity = 1024)

    /**
     * Finds a class by its binary name, like `com.example.Outer${'$'}Nested`, cached also when it is not found,
     * unless [isMissCached] is `false`, like for a name which is not known to be exact.
     */
    private fun findClassByBinaryName(binaryName: String, isMissCached: Boolean = true): FoundClass? =
        lookUpClassByBinaryName(binaryName, isMissCached).foundClass

    /**
     * The lookup of a class by its binary name, see [findClassByBinaryName],
     * shared with the concurrent lookups of the same class, and counted as the same failing attempt, see [attemptLookUpClass],
     * except for a miss of a lookup whose miss is not cached, so not probed either, see [lookUpClass],
     * which a concurrent lookup whose miss is cached does not receive, as if it were probed, looking the class up again,
     * and which is not cached, as the names whose misses are not cached come from the user input,
     * while a miss of such a lookup caused by an `IOException` is probed, and, if its class file is there,
     * is a failing attempt like of any other lookup, cached once deemed final.
     * After the misses of two such lookups in a row, the lookup whose miss is cached looks the class up on its own,
     * outside the cache entry, so not shared with the concurrent lookups, caching it, if final, over the cached one, if any.
     */
    private fun lookUpClassByBinaryName(binaryName: String, isMissCached: Boolean = true): ClassLookup {
        repeat(2) {
            val computed = foundClasses.getOrCompute(binaryName) {
                val lookup = attemptLookUpClass(binaryName, isMissProbed = isMissCached)
                BoundedCache.Computed(lookup, isCacheable = lookup.isFinal && lookup.isMissProbed)
            }
            // also when computed by another thread
            if (!isMissCached || computed.value.isMissProbed) return computed.value.markedUnlessFinal()
        }
        // after the misses of two concurrent lookups by name in a row, which might keep taking the cache entry for good
        val lookup = attemptLookUpClass(binaryName, isMissProbed = true)
        if (lookup.isFinal) foundClasses.put(binaryName, lookup)
        return lookup.markedUnlessFinal()
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
            findClassByBinaryName(binaryName, isMissCached = false)?.let { return it }
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
     * follows the context telling it, see [protectedContext].
     * The JVM-only members of a built-in type, like `String.chars()`, follow its functions, see [BuiltinClass],
     * but the ones hidden by an extension, which its listing leaves out too, see [jvmOnlyFunctionsNotHidden].
     * Then the members it inherits from its supertypes, and does not override, see [inheritedMembers],
     * which its listing leaves out, not to be flooded with them, rendered before the companion object members, and the extensions.
     *
     * @param apiClass the [kmClass] as a class of the API.
     * @param isBuiltin whether the class is a built-in type, or its companion object, see [listedExtensions].
     */
    private fun resolveMember(
        kmClass: KmClass,
        apiClass: ApiClass,
        className: String,
        declarations: ClassDeclarations,
        memberName: String,
        isBuiltin: Boolean = false
    ): MemberListing? {
        val contextPackage = contextPackageOf(declarations, kmClass)
        // like in the listing of the class, also the extensions a built-in type lists in its body, with their receivers
        val listedExtensions = listedExtensions(kmClass, className, apiClass, declarations, isBuiltin).let { listed ->
            // Kotlin reports the deprecation of a companion object also for its extensions called on its name,
            // like Foo.Companion.of(), which, resolved directly, unlike in its listing, are not rendered under its header
            val deprecation = declarations.classDeprecation.takeIf { kmClass.kind == ClassKind.COMPANION_OBJECT }
            if (deprecation == null) listed else listed.withCompanionDeprecation(kmClass.name, deprecation)
        }
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

        val extensions = extensionSignatures(
            (listedExtensions.functions.filter { it.function.name == memberName } + hidingExtensions).distinct(),
            listedExtensions.properties.filter { it.property.name == memberName },
            contextPackage
        )
        return MemberListing.of(
            members.map { ListingItem.Declaration(it) } + inherited + companionMembers + extensions,
            context = protectedContext(kmClass.name, apiClass, includingClass = true)
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
        val typeParameters = typeParametersInScope(kmClass, declarations)
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

        /**
         * The key of this member of the [owner], see [memberKey].
         */
        fun key(owner: KmClass): String

        /**
         * The keys of the members of the supertypes of the [owner] which this member of it overrides,
         * see [OverrideResolver.overriddenMembers].
         */
        fun overridden(overrideResolver: OverrideResolver, owner: KmClass): Set<String>

        /**
         * Whether this member of the [owner] is an override, see [OverrideResolver.isOverride].
         */
        fun isOverride(overrideResolver: OverrideResolver, owner: KmClass): Boolean

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

            override fun key(owner: KmClass): String = memberKey(owner, declaration)

            override fun overridden(overrideResolver: OverrideResolver, owner: KmClass): Set<String> =
                overrideResolver.overriddenMembers(owner, declaration)

            override fun isOverride(overrideResolver: OverrideResolver, owner: KmClass): Boolean =
                overrideResolver.isOverride(owner, declaration)

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

            override fun key(owner: KmClass): String = memberKey(owner, declaration)

            override fun overridden(overrideResolver: OverrideResolver, owner: KmClass): Set<String> =
                overrideResolver.overriddenMembers(owner, declaration)

            override fun isOverride(overrideResolver: OverrideResolver, owner: KmClass): Boolean =
                overrideResolver.isOverride(owner, declaration)

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
     */
    private fun formatMember(
        member: Member,
        kmClass: KmClass,
        declarations: ClassDeclarations,
        scope: ApiClass,
        contextPackage: String,
        isOwnerOpenForOverrides: Boolean,
        isInheritedByFinalClass: Boolean = false,
        isInheritedByInterface: Boolean = false,
        typeParameters: Map<Int, String> = typeParametersInScope(kmClass, declarations)
    ): List<String> {
        // a member inherited by an interface is rendered as within it, like the ones of kotlin.Any
        val isInterface = kmClass.kind == ClassKind.INTERFACE || isInheritedByInterface
        return when (member) {
            is Member.Property -> formatApiProperty(
                member.property, scope, declarations,
                inInterface = isInterface, owner = kmClass, contextPackage = contextPackage, classTypeParameters = typeParameters,
                isOwnerOpenForOverrides = isOwnerOpenForOverrides, isInheritedByFinalClass = isInheritedByFinalClass,
                declaration = member.declaration
            )
            is Member.Function -> listOf(
                formatFunction(
                    member.function, inInterface = isInterface, owner = kmClass, contextPackage = contextPackage,
                    classTypeParameters = typeParameters, deprecation = declarations.deprecation(member.declaration),
                    isOwnerOpenForOverrides = isOwnerOpenForOverrides, isInheritedByFinalClass = isInheritedByFinalClass,
                    declaration = member.declaration
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
     * A [member] named like the one looked up, declared by a supertype, the [kmClass] of the [declarations],
     * which is inherited, unless a member of another supertype overrides it, see [inheritedMembers],
     * rendered with the type arguments of the inheriting class substituted, see [Member.substituted].
     *
     * @param key the key of the member, see [memberKey].
     * @param overridden the keys of the members of the supertypes of the [kmClass] which the [member] overrides.
     * @param signature the signature of the [member] as rendered, see [Member.signature],
     *   the same for the members of the same signature of unrelated supertypes.
     * @param typeParameters the names of the type parameters the [member] refers to, by their ids.
     * @param isHidden whether the [member] is deprecated as hidden, so that it is not inherited,
     *   while it still hides the members it overrides,
     *   and the members merged with it, when the compiler picks it of them, see [inheritedCandidates].
     * @param jvmMethod the JVM method of the [member], see [JvmMethod],
     *   of a JVM-only member of a built-in type, see [BuiltinClass], or of a member of a Java class, see [JavaClass].
     */
    private inner class InheritedCandidate(
        val kmClass: KmClass,
        val declarations: ClassDeclarations,
        val member: Member,
        val key: String,
        val overridden: Set<String>,
        val signature: String,
        val typeParameters: Map<Int, String>,
        val isHidden: Boolean,
        val jvmMethod: JvmMethod?
    ) {

        /**
         * Whether the [member], of a Java class, overrides a JDK method of the JVM type of a built-in type,
         * which the built-in type does not expose, as one of its JVM-only members, see [BuiltinClass.jvmOnlyFunctions],
         * by the keys of the [exposedJdkMethods], see [javaMemberKey], like the `sort(Comparator)` of `java.util.List`,
         * which `MutableList` does not have, as Kotlin calls its `MutableList<T>.sort(comparator)` extension instead.
         */
        fun overridesHiddenJdkMethod(exposedJdkMethods: Set<String>): Boolean {
            val function = (member as? Member.Function)?.declaration ?: return false
            return overrideResolver.overriddenJavaMembers(kmClass, function).any { key ->
                overrideResolver.builtinNameOfJavaClass(key.substringBefore('#')) != null && key !in exposedJdkMethods
            }
        }

        /**
         * Whether the [member] overriding the one of the [overridden] candidate makes it more visible,
         * like a public override of a protected member, or returns a more specific type, see [compareSpecificity].
         */
        fun refines(overridden: InheritedCandidate, supertypeNamesOf: (String) -> Set<String>): Boolean =
            raisesVisibility(overridden) ||
                (compareSpecificity(member.returnType, overridden.member.returnType, supertypeNamesOf) ?: 0) > 0

        /**
         * Whether the [member] overriding the one of the [overridden] candidate makes it more visible,
         * like a public override of a protected member.
         */
        fun raisesVisibility(overridden: InheritedCandidate): Boolean =
            member.visibility == Visibility.PUBLIC && overridden.member.visibility != Visibility.PUBLIC

        /**
         * Whether the [member] is merged with the one of the [other] candidate, competing with it,
         * as of the same kind, and of the same [signature], or overriding the same member, declared by another supertype,
         * as the members of the same supertype are its overloads.
         */
        fun isMergedWith(other: InheritedCandidate): Boolean =
            other.kmClass.name != kmClass.name && (other.member is Member.Property) == (member is Member.Property) &&
                (other.signature == signature || other.overridden.any { it in overridden })

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
     * as the compiler resolves neither it, nor the member it overrides, on the [kmClass].
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
     * also when another direct supertype overrides it, like `f()` of `interface Join : Left, Right`, hidden by `Left`.
     * Inherited by a class which the user code cannot subclass, see [ApiClass.isOpen], a member is neither `open`, nor `final`,
     * like its own members, but a member with a body of an interface, which is rendered as within its interface,
     * telling it apart from an abstract one.
     * A Java supertype, which is not rendered, contributes the members it declares, as kotlin-reflect describes them,
     * see [JavaClass], like `ensureCapacity(arg0: Int)` of `java.util.ArrayList`,
     * but the ones overriding a member of a Kotlin supertype, like its `get(index: Int)`,
     * for which the Kotlin member is inherited instead, declared with the names of its parameters, which the JDK does not tell,
     * unless the Java method makes it more visible, like the public `clone()` of the protected one of `kotlin.Cloneable`,
     * or returns a more specific type, see [InheritedCandidate.refines],
     * and the ones overriding a JDK method which a built-in type does not expose, like the `sort(Comparator)` of `java.util.List`,
     * see [InheritedCandidate.overridesHiddenJdkMethod].
     * Its methods are matched with the members of the Kotlin supertypes they override
     * regardless of the nullability of the platform types of Java, see [JavaClass.overriddenMembers].
     * A Java supertype whose members cannot be described, like one which cannot be loaded, is walked through to its supertypes,
     * as told by its class file, see [rawJavaSupertypes].
     * A Kotlin supertype which is not part of the API, see [ApiClasses], is walked through too,
     * its members not inherited, so that the members it overrides stand for its overrides, which the user code cannot see.
     * Which members are part of the API is decided by the inheriting class, the [scope],
     * like a protected member, inherited by a final class, which is not.
     * The members of the [kmClass], see [apiMembersIncludingHidden], also the hidden ones, override the ones they match like any other override,
     * so that only the inherited overloads are listed next to them, like `add(element: E)` of `MutableList`
     * next to the `add(index: Int, element: E)` which `AbstractMutableList` declares.
     * A qualified [memberName], like `Nested.member`, names no member, so the supertypes are not walked then.
     * The JVM-only members of a built-in supertype, see [BuiltinClass.jvmOnlyFunctions], and the members of a Java supertype,
     * are overridden like the Java members, see [OverrideResolver.overriddenJavaMembers], or by a function of the same JVM signature,
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
        return inherited.map { candidate ->
            ListingItem.InheritedMember(
                candidate.kmClass.name.replace('/', '.'),
                formatMember(
                    candidate.member, candidate.kmClass, candidate.declarations, scope, contextPackage,
                    isOwnerOpenForOverrides = isOpenForOverrides,
                    // also the open member of a class, unless inherited by an interface,
                    // where it is rendered as open, like its own members, telling it apart from an abstract one,
                    // like the member with a body of an interface, rendered as within it
                    isInheritedByFinalClass = !isOpenForOverrides && kmClass.kind != ClassKind.INTERFACE &&
                        candidate.kmClass.kind != ClassKind.INTERFACE,
                    isInheritedByInterface = kmClass.kind == ClassKind.INTERFACE,
                    typeParameters = candidate.typeParameters
                )
            )
        }
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
        val inheritingTypeParameters = typeParametersInScope(kmClass, declarations)
        val inheritingNames = inheritingTypeParameters.mapKeys { (id, _) -> inheritingTypeParameterId(id) }
        val inheriting = inheritingTypeParameters.keys.associateWith { typeParameterType(inheritingTypeParameterId(it)) }
        val visited = hashSetOf(kmClass.name, ANY_CLASS_NAME)
        val pending = ArrayDeque(kmClass.supertypes.map { it.substituted(inheriting) })
        val candidates = mutableListOf<InheritedCandidate>()
        // the names of the direct supertypes of each class walked, in the order of declaration, see contribution below
        val directSupertypes = hashMapOf(kmClass.name to kmClass.supertypes.classNames())
        fun collect(name: String, supertype: KmType) {
            val superDeclarations = inheritedDeclarations(name)
            val superKmClass = superDeclarations?.kmClass
            if (superDeclarations == null || superKmClass == null) {
                val javaSupertypes = rawJavaSupertypes(name)
                directSupertypes[name] = javaSupertypes.classNames()
                pending += javaSupertypes
                return
            }
            val substitution = supertypeSubstitution(superKmClass, supertype)
            directSupertypes[name] = superKmClass.supertypes.classNames()
            pending += superKmClass.supertypes.map { it.substituted(substitution) }
            if (superDeclarations !is JavaClass && apiClasses.apiClass(superKmClass.name) == null) return
            val jvmOnlyFunctions = (superDeclarations as? BuiltinClass)?.jvmOnlyFunctions.orEmpty()
            val jvmOnlyFunctionsNotHidden = if (jvmOnlyFunctions.any { it.name == memberName }) {
                // only an extension on the built-in type itself reads like its member, and hides it,
                // see bodyReceiverSubstitution, never one on a type parameter, like of multiple bounds, see listedFor
                val hiding = extensionIndex[superKmClass.name.replace('/', '.')].orEmpty().filter {
                    it.hidesMembers && it.function.name == memberName && it.isBuiltinExtension
                }
                jvmOnlyFunctionsNotHidden(superKmClass, superDeclarations, hiding, hidingExtensions)
            } else {
                jvmOnlyFunctions
            }
            val typeParameters = typeParametersInScope(superKmClass, superDeclarations) + inheritingNames
            apiMembersIncludingHidden(
                superKmClass, superDeclarations, memberName, scope, jvmOnlyFunctionsNotHidden
            ).forEach { apiMember ->
                val declared = apiMember.member
                val overridden = (superDeclarations as? JavaClass)?.overriddenMembers(declared)
                    ?: declared.overridden(overrideResolver, superKmClass)
                val member = declared.substituted(substitution, inheritingNames)
                candidates += InheritedCandidate(
                    superKmClass, superDeclarations, member, declared.key(superKmClass), overridden,
                    signature = member.signature(inheritingNames),
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
                    }
                )
            }
        }
        while (pending.isNotEmpty()) {
            val supertype = pending.removeFirst()
            val name = (supertype.classifier as? KmClassifier.Class)?.name ?: continue
            if (visited.add(name)) collect(name, supertype)
        }
        if (kmClass.name != ANY_CLASS_NAME) collect(ANY_CLASS_NAME, classType(ANY_CLASS_NAME))
        // the supertypes of the return types compared, see compareSpecificity, walked once per class
        val supertypeNames = HashMap<String, Set<String>>()
        val supertypeNamesOf = { name: String ->
            supertypeNames.getOrPut(name) { supertypeNames(name, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) }) }
        }
        // a Java method overriding a member of a Kotlin supertype, like get of java.util.ArrayList, is not inherited,
        // the Kotlin member is, unless the Java method refines it, see above, and neither is one overriding
        // a JDK method which a built-in type does not expose, see InheritedCandidate.overridesHiddenJdkMethod,
        // or which Kotlin hides, see overridesHiddenJdkMethod, like the reversed(): LinkedList<E> of java.util.LinkedList,
        // overriding the hidden reversed() of java.util.List, so that list.reversed() calls the Iterable<T>.reversed() extension,
        // nor one overriding a JVM-only member of a built-in type of the same signature, unless it makes it more visible,
        // nor one overridden by a JVM class between the inheriting class and its own, see isOverriddenBelow,
        // like size() of java.util.Dictionary, overridden by java.util.Hashtable, seen in Kotlin as the size of Map
        val kotlinCandidates = candidates.filter { it.declarations !is JavaClass }
        val jvmOnlyCandidates = kotlinCandidates.filter { it.declarations is BuiltinClass && it.jvmMethod != null }
        val exposedJdkMethods = jvmOnlyCandidates.mapNotNullTo(HashSet()) { it.jvmMethod?.key }
        candidates.removeAll { candidate ->
            val javaClass = candidate.declarations as? JavaClass ?: return@removeAll false
            kotlinCandidates.any { overridden ->
                overridden.key in candidate.overridden && !candidate.refines(overridden, supertypeNamesOf)
            } ||
                (candidate.overridden.isEmpty() && candidate.overridesHiddenJdkMethod(exposedJdkMethods)) ||
                jvmOnlyCandidates.any { jvmOnly ->
                    jvmOnly.signature == candidate.signature && !candidate.raisesVisibility(jvmOnly)
                } ||
                (candidate.member as? Member.Function)?.let { function ->
                    javaClass.javaMethodOf(function.declaration)?.let {
                        overridesHiddenJdkMethod(it) || isOverriddenBelow(it, declarations.clazz)
                    }
                } == true
        }
        if (candidates.isEmpty()) return emptyList()

        // the declared members override the inherited ones, matched by the overridden keys,
        // or else, for a declared override whose overridden Kotlin members are not told, like of a Java supertype,
        // by the same signature, with the type arguments of the supertypes substituted
        val overridden = candidates.flatMapTo(HashSet()) { it.overridden }
        // the Java members are overridden by the declared members, or by the ones of the other supertypes,
        // like an override declared by an intermediate class, also by a function of the same JVM signature,
        // overriding the JVM method on the JVM, though its metadata does not tell, like `ArrayDeque.addFirst(element: E)`
        // of the standard library, compiled against JDK 8, of `addFirst` of `java.util.List` since JDK 21
        val overriddenJavaMembers = HashSet<String>()
        val overridingJvmSignatures = HashSet<JvmMethodSignature>()
        fun collectJavaOverrides(owner: KmClass, function: KmFunction) {
            overriddenJavaMembers += overrideResolver.overriddenJavaMembers(owner, function)
            function.signature?.let { overridingJvmSignatures += it }
        }
        val hasJavaCandidates = candidates.any { it.jvmMethod != null && !it.isHidden }
        if (hasJavaCandidates) {
            candidates.filter { it.declarations !is BuiltinClass }.forEach { candidate ->
                (candidate.member as? Member.Function)?.let { collectJavaOverrides(candidate.kmClass, it.declaration) }
            }
        }
        val declaredSignatures = HashSet<String>()
        // also a hidden one, hiding the member it overrides, which cannot be called on the class either
        apiMembersIncludingHidden(kmClass, declarations, memberName, scope).map { it.member }.forEach { member ->
            if (member is Member.Function && hasJavaCandidates) {
                collectJavaOverrides(kmClass, member.declaration)
            }
            val keys = member.overridden(overrideResolver, kmClass)
            overridden += keys
            if (keys.isEmpty() && member.isOverride(overrideResolver, kmClass)) {
                declaredSignatures += member.signature(inheritingTypeParameters)
            }
        }
        val survivors = candidates.filter {
            !it.isHidden && it.key !in overridden && it.signature !in declaredSignatures &&
                it.jvmMethod?.key !in overriddenJavaMembers && it.jvmMethod?.signature !in overridingJvmSignatures
        }
        // whether the member a wins over the merged member b, of a less specific return type, or else declared later
        fun wins(a: InheritedCandidate, aIndex: Int, b: InheritedCandidate, bIndex: Int): Boolean =
            when (val specificity = compareSpecificity(a.member.returnType, b.member.returnType, supertypeNamesOf)) {
                null -> false
                0 -> aIndex < bIndex
                else -> specificity > 0
            }
        // the member winning over all the others, or none, when some cannot be compared
        fun winner(members: List<InheritedCandidate>): InheritedCandidate? = members.withIndex().firstOrNull { (index, member) ->
            members.withIndex().all { (otherIndex, other) -> other === member || wins(member, index, other, otherIndex) }
        }?.value
        val listed = survivors.filterIndexed { index, candidate ->
            survivors.withIndex().none { (rivalIndex, rival) ->
                rivalIndex != index && rival.isMergedWith(candidate) && wins(rival, rivalIndex, candidate, index)
            }
        }
        // the compiler merges the members which the direct supertypes contribute, each its own, or the one it inherits,
        // also a hidden one overridden by another direct supertype, and picks the one of the most specific return type,
        // or else the one of the first direct supertype, in the order of declaration, see contribution,
        // so that none of them can be called when the picked one is hidden, like f() of `interface Join : Left, Right`,
        // whose Left hides the f() which Right does not, while the one of Right is picked for `interface Join : Right, Left`
        // the listed member still stands for the picked one, which is visible, like the implementation of an abstract member,
        // declared by an interface of an intermediate class, of `abstract class Square : Shape, SquareBase()`,
        // while the compiler picks the abstract one of Shape, the first direct supertype
        if (candidates.none { it.isHidden }) return listed
        val candidatesByClass = candidates.groupBy { it.kmClass.name }
        val contributions = HashMap<Pair<String, InheritedCandidate>, List<InheritedCandidate>>()
        // the members merged with the listed candidate which the class named name contributes to its subclasses:
        // its own one, or else the one picked of the ones its direct supertypes contribute, in the order of declaration,
        // like the hidden f() of Left for `interface Join : Left, Right`, also when Right overrides it,
        // or none, or all of them, when none can be picked, as some cannot be compared
        fun contribution(name: String, candidate: InheritedCandidate): List<InheritedCandidate> =
            contributions.getOrPut(name to candidate) {
                val merged = candidatesByClass[name].orEmpty()
                    .filter { it === candidate || it.isMergedWith(candidate) }
                    .ifEmpty {
                        val picks = directSupertypes[name].orEmpty().map { contribution(it, candidate) }
                        picks.firstOrNull { it.size > 1 } ?: picks.flatten().distinct()
                    }
                winner(merged)?.let { listOf(it) } ?: merged
            }
        return listed.filter { candidate -> contribution(kmClass.name, candidate).singleOrNull()?.isHidden != true }
    }

    /**
     * The names of the classes of these types, like the supertypes of a class.
     */
    private fun List<KmType>.classNames(): List<String> = mapNotNull { (it.classifier as? KmClassifier.Class)?.name }

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
     * The declarations of the supertype named like `java/util/ArrayList`, whose members a class inherits, see [inheritedMembers]:
     * the ones of a Kotlin class, or of a built-in type, see [classDeclarations],
     * or the ones of a Java class, as kotlin-reflect describes it, see [JavaClass],
     * or `null` if the class cannot be described.
     */
    private fun inheritedDeclarations(name: String): ClassDeclarations? {
        val declarations = classDeclarations(name)
        if (declarations?.kmClass != null) return declarations
        return (declarations as? FoundClass)?.javaClass
    }

    /**
     * The direct supertypes of the Java class named like `com/example/Base`, whose members cannot be described,
     * see [inheritedDeclarations], as raw types, without their type arguments, mapped to the built-in types:
     * the ones of its JVM class, if loaded, see [javaSupertypeNames],
     * or else the ones its class file declares, see [OverrideResolver.unloadableJavaSupertypeNames].
     */
    private fun rawJavaSupertypes(name: String): List<KmType> {
        val names = if (findClassByMetadataName(name)?.clazz != null) {
            javaSupertypeNames(name).map { (_, metadataName) -> metadataName }
        } else {
            overrideResolver.unloadableJavaSupertypeNames(name)
        }
        return names.map { classType(it) }
    }

    /**
     * Whether the type [a] is more specific than the type [b], a positive number, or less, a negative one,
     * or equally, `0`, like the same class of the same nullability, or two type parameters,
     * or `null` if they cannot be compared, as told by their classifiers, and their nullability, not by their type arguments,
     * the classifiers related also through a Java class, like `java.util.ArrayList`, a subtype of `MutableList`,
     * as told by the names of their supertypes, given by the [supertypeNamesOf] their metadata names, see [supertypeNames].
     */
    private fun compareSpecificity(a: KmType, b: KmType, supertypeNamesOf: (String) -> Set<String>): Int? {
        val aClassifier = a.classifier
        val bClassifier = b.classifier
        if (aClassifier is KmClassifier.TypeParameter && bClassifier is KmClassifier.TypeParameter) return 0
        if (aClassifier !is KmClassifier.Class || bClassifier !is KmClassifier.Class) return null
        if (aClassifier.name == bClassifier.name) return when {
            a.isNullable == b.isNullable -> 0
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
     * but the ones of the standard library on `Comparable` for a class comparable to itself, see [isComparableToItself].
     * Either way, the ones on a type parameter with multiple bounds only when the class satisfies all of them,
     * see [receiverBoundsOf], also when none of them is among the [userSupertypes], see [listedFor],
     * and only the ones which can be called on the class, as told by the type arguments, see [isCallableOn],
     * also the ones on its companion object, see [extendedCompanionOf], telling its deprecation, see [withCompanionDeprecation],
     * and never the ones on a type parameter shadowed by a more specific overload, see [withoutShadowed].
     *
     * @param apiClass the [kmClass] as a class of the API.
     * @param declarations the declarations of the [kmClass], telling its members, which shadow the extensions.
     */
    private fun listedExtensions(
        kmClass: KmClass,
        className: String,
        apiClass: ApiClass,
        declarations: ClassDeclarations,
        isBuiltin: Boolean
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
            if (supertypes.isComparableToItself) {
                // only the ones of the standard library, like on the other types of the standard library, see userSupertypes,
                // and of the package of the class, like next to it, but not the ones of the other libraries,
                // while the ones with multiple bounds, satisfied by the class, are already listed
                val classPackage = contextPackageOf(declarations, kmClass)
                fun ReceiverBounded.isListed() =
                    (isBuiltinExtension || source == classPackage) && receiverBounds.all { it in satisfiedBounds }
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
                extension.function.receiverParameterType
                    ?.let { supertypes.isCallableOn(it, extension.function.typeParameters) } != false
            },
            listed.properties.filter { extension ->
                extension.property.receiverParameterType
                    ?.let { supertypes.isCallableOn(it, extension.property.typeParameters) } != false
            }
        )
        val builtinClass = declarations as? BuiltinClass
        val unshadowed = callable.withoutShadowed(supertypes) { names ->
            (kmClass.functions + builtinClass?.jvmOnlyFunctions.orEmpty())
                .filter { it.name in names && it.receiverParameterType == null }
                .inApi(apiClass, declarations) +
                inheritedFunctionsNamed(kmClass, declarations, names, apiClass)
        }
        val companionDeprecation = companion?.deprecation ?: return unshadowed
        return unshadowed.withCompanionDeprecation("${kmClass.name}.${companion.name}", companionDeprecation)
    }

    /**
     * These extensions, telling on the ones on the companion object named [companionName], like `com/example/Foo.Companion`,
     * its [deprecation], as Kotlin reports it when they are called, like `Foo.of()`, or `Foo.Companion.of()`,
     * rendered next to their own one, see [formatCompanionDeprecation].
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
     * other than a member extension, or one of these extensions on the class itself,
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
        val supertype = types[classReceiverIndexKey(bound)] ?: return@any false
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
     * but the member extensions, and the JVM-only ones of a built-in supertype.
     */
    private fun inheritedFunctionsNamed(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        names: Set<String>,
        scope: ApiClass
    ): List<KmFunction> = names.flatMap { name ->
        inheritedCandidates(kmClass, declarations, name, scope, hidingExtensions = mutableListOf()).mapNotNull { candidate ->
            (candidate.member as? Member.Function)?.function?.takeUnless {
                it.receiverParameterType != null || (candidate.declarations is BuiltinClass && candidate.jvmMethod != null)
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
     * The comment telling the outermost protected class enclosing the declarations of the class named [name],
     * like `// in protected companion object Outer.Named`, as only the subclasses of its outer class can use them,
     * or `null` if there is none, rendered before the declarations resolved directly,
     * which would otherwise read as usable by anyone, unlike in the listing of the outer class,
     * where they are rendered within that protected class.
     *
     * @param apiClass the class named [name] as a class of the API.
     * @param includingClass whether the class itself counts, as for its members,
     *   unlike for the class resolved directly, whose header tells its own visibility.
     */
    private fun protectedContext(name: String, apiClass: ApiClass, includingClass: Boolean): String? {
        val names = enclosingClassNames(name) + name
        val index = names.indices
            .take(if (includingClass) names.size else names.size - 1)
            .firstOrNull { apiClass.scopes[it].visibility == Visibility.PROTECTED }
            ?: return null
        val protectedName = names[index]
        val kind = formatClassKind(apiClass.scopes[index].kind)
        return "// in protected $kind${escapeQualifiedName(protectedName.substringAfterLast('/'))}"
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
        formatApiProperty(property, companion.apiClass, companion.foundClass, owner = companion.kmClass, contextPackage = contextPackage)

    private fun formatCompanionFunction(function: KmFunction, companion: ApiCompanion, contextPackage: String): String =
        formatFunction(
            function, inInterface = false, owner = companion.kmClass, contextPackage = contextPackage,
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
     * @param types when given, receives the supertypes told by the Kotlin metadata, by their names,
     *   with the type arguments substituted down to the [kmClass], see [ClassSupertypes.types].
     */
    private fun supertypeNames(
        kmClass: KmClass,
        kmClassOf: (metadataName: String) -> KmClass?,
        types: MutableMap<String, KmType>? = null,
        included: (name: String) -> Boolean = { true }
    ): Set<String> = supertypeNames(kmClass.name, { if (it == kmClass.name) kmClass else kmClassOf(it) }, types, included)

    /**
     * The names of the supertypes of the class of the [metadataName], see [supertypeNames] of a [KmClass],
     * also of a Java class, like `java/util/ArrayList`, whose supertypes are told by its JVM class.
     */
    private fun supertypeNames(
        metadataName: String,
        kmClassOf: (metadataName: String) -> KmClass?,
        types: MutableMap<String, KmType>? = null,
        included: (name: String) -> Boolean = { true }
    ): Set<String> {
        val supertypeNames = linkedSetOf<String>()
        // the metadata names of the supertypes to traverse, starting with the class itself, which is not among them,
        // with the types they are reached by, unless through a Java class, telling the substitution of their type parameters
        val pending = ArrayDeque<Pair<String, KmType?>>(listOf(metadataName to null))
        fun visit(name: String, metadataName: String, type: KmType?) {
            if (type != null) types?.putIfAbsent(name, type)
            val functionTypeClass = FunctionTypeClass.of(name)
            if (functionTypeClass != null) {
                if (included(name)) supertypeNames += name
                functionTypeClass.supertypeNames.forEach { visit(it, kotlinClassName(it), type = null) }
            } else if (included(name) && supertypeNames.add(name)) {
                pending += metadataName to type
            }
        }
        fun visitSupertypes(supertypes: List<KmType>, substitution: Map<Int, KmType>?) {
            for (supertype in supertypes) {
                val classifier = supertype.classifier as? KmClassifier.Class ?: continue
                val type = if (types != null && substitution != null) supertype.substituted(substitution) else null
                visit(receiverIndexKeys(supertype).single(), classifier.name, type)
            }
        }
        var isClassItself = true
        while (pending.isNotEmpty()) {
            val (supertypeName, type) = pending.removeFirst()
            val supertypeKmClass = kmClassOf(supertypeName)
            if (supertypeKmClass != null) {
                val substitution = if (isClassItself) emptyMap() else type?.let { substitutionOf(supertypeKmClass, it) }
                visitSupertypes(supertypeKmClass.supertypes, substitution)
            } else {
                javaSupertypeNames(supertypeName).forEach { (name, supertypeMetadataName) ->
                    visit(name, supertypeMetadataName, type = null)
                }
            }
            isClassItself = false
        }
        return supertypeNames
    }

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
                val supertypeMetadataName = overrideResolver.builtinNameOfJavaClass(supertype.name)
                    ?: functionTypeOfJvmInterface(supertype.name.replace('.', '/'))
                    ?: kotlinClassName(supertype)
                supertypeMetadataName.replace('/', '.') to supertypeMetadataName
            }
        }
    }

    /**
     * All the supertypes of a class, excluding the ones from `kotlin.*` and `java.*` packages,
     * which are not traversed further, so that a class implementing, e.g., `CharSequence`
     * is not flooded with the generic extensions of the standard library,
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
     * @property types the supertypes told by the Kotlin metadata, by their names, with the type arguments of the class,
     *   like `Comparable<User>` of `class User : Entity<User>()`, where `abstract class Entity<E> : Comparable<E>`,
     *   telling whether an extension listed by its receiver name can be called on the class, see [isCallableOn],
     *   while a type parameter of the class, in its type arguments, stands for any type.
     */
    private class ClassSupertypes(val kmClass: KmClass, val names: Set<String>, val types: Map<String, KmType>) {

        /**
         * The names of the supertypes of the supertypes of the class, by their metadata names, see [supertypeNamesOf].
         */
        val supertypeNamesOfSupertypes = HashMap<String, Set<String>>()

    }

    private fun supertypesOf(kmClass: KmClass): ClassSupertypes {
        val types = HashMap<String, KmType>()
        val names = supertypeNames(kmClass, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) }, types = types)
        return ClassSupertypes(kmClass, names, types)
    }

    /**
     * Whether the class is comparable to itself, or to one of its supertypes,
     * like `class Version : Comparable<Version>`, or `class User : Entity<User>()`, where `Entity<E> : Comparable<E>`,
     * satisfying the bound `T : Comparable<T>` of the extensions of the standard library on `Comparable`,
     * like `coerceAtLeast`, which are few and widely used, unlike the generic extensions
     * on the other types of the standard library, see [userSupertypes].
     * Told by the Kotlin metadata of the class and of its supertypes, see [ClassSupertypes.types],
     * not through a Java supertype, whose type arguments are not known.
     * An enum class, comparable to itself through `kotlin.Enum`, is not told to be,
     * as these extensions are rarely called on the enum entries, and would grow the listing of every enum class.
     */
    private val ClassSupertypes.isComparableToItself: Boolean
        get() {
            if (kmClass.kind == ClassKind.ENUM_CLASS) return false
            val comparableTo = types[COMPARABLE_CLASS_NAME]?.arguments?.singleOrNull()?.type ?: return false
            if (isSelfTypeParameter(comparableTo)) return true
            val name = (comparableTo.classifier as? KmClassifier.Class)?.name ?: return false
            return name == kmClass.name || name.replace('/', '.') in names
        }

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
        supertypeNamesOfSupertypes.getOrPut(metadataName) {
            val known = if (metadataName == kmClass.name) {
                names
            } else {
                supertypeNames(metadataName, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) }).ifEmpty {
                    // a loaded Java interface without superinterfaces, like java.lang.Runnable, is still known
                    if (findClassByMetadataName(metadataName)?.clazz != null) setOf(ANY_RECEIVER_KEY) else emptySet()
                }
            }
            known + jvmSupertypeNamesOfBuiltins(known + metadataName.replace('/', '.'))
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
     * Only what is told by the Kotlin metadata is matched, any other extension can be called,
     * like on a supertype reached through a Java class, or on a type parameter whose other bounds are not checked.
     */
    private fun ClassSupertypes.isCallableOn(receiver: KmType, typeParameters: List<KmTypeParameter>): Boolean =
        when (val classifier = receiver.classifier) {
            is KmClassifier.Class -> {
                val supertype = types[classReceiverIndexKey(receiver)]
                supertype == null || ReceiverMatcher(this, typeParameters, receiverTypeParameterId = null, self = kmClass.name)
                    .argumentsMatch(classifier.name, receiver.arguments, supertype.arguments, KmVariance.OUT)
            }
            is KmClassifier.TypeParameter -> {
                val bounds = receiverBoundTypes(classifier.id, typeParameters)
                fun isSatisfiedBy(self: String) = ReceiverMatcher(this, typeParameters, classifier.id, self).let { matcher ->
                    bounds.all { matcher.isSupertype(it) }
                }
                isSatisfiedBy(kmClass.name) || bounds
                    .flatMap { bound -> types[classReceiverIndexKey(bound)]?.arguments.orEmpty() }
                    .mapNotNullTo(LinkedHashSet()) { (it.type?.classifier as? KmClassifier.Class)?.name }
                    .any { it.replace('/', '.') in names && isSatisfiedBy(self = it) }
            }
            is KmClassifier.TypeAlias -> true
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
     * The declaration-site variances of the type parameters of the class of the given [metadataName],
     * like `in P1, out R` of a function type, see [FunctionTypeClass], or `null` if the class cannot be loaded.
     */
    private fun declaredVariances(metadataName: String): List<KmVariance>? {
        FunctionTypeClass.of(metadataName.replace('/', '.'))?.let { functionTypeClass ->
            return List(functionTypeClass.arity) { KmVariance.IN } + KmVariance.OUT
        }
        return (builtinClasses[metadataName] ?: loadKmClass(metadataName))?.typeParameters?.map { it.variance }
    }

    /**
     * Matches the types of an extension, declaring the [typeParameters], against the types of the supertypes of a class,
     * see [isCallableOn], inferring its type parameters from the first type matched against each of them,
     * and the type parameter receiver of the [receiverTypeParameterId] standing for the class of the metadata name [self].
     */
    private inner class ReceiverMatcher(
        private val supertypes: ClassSupertypes,
        typeParameters: List<KmTypeParameter>,
        private val receiverTypeParameterId: Int?,
        private val self: String
    ) {

        private val typeParameterIds = typeParameters.mapTo(HashSet()) { it.id }

        private val inferred = HashMap<Int, Pair<KmType, KmVariance>>()

        /**
         * Whether the class of the type parameter receiver is a subtype of the [bound].
         */
        fun isSupertype(bound: KmType): Boolean {
            val classifier = bound.classifier as? KmClassifier.Class ?: return true
            if (classifier.name == ANY_CLASS_NAME) return true
            val name = classReceiverIndexKey(bound)
            if (name != self.replace('/', '.') && name !in supertypes.supertypeNamesOf(self)) return false
            val supertype = supertypes.types[name] ?: return true
            return argumentsMatch(classifier.name, bound.arguments, supertype.arguments, KmVariance.OUT)
        }

        /**
         * Whether the [expected] type arguments of the class of the given [metadataName] match the [actual] ones,
         * in a position of the given [variance], see [matches].
         */
        fun argumentsMatch(
            metadataName: String,
            expected: List<KmTypeProjection>,
            actual: List<KmTypeProjection>,
            variance: KmVariance
        ): Boolean {
            if (expected.size != actual.size) return true
            val declaredVariances = declaredVariances(metadataName)?.takeIf { it.size == expected.size } ?: return true
            return expected.indices.all { index ->
                val expectedType = expected[index].type
                val actualType = actual[index].type
                // a star projection, or a projected supertype argument, matches any type
                if (expectedType == null || actualType == null || actual[index].variance != KmVariance.INVARIANT) {
                    return@all true
                }
                val argumentVariance = projectedVariance(declaredVariances[index], expected[index].variance!!)
                argumentVariance == null || matches(expectedType, actualType, variance.compose(argumentVariance))
            }
        }

        /**
         * Whether the [expected] type of the extension matches the [actual] one of a supertype of the class:
         * the same, or, in a covariant position, a supertype of it, in a contravariant one, a subtype of it,
         * while a type parameter of the class stands for any type, other than the class itself.
         */
        private fun matches(expected: KmType, actual: KmType, variance: KmVariance): Boolean {
            val actualClassifier = actual.classifier as? KmClassifier.Class
                // a type parameter bounded by the class itself, like `E` of `Enum<E : Enum<E>>`, stands for the class
                ?: KmClassifier.Class(supertypes.kmClass.name).takeIf { supertypes.isSelfTypeParameter(actual) }
                // any other one for any type, but for the class itself,
                // like of `inner class Outer<T>.Inner : Comparable<T>`, comparable to `T`
                ?: return receiverTypeParameterId == null ||
                    (expected.classifier as? KmClassifier.TypeParameter)?.id != receiverTypeParameterId
            return when (val classifier = expected.classifier) {
                is KmClassifier.TypeParameter -> when (classifier.id) {
                    receiverTypeParameterId ->
                        isNullabilityMatched(expected, actual, variance) && when (variance) {
                            KmVariance.INVARIANT -> actualClassifier.name == self
                            KmVariance.IN -> supertypes.isSubclass(self, actualClassifier.name)
                            // the class is not told to be a subtype of the actual type
                            KmVariance.OUT -> true
                        }
                    in typeParameterIds -> infer(classifier.id, actual, variance)
                    else -> true
                }
                is KmClassifier.Class ->
                    isNullabilityMatched(expected, actual, variance) && if (classifier.name == actualClassifier.name) {
                        argumentsMatch(classifier.name, expected.arguments, actual.arguments, variance)
                    } else {
                        when (variance) {
                            KmVariance.INVARIANT -> false
                            KmVariance.OUT -> supertypes.isSubclass(actualClassifier.name, classifier.name)
                            KmVariance.IN -> supertypes.isSubclass(classifier.name, actualClassifier.name)
                        }
                    }
                is KmClassifier.TypeAlias -> true
            }
        }

        /**
         * Infers the type parameter of the given [id] from the [actual] type in a position of the given [variance],
         * or tells whether it can be, once inferred from another one: when either of them is invariant,
         * the other one is its subtype in a covariant position, its supertype in a contravariant one.
         */
        private fun infer(id: Int, actual: KmType, variance: KmVariance): Boolean {
            val (inferredType, inferredVariance) = inferred[id] ?: run {
                inferred[id] = actual to variance
                return true
            }
            if (variance == KmVariance.INVARIANT) inferred[id] = actual to variance
            val (type, otherType, otherVariance) = when {
                inferredVariance == KmVariance.INVARIANT -> Triple(inferredType, actual, variance)
                variance == KmVariance.INVARIANT -> Triple(actual, inferredType, inferredVariance)
                // both can be the type parameter inferred as their common supertype, or subtype
                else -> return true
            }
            val name = (type.classifier as? KmClassifier.Class)?.name ?: return true
            val otherName = (otherType.classifier as? KmClassifier.Class)?.name ?: return true
            return when (otherVariance) {
                KmVariance.INVARIANT -> name == otherName
                KmVariance.OUT -> supertypes.isSubclass(otherName, name)
                KmVariance.IN -> supertypes.isSubclass(name, otherName)
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
        val declared = apiMembersNamed(kmClass, declarations, memberName, apiClass).map { member ->
            ListingItem.InheritedMember(
                KFUNCTION_CLASS_NAME,
                formatMember(
                    member, kmClass, declarations, apiClass, contextPackage,
                    isOwnerOpenForOverrides = isOpenForOverrides, isInheritedByInterface = true
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
        val typeParameters = typeParametersInScope(kmClass, foundClass)

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
            bodyExtensionsOf(kmClass, foundClass, typeParameters, listedExtensions)
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
                val isOverride = prop != null && isOverride(kmClass, prop)
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
            return ClassListing.of(header, context = protectedContext(kmClass.name, apiClass, includingClass = false))
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
            context = protectedContext(kmClass.name, apiClass, includingClass = false),
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
     */
    private fun overloadSignature(
        function: KmFunction,
        classTypeParameters: Map<Int, String>,
        bodyReceiverSubstitution: Map<Int, String> = emptyMap()
    ): String {
        val typeParameters = classTypeParameters + function.typeParameters.namesById() + bodyReceiverSubstitution
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
        val formatted = formatType(if (notNullable) type.notNullable() else type, typeParameters = typeParameters)
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
                    bodyReceiverSubstitution(extension.property.receiverParameterType, extension.property.typeParameters, kmClass) != null
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
        val typeParameters = typeParametersInScope(kmClass, declarations)
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
     * The declarations a built-in type lists in its body, besides its own properties and functions, each with its name:
     * its JVM-only members, see [BuiltinClass], but the ones hidden by its [listedExtensions],
     * like in its lookup, see [jvmOnlyFunctionsNotHidden], and the [bodyExtensions], see [bodyExtensionsOf].
     *
     * @param typeParameters the names of the type parameters in scope of the [kmClass].
     */
    private fun builtinBodyItems(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        apiClass: ApiClass,
        typeParameters: Map<Int, String>,
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
                bodyReceiverSubstitution = bodyReceiverSubstitution(
                    extension.property.receiverParameterType, extension.property.typeParameters, kmClass
                ),
                deprecation = extension.deprecation
            )
        }
        val bodyExtensionFunctions = bodyExtensions.functions.map { extension ->
            extension.function.name to listOf(
                formatFunction(
                    extension.function, contextPackage = contextPackage,
                    bodyReceiverSubstitution = bodyReceiverSubstitution(extension.function, kmClass),
                    deprecation = extension.deprecation
                )
            )
        }
        return jvmOnlyProperties + jvmOnlyFunctions + bodyExtensionProperties + bodyExtensionFunctions
    }

    /**
     * The type parameters of an extension [function] of a built-in type, the [kmClass], see [bodyReceiverSubstitution],
     * telling the types of its context and value parameters.
     */
    @OptIn(ExperimentalContextParameters::class)
    private fun bodyReceiverSubstitution(function: KmFunction, kmClass: KmClass): Map<Int, String>? =
        bodyReceiverSubstitution(
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
     * by their ids, mapped to the names of the type parameters of the class,
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
    private fun bodyReceiverSubstitution(
        receiver: KmType?,
        typeParameters: List<KmTypeParameter>,
        kmClass: KmClass,
        signatureTypes: List<KmType> = emptyList()
    ): Map<Int, String>? {
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
            filterNot { it.isNullableAny() }.mapTo(HashSet()) { formatType(it, typeParameters = typeParameters) }
        if (substituted.any { (typeParameter, classTypeParameter) ->
                !classTypeParameter.upperBounds.bounds(classTypeParameters).containsAll(typeParameter.upperBounds.bounds(substitution))
            }
        ) return null
        val classTypeParameterNames = classTypeParameters.values.toSet()
        if (typeParameters.any { it.id !in substitution && it.name in classTypeParameterNames }) return null
        return substitution
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
        typeParameters: Map<Int, String>,
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
     * @param typeParameters the names of the type parameters in scope, including the ones
     *   of the outer class of an inner class.
     * @param deprecation the deprecation of the class, see [formatDeprecation].
     * @param kind the kind of the class to render, like the one of a listed companion object, see [formatListedClassHeader].
     */
    private fun formatClassHeader(
        kmClass: KmClass,
        name: String,
        contextPackage: String,
        typeParameters: Map<Int, String> = kmClass.typeParameters.namesById(),
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
        typeParameters: Map<Int, String>
    ): String = formatContext(kmClass.legacyContextReceiverTypes.map { formatType(it, contextPackage, typeParameters) })
        .let { if (it.isEmpty()) it else "$it$LEGACY_CONTEXT_RECEIVERS " }

    /**
     * The header of a [listed] class, like a nested class, see [formatClassHeader], without its constructor,
     * where the companion object of another class, listed as a sealed subclass, is referred to as an object,
     * like `object Holder.Companion : Sealed`, as a companion object can only be declared in its own class.
     */
    private fun formatListedClassHeader(listed: ListedClass, contextPackage: String): String =
        formatClassHeader(
            listed.kmClass, formatListedClassName(listed), contextPackage, typeParametersInScope(listed.kmClass, listed.foundClass),
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
     * which the inner class can refer to.
     */
    private class OuterTypeParameters(val name: String, val typeParameters: Map<Int, String>) {

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
            outers += OuterTypeParameters(outerName, typeParameters)
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
        typeParameters: Map<Int, String>
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
     */
    private fun extensionSignatures(
        functions: List<IndexedExtension>,
        properties: List<IndexedExtensionProperty>,
        contextPackage: String?
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
                        )
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
                            )
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
     * @param bodyReceiverSubstitution the substitution of an extension listed in the body of its receiver class,
     *   rendered without its receiver, see [bodyReceiverSubstitution], or `null` to render the receiver.
     * @param deprecation the deprecation of the [function], see [formatDeprecation].
     * @param companionDeprecation the deprecation of the companion object the [function] extends,
     *   see [formatCompanionDeprecation].
     * @param isOwnerOpenForOverrides whether the user code might override the members of the [owner],
     *   see [formatModalityModifiers], by default unless final.
     * @param declaration the [function] as the [owner] declares it, telling whether it is an override,
     *   when the rendered one is a copy with the type arguments of an inheriting class substituted, see [inheritedMembers].
     */
    @OptIn(ExperimentalContextParameters::class)
    private fun formatFunction(
        function: KmFunction,
        inInterface: Boolean = false,
        owner: KmClass? = null,
        contextPackage: String? = null,
        classTypeParameters: Map<Int, String> = emptyMap(),
        bodyReceiverSubstitution: Map<Int, String>? = null,
        deprecation: Deprecation? = null,
        companionDeprecation: Deprecation? = null,
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner),
        isInheritedByFinalClass: Boolean = false,
        declaration: KmFunction = function
    ): String = buildString {
        val typeParameters = classTypeParameters + function.typeParameters.namesById() + bodyReceiverSubstitution.orEmpty()
        // the type parameters standing for the ones of the class are not declared
        val declaredTypeParameters = function.typeParameters.filter { it.id !in bodyReceiverSubstitution.orEmpty() }

        +formatDeprecation(deprecation)
        +formatCompanionDeprecation(companionDeprecation)
        +formatContextParameters(function.contextParameters, contextPackage, typeParameters)
        +formatVisibility(function.visibility)

        val override = override(owner, declaration)
        +formatModalityModifiers(function.modality, override != null, inInterface, isOwnerOpenForOverrides, isInheritedByFinalClass)

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
        function.receiverParameterType?.takeIf { bodyReceiverSubstitution == null }?.let { receiverType ->
            +formatReceiverType(receiverType, contextPackage, typeParameters)
            +"."
        }

        +escapeIdentifier(function.name)
        +"("

        // an override cannot declare a default value, but inherits the one of the overridden function
        val inheritedDefaultValues = override?.inheritedDefaultValues.orEmpty()
        +(function.valueParameters.withIndex().joinToString(", ") { (index, param) ->
            formatValueParameter(param, contextPackage, typeParameters, hasDefaultValue = index in inheritedDefaultValues)
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
        typeParameters: Map<Int, String> = emptyMap(),
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
        typeParameters: Map<Int, String>
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
        owner: KmClass? = null,
        contextPackage: String? = null,
        classTypeParameters: Map<Int, String> = emptyMap(),
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner),
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
     * @param bodyReceiverSubstitution the substitution of an extension listed in the body of its receiver class,
     *   like for [formatFunction].
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
        owner: KmClass? = null,
        contextPackage: String? = null,
        classTypeParameters: Map<Int, String> = emptyMap(),
        bodyReceiverSubstitution: Map<Int, String>? = null,
        deprecation: Deprecation? = null,
        companionDeprecation: Deprecation? = null,
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner),
        isInheritedByFinalClass: Boolean = false,
        declaration: KmProperty = property
    ): List<String> = buildList {
        val typeParameters = classTypeParameters + property.typeParameters.namesById() + bodyReceiverSubstitution.orEmpty()
        // the type parameters standing for the ones of the class are not declared
        val declaredTypeParameters = property.typeParameters.filter { it.id !in bodyReceiverSubstitution.orEmpty() }
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
            property.receiverParameterType?.takeIf { bodyReceiverSubstitution == null }?.let { receiverType ->
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
     * The names of the type parameters which the [kmClass] can refer to, by their ids,
     * including the ones of its outer classes when it is an inner class, see [outerTypeParametersOf],
     * which can be declared in a class other than the one listing it, like a sealed subclass.
     */
    private fun typeParametersInScope(kmClass: KmClass, declarations: ClassDeclarations): Map<Int, String> =
        outerTypeParametersOf(kmClass, declarations).flatMap { it.typeParameters.entries }.associate { it.key to it.value } +
            kmClass.typeParameters.namesById()

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
        typeParameters: Map<Int, String>,
        contextPackage: String? = null
    ): String {
        val constraints = declarations.filter { it.upperBounds.size > 1 }.flatMap { typeParameter ->
            typeParameter.upperBounds.map { "${escapeIdentifier(typeParameter.name)} : ${formatType(it, contextPackage, typeParameters)}" }
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
     * Format a [property] described by kotlin-reflect, like a function, see [formatReflectedFunction].
     */
    internal fun formatReflectedProperty(property: KProperty<*>): List<String> {
        val kmProperty = property.toKmProperty(ReflectedTypeParameters.classTypeParameters(emptyList()))
        return formatProperty(kmProperty, setterVisibility = kmProperty.setter?.visibility)
    }

    internal fun formatType(
        type: KmType,
        contextPackage: String? = null,
        typeParameters: Map<Int, String> = emptyMap()
    ): String = MetadataTypeNotation(contextPackage, typeParameters).format(type)

    /**
     * Formats a receiver [type], parenthesizing a function type, see [TypeNotation.formatReceiver].
     */
    private fun formatReceiverType(
        type: KmType,
        contextPackage: String?,
        typeParameters: Map<Int, String>
    ): String = MetadataTypeNotation(contextPackage, typeParameters).formatReceiver(type)

    /**
     * The notation of the types of the Kotlin metadata.
     *
     * @param contextPackage the package of the rendered declaration, whose types are written with simple names.
     * @param typeParameters the names of the type parameters in scope, by their ids.
     */
    private inner class MetadataTypeNotation(
        private val contextPackage: String?,
        private val typeParameters: Map<Int, String>
    ) : TypeNotation<KmType>() {

        override fun decodeFunctionType(type: KmType): FunctionType<KmType>? = type.decodeFunctionType()

        override fun isMarkedSuspend(type: KmType): Boolean = type.isSuspend

        override fun isMarkedNullable(type: KmType): Boolean = type.isNullable

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
                is KmClassifier.TypeParameter -> typeParameters[classifier.id]?.let(::escapeIdentifier) ?: "T"
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
     * which might make abstract again the `f()` with a default body of a superinterface.
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
            Modality.FINAL -> if (isOverride && isOwnerOpenForOverrides) +"final "
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

    private fun isOverride(owner: KmClass?, function: KmFunction): Boolean =
        owner != null && overrideResolver.isOverride(owner, function)

    /**
     * The override of a supertype function by the [function], or `null` if it is not an override.
     */
    private fun override(owner: KmClass?, function: KmFunction): OverrideResolver.FunctionOverride? =
        owner?.let { overrideResolver.override(it, function) }

    private fun isOverride(owner: KmClass?, property: KmProperty): Boolean =
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

        return ClasspathScan(
            extensionIndex = funcIndex,
            extensionPropertyIndex = propIndex,
            classIndex = clsIndex,
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
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
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
            membersHidingMethods = membersHidingMethods.toSet()
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
        val membersHidingMethods: Set<String> = emptySet()
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
        return transientFailures.operation { resolve(name) }?.render(name, page, pageSize)
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
     */
    class Extension(val source: String, val lines: List<String>) : ClassItem, MemberItem

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
 * A class listed with its [header], the declaration line without the body, followed by the [items]:
 * the enum entries, and the members of the class body, see [ListingItem.ClassBodyItem],
 * then the extensions, see [ListingItem.Extension].
 *
 * @param context the comment rendered before the [header] on every page, see `protectedContext`.
 * @param isEnum whether the class is an enum class, whose members are separated from its entries with a semicolon.
 */
private class ClassListing(
    val header: String,
    val context: String?,
    val isEnum: Boolean,
    override val items: List<ListingItem.ClassItem>
) : Resolved<ListingItem.ClassItem> {

    /**
     * The class body is shown only on a page with entries or members, and the semicolon separating the members
     * of an enum class from its entries on every such page without entries, as the last entry carries it.
     * A class without a body still has its header shown on the first page, e.g. to show the constructor.
     */
    override fun renderPage(page: Page<ListingItem.ClassItem>): List<String> = buildList {
        context?.let { add(it) }
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
            context: String? = null,
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
    private val context: String?
) : Resolved<ListingItem.MemberItem> {

    override fun renderPage(page: Page<ListingItem.MemberItem>): List<String> = buildList {
        if (context != null && page.items.any { it !is ListingItem.Extension }) add(context)
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
         * @param context the comment rendered first on every page with the members of the class,
         *   not only its extensions, see `protectedContext`.
         */
        fun of(items: List<ListingItem.MemberItem>, context: String? = null): MemberListing? =
            if (items.isEmpty()) null else MemberListing(items, context)

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
 * The extensions of this index on a type parameter with multiple bounds, see [receiverBoundsOf], under each of them,
 * looked up under every supertype of a class, see `listedFor`, unlike the many other ones.
 */
private fun <E : ReceiverBounded> Map<String, List<E>>.withMultipleBounds(): Map<String, List<E>> =
    mapValues { (_, extensions) -> extensions.filter { it.receiverBounds.isNotEmpty() } }.filterValues { it.isNotEmpty() }

/**
 * The substitution of the type parameters of the [kmClass] with the type arguments of its [type], by their ids,
 * or `null` if they do not match, like with a star projection.
 */
private fun substitutionOf(kmClass: KmClass, type: KmType): Map<Int, KmType>? {
    if (kmClass.typeParameters.size != type.arguments.size) return null
    return kmClass.typeParameters.zip(type.arguments).associate { (typeParameter, argument) ->
        typeParameter.id to (argument.type ?: return null)
    }
}

/**
 * The variance of a type argument of a type parameter of the [declared] variance, [projected] at the use site,
 * like `out` of `Box<out Number>`, or `null` if the projection contradicts it, like `in` of an `out` type parameter,
 * which matches any type then, like a star projection.
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
 * like a nullable one in a covariant position, which is a supertype of the not nullable one.
 */
private fun isNullabilityMatched(expected: KmType, actual: KmType, variance: KmVariance): Boolean = when (variance) {
    KmVariance.INVARIANT -> expected.isNullable == actual.isNullable
    KmVariance.OUT -> expected.isNullable || !actual.isNullable
    KmVariance.IN -> actual.isNullable || !expected.isNullable
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
