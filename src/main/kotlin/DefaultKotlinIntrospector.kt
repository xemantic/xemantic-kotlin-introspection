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
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Attributes
import java.util.jar.JarFile
import kotlin.metadata.*
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.getterSignature
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
import kotlin.reflect.jvm.javaMethod
import kotlin.time.measureTime
import kotlin.metadata.jvm.Metadata as jvmMetadata

/**
 * Represents an indexed extension function with its source location.
 */
internal data class IndexedExtension(
    val function: KmFunction,
    val sourceClass: String,  // fully qualified class name where it's defined
    val source: String,  // the package, or the object, to import it from
    val isObjectMember: Boolean,  // whether the source is an object
    val deprecation: Deprecation? = null,  // never a hidden one, as the hidden extensions are not indexed
    // annotated with @kotlin.internal.HidesMembers, called instead of a member, see jvmOnlyFunctionsNotHidden
    val hidesMembers: Boolean = false,
    override val receiverBounds: Set<String> = emptySet()
) : ReceiverBounded

/**
 * An indexed extension listed only for the classes which are subtypes of all its [receiverBounds].
 */
internal sealed interface ReceiverBounded {

    /**
     * The bounds of a type parameter receiver with multiple bounds, see [receiverBoundsOf], or none.
     */
    val receiverBounds: Set<String>

}

/**
 * Represents an indexed extension property with its source location.
 */
internal data class IndexedExtensionProperty(
    val property: KmProperty,
    val sourceClass: String,  // fully qualified class name where it's defined
    val source: String,  // the package, or the object, to import it from
    val isObjectMember: Boolean,  // whether the source is an object
    val setterVisibility: Visibility?,  // when its setter can be called, see apiSetterVisibility
    // never a hidden one, as the hidden extensions are not indexed, but maybe hiding an accessor, see Deprecation.accessors
    val deprecation: Deprecation? = null,
    override val receiverBounds: Set<String> = emptySet()
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
 */
private fun classScope(kmClass: KmClass, isUsable: Boolean, deprecations: Deprecations): ClassScope {
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
            kmClass.sealedSubclasses
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
        // read directly, without loading the class, like a DefaultImpls class probed for every interface
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

        private const val ANY_CLASS_NAME = "kotlin/Any"

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
                    // the class told by its own scope, and the enclosing ones by its binary name, see locatedNameOf,
                    // as it might not be the one of its metadata name, like when relocated by shading
                    val apiClass = foundClass.scope?.let {
                        apiClasses.apiClass(locatedNameOf(foundClass.binaryName, kmClass.name), it)
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

        /**
         * The deprecation of the class itself, read from its class file,
         * or reflected, when the class file cannot be read, see [reflectedClassFile].
         */
        override val classDeprecation: Deprecation? get() = (classFile ?: reflectedClassFile).classDeprecation

        // parsed once, and only when needed, as some classes are loaded only for the Java reflection
        val metadata: KotlinClassMetadata? by lazy {
            (if (clazz != null) clazz.getAnnotation(Metadata::class.java) else classFile?.metadata)
                ?.let { parseMetadata(it) }
        }

        override val kmClass: KmClass? get() = (metadata as? KotlinClassMetadata.Class)?.kmClass

        /**
         * The `DefaultImpls` class file of an interface, or of an annotation class, holding the synthetic methods
         * for the annotations of its properties, unless compiled with `-jvm-default=no-compatibility`,
         * read only when needed, and again after a failure which might be transient, see [FinalOnce].
         */
        private val defaultImplsFile: ClassFile? get() = defaultImplsFileRead.value

        private val defaultImplsFileRead = FinalOnce { readClassFile("$binaryName\$$DEFAULT_IMPLS") }

        // the deprecation of a declaration of this class, see ClassFile, reflected when the class file cannot be read,
        // see reflectedClassFile, like the one of a hidden constructor, telling whether the class can be subclassed

        override fun deprecation(function: KmFunction): Deprecation? = (classFile ?: reflectedClassFile).deprecation(function)

        override fun deprecation(constructor: KmConstructor): Deprecation? =
            (classFile ?: reflectedClassFile).deprecation(constructor)

        override fun deprecation(property: KmProperty): Deprecation? {
            val classFile = classFile ?: return reflectedClassFile.deprecation(property)
            // the synthetic method for the annotations of an interface property is rather in its DefaultImpls
            val annotationsFile = if (classFile.hasAnnotationsInDefaultImpls(property)) defaultImplsFile else classFile
            return classFile.deprecation(property, annotationsDeprecation = annotationsFile?.annotationsDeprecation(property))
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
                kmClass?.let { classScope(it, isUsableClass(it, classFile.value ?: reflectedClassFile), this) },
                classFile.isFinal
            )
        }

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
     * not when it cannot be read, maybe only for now, see [readClassBytesOutcome].
     */
    private fun classFileAbsence(binaryName: String): FinalOnce<Boolean> = FinalOnce {
        val bytes = readClassBytesOutcome(binaryName)
        TransientFailures.Outcome(bytes.value == null && bytes.isFinal, bytes.isFinal)
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
     * and none of them is, which `KotlinBuiltinsTest` guards.
     * Neither the constructors, nor the class itself, are deprecated.
     */
    private inner class BuiltinClass(name: String, override val kmClass: KmClass) : ClassDeclarations {

        // reflected only when needed, see reflectedBuiltinClass
        private val kClass: KClass<*>? by lazy { reflectedBuiltinClass(name) }

        override val clazz: Class<*>? get() = null

        override val classDeprecation: Deprecation? get() = null

        override val scope: ClassScope by lazy { classScope(kmClass, isUsable = true, deprecations = this) }

        private val typeParameterNames = kmClass.typeParameters.namesById()

        /**
         * The functions declared by the [kClass], also the JVM-only ones, by their signatures, see [signatureOf],
         * converted to the Kotlin metadata, see [toKmFunction].
         */
        private val reflectedFunctions: Map<String, ReflectedFunction> by lazy {
            val typeParameters = ReflectedTypeParameters.classTypeParameters(kmClass.typeParameters)
            kClass?.declaredMemberFunctions.orEmpty()
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
         * The key of the JVM method of a JVM-only [function], see [javaMemberKey], like
         * `java.lang.Throwable#printStackTrace(Ljava/io/PrintStream;)V`, telling its overrides, see [inheritedMembers].
         */
        fun javaMemberKeyOf(function: KmFunction): String? = reflectedValue(default = null) {
            reflectedFunctions[signatureOf(function)]?.kFunction?.javaMethod?.let { method ->
                javaMemberKey(method.declaringClass.name, method.name, AsmType.getMethodDescriptor(method))
            }
        }

        override fun deprecation(constructor: KmConstructor): Deprecation? = null


        override fun deprecation(property: KmProperty): Deprecation? =
            reflectedProperties[property.name]?.let { builtinDeprecation(it.kProperty) }

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
     * for a nested class, its deprecation, and the ones of its methods and constructors, see [reflectedDeprecation],
     * each annotation read on its own, never failing the resolve.
     */
    private fun reflectedClassFile(clazz: Class<*>?): ClassFile {
        fun deprecationOf(element: java.lang.reflect.AnnotatedElement): Deprecation? = reflectedDeprecation(
            listOfNotNull(
                reflectedValue(default = null) { element.getAnnotation(Deprecated::class.java) },
                reflectedValue(default = null) { element.getAnnotation(DeprecatedSinceKotlin::class.java) }
            )
        )
        // by their JVM signatures, like the ones read from a class file, see ClassFile.deprecatedMethods,
        // each member reflected on its own, while none of them is, when a type of any of them cannot be loaded
        val methods = reflectedValue(default = emptyList()) { clazz?.declaredMethods.orEmpty().toList() }.map { method ->
            method to { method.name + AsmType.getMethodDescriptor(method) }
        }
        val constructors = reflectedValue(default = emptyList()) { clazz?.declaredConstructors.orEmpty().toList() }.map {
            constructor -> constructor to { "<init>" + AsmType.getConstructorDescriptor(constructor) }
        }
        return ClassFile(
            metadata = null,
            isSynthetic = clazz?.isSynthetic == true,
            classDeprecation = clazz?.let { deprecationOf(it) },
            deprecatedMethods = (methods + constructors).mapNotNull { (member, signature) ->
                reflectedValue(default = null) { deprecationOf(member)?.let { signature() to it } }
            }.toMap()
        )
    }

    /**
     * The lookups of the recently found classes by their binary names, also of the ones not found,
     * so that neither a class is loaded, nor its metadata parsed, nor a missing class looked up, more than once,
     * like for every nested class listed on every page of a paged resolve.
     * It is bounded, as a found class keeps its parsed metadata, so that a long-lived introspector
     * does not keep the metadata of every class ever looked up,
     * while the [overrideResolver] keeps the declarations it needs in its own bounded caches.
     * The outcome of a lookup which might be transient is not cached, see [lookUpClass].
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
     * where a lookup whose miss is not cached, so not probed either, see [lookUpClass], is neither a cache entry,
     * nor a failing attempt, see [TransientFailures.attempt], of its own while computed,
     * so that a concurrent lookup of the same class never receives its miss, as if it were probed,
     * nor the miss resets the count of the failing attempts of the probed lookups.
     * Only a class found this way is cached, like by any other lookup.
     */
    private fun lookUpClassByBinaryName(binaryName: String, isMissCached: Boolean = true): ClassLookup {
        if (!isMissCached) return lookUpClassUnlessCachedMiss(binaryName)
        val computed = foundClasses.getOrCompute(binaryName) {
            val lookup = attemptLookUpClass("class $binaryName", binaryName, isMissProbed = true)
            BoundedCache.Computed(lookup, isCacheable = lookup.isFinal)
        }
        // also when computed by another thread
        return computed.value.markedUnlessFinal()
    }

    private fun lookUpClassUnlessCachedMiss(binaryName: String): ClassLookup {
        foundClasses.getIfCached(binaryName)?.let { return it }
        val lookup = attemptLookUpClass("unprobed class $binaryName", binaryName, isMissProbed = false)
        if (!lookup.isFinal || lookup.foundClass == null) return lookup.markedUnlessFinal()
        // the class cached meanwhile by another thread, if any, which is then shared, unlike a miss, see below
        val cached = foundClasses.getOrCompute(binaryName) { BoundedCache.Computed(lookup, isCacheable = true) }
        if (cached.isCacheable && cached.value.foundClass != null) return cached.value
        // rather than a miss cached meanwhile by a concurrent lookup, like after a transient failure, deemed final,
        // which each concurrent lookup by name replaces, the last one staying cached, all of the same class
        foundClasses.put(binaryName, lookup)
        return lookup
    }

    /**
     * The [lookUpClass] of the class, as the attempt of the [key], see [TransientFailures.attempt].
     */
    private fun attemptLookUpClass(key: String, binaryName: String, isMissProbed: Boolean): ClassLookup {
        val outcome = transientFailures.attempt(key) {
            val lookup = lookUpClass(binaryName, isMissProbed)
            TransientFailures.Outcome(lookup, lookup.isFinal)
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
     */
    private class ClassLookup(
        val foundClass: FoundClass?,
        val isFinal: Boolean,
        val unloadableClassBytes: ByteArray? = null,
        private val classFileAbsence: FinalOnce<Boolean>? = null
    ) {
        val isAbsent: Boolean get() = foundClass == null && classFileAbsence?.value == true

        fun asFinal(): ClassLookup =
            if (isFinal) this else ClassLookup(foundClass, isFinal = true, unloadableClassBytes, classFileAbsence)
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
    } catch (_: ClassNotFoundException) {
        // also thrown when the class file is there, but cannot be read, like by a URLClassLoader after an IOException
        ClassLookup(
            null,
            isFinal = !isMissProbed || !classLoader.hasClassFile(binaryName),
            classFileAbsence = classFileAbsence(binaryName)
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
     * and a [ClassCircularityError], which is spurious when the classes are loaded in parallel,
     * while a genuine one is deemed final after a few attempts anyway, see [TransientFailures].
     * Any other one, like a [NoClassDefFoundError] of a missing supertype, an [UnsupportedClassVersionError],
     * a [ClassFormatError], or an [IncompatibleClassChangeError], is told by the class file itself, so it is final.
     */
    private fun isTransientLinkageError(e: LinkageError): Boolean =
        e.javaClass == LinkageError::class.java || e is ClassCircularityError

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
        val listedExtensions = listedExtensions(kmClass, className, apiClass, declarations, isBuiltin)
        val members = declaredMembers(
            kmClass, declarations, memberName, apiClass, contextPackage,
            jvmOnlyFunctions = jvmOnlyFunctionsNotHidden(kmClass, declarations, listedExtensions)
        )

        val companionMembers = apiCompanionOf(kmClass, apiClass)?.let { companion ->
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
        val inherited = inheritedMembers(kmClass, declarations, memberName, apiClass, contextPackage, hidingExtensions)

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
     * The members named [memberName] which the [kmClass] declares, each on its lines, like in its listing,
     * see [apiMembersNamed].
     */
    private fun declaredMembers(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        memberName: String,
        apiClass: ApiClass,
        contextPackage: String,
        jvmOnlyFunctions: List<KmFunction>
    ): List<List<String>> {
        val isOpenForOverrides = isOpenForOverrides(kmClass, apiClass)
        return apiMembersNamed(kmClass, declarations, memberName, apiClass, jvmOnlyFunctions).map { member ->
            formatMember(member, kmClass, declarations, apiClass, contextPackage, isOpenForOverrides)
        }
    }

    /**
     * A property, or a function, of a class.
     */
    private sealed interface Member {
        class Property(val property: KmProperty) : Member
        class Function(val function: KmFunction) : Member
    }

    /**
     * The members named [memberName] which the [kmClass] declares, which are part of the API in the [scope], see [inApi]:
     * the properties, and the functions, followed by the JVM-only members of a built-in type, see [BuiltinClass].
     *
     * @param scope the class of the API telling which of the members are part of it,
     *   the [kmClass] itself, or its subclass inheriting them, see [inheritedMembers].
     * @param jvmOnlyFunctions the JVM-only functions of a built-in type, see [jvmOnlyFunctionsNotHidden].
     */
    private fun apiMembersNamed(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        memberName: String,
        scope: ApiClass,
        jvmOnlyFunctions: List<KmFunction> = (declarations as? BuiltinClass)?.jvmOnlyFunctions.orEmpty()
    ): List<Member> {
        val builtinClass = declarations as? BuiltinClass
        val properties = kmClass.properties.filter { it.name == memberName } +
            builtinClass?.jvmOnlyProperties.orEmpty().filter { it.name == memberName }
        val functions = kmClass.functions.filter { it.name == memberName } +
            jvmOnlyFunctions.filter { it.name == memberName }
        return properties.inApi(scope, declarations).map { Member.Property(it) } +
            functions.inApi(scope, declarations).map { Member.Function(it) }
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
        isInheritedByInterface: Boolean = false
    ): List<String> {
        // a member inherited by an interface is rendered as within it, like the ones of kotlin.Any
        val isInterface = kmClass.kind == ClassKind.INTERFACE || isInheritedByInterface
        val typeParameters = typeParametersInScope(kmClass, declarations)
        return when (member) {
            is Member.Property -> formatApiProperty(
                member.property, scope, declarations,
                inInterface = isInterface, owner = kmClass, contextPackage = contextPackage, classTypeParameters = typeParameters,
                isOwnerOpenForOverrides = isOwnerOpenForOverrides, isInheritedByFinalClass = isInheritedByFinalClass
            )
            is Member.Function -> listOf(
                formatFunction(
                    member.function, inInterface = isInterface, owner = kmClass, contextPackage = contextPackage,
                    classTypeParameters = typeParameters, deprecation = declarations.deprecation(member.function),
                    isOwnerOpenForOverrides = isOwnerOpenForOverrides, isInheritedByFinalClass = isInheritedByFinalClass
                )
            )
        }
    }

    /**
     * A [member] named like the one looked up, declared by a supertype, the [kmClass] of the [declarations],
     * which is inherited, unless a member of another supertype overrides it, see [inheritedMembers].
     *
     * @param key the key of the member, see [memberKey].
     * @param overridden the keys of the members of the supertypes of the [kmClass] which the [member] overrides.
     */
    private class InheritedCandidate(
        val kmClass: KmClass,
        val declarations: ClassDeclarations,
        val member: Member,
        val key: String,
        val overridden: Set<String>,
        val javaKey: String? = null
    ) {

        /**
         * The [key] without the class, the same for the members of the same signature of unrelated supertypes.
         */
        val signature: String = key.substringAfter('#')

        val returnType: KmType = when (member) {
            is Member.Property -> member.property.returnType
            is Member.Function -> member.function.returnType
        }

    }

    /**
     * The members named [memberName] which the [kmClass] inherits from its supertypes, and does not override,
     * each under the `// inherited from …` header of the supertype declaring it, or none.
     *
     * The supertypes are walked breadth-first through their cached declarations, and `kotlin.Any`, the supertype of every class,
     * last, also when it is reached only through Java interfaces. Their members of the name are merged,
     * but the ones which a member of another supertype overrides, see [OverrideResolver.overriddenMembers],
     * so that the nearest declaration of each signature wins, like an override declared by an intermediate class,
     * while an overload which it does not override is still inherited from further up.
     * Of the members of the same signature, see [InheritedCandidate.signature], or overriding the same member,
     * declared by unrelated supertypes, the one of the most specific return type wins, see [compareSpecificity],
     * like the `MutableIterator<E>` of `MutableCollection.iterator()` over the `Iterator<E>` of `List.iterator()`
     * for `MutableList`, as of the intersection override the compiler infers,
     * or the first one, when they are equally specific, while all of them are inherited when they cannot be compared.
     * The members are rendered as declared in the supertype, with its own type parameters, like `fun get(index: Int): E`
     * of `kotlin.collections.List`, the type arguments of the supertype not substituted,
     * and, inherited by a class which the user code cannot subclass, see [ApiClass.isOpen], neither `open`, nor `final`,
     * like its own members.
     * A Java supertype, which is not rendered, is walked through to its supertypes,
     * like `java.util.List`, seen in Kotlin as the built-in `kotlin.collections.MutableList`.
     * A supertype which is not part of the API, see [ApiClasses], is walked through too.
     * Which members are part of the API is decided by the inheriting class, the [scope],
     * like a protected member, inherited by a final class, which is not.
     * The members which the [kmClass] declares, see [apiMembersNamed], override the ones they match like any other override,
     * so that only the inherited overloads are listed next to them, like `add(element: E)` of `MutableList`
     * next to the `add(index: Int, element: E)` which `AbstractMutableList` declares.
     * A qualified [memberName], like `Nested.member`, names no member, so the supertypes are not walked then.
     * The JVM-only members of a built-in supertype, see [BuiltinClass.jvmOnlyFunctions], are overridden
     * like the Java members, see [OverrideResolver.overriddenJavaMembers], and never inherited when hidden
     * by an extension, see [jvmOnlyFunctionsNotHidden], which is added to the [hidingExtensions] instead,
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
        if ('.' in memberName) return emptyList()
        val visited = hashSetOf(kmClass.name, ANY_CLASS_NAME)
        val pending = ArrayDeque(supertypeMetadataNames(kmClass, declarations))
        val candidates = mutableListOf<InheritedCandidate>()
        fun collect(name: String) {
            val supertype = classDeclarations(name) ?: return
            val superKmClass = supertype.kmClass
            pending.addAll(supertypeMetadataNames(superKmClass, supertype))
            val superApiClass = superKmClass?.let { apiClasses.apiClass(it.name) } ?: return
            val jvmOnlyFunctions = (supertype as? BuiltinClass)?.jvmOnlyFunctions.orEmpty()
            val jvmOnlyFunctionsNotHidden = if (jvmOnlyFunctions.any { it.name == memberName }) {
                val hiding = listedExtensions(
                    superKmClass, superKmClass.name.replace('/', '.'), superApiClass, supertype, isBuiltin = true
                ).functions.filter { it.hidesMembers && it.function.name == memberName }
                val notHidden = jvmOnlyFunctionsNotHidden(superKmClass, supertype, ListedExtensions(hiding, emptyList()))
                if (notHidden.size < jvmOnlyFunctions.size) hidingExtensions += hiding
                notHidden
            } else {
                jvmOnlyFunctions
            }
            apiMembersNamed(superKmClass, supertype, memberName, scope, jvmOnlyFunctionsNotHidden).forEach { member ->
                candidates += when (member) {
                    is Member.Property -> InheritedCandidate(
                        superKmClass, supertype, member, memberKey(superKmClass, member.property),
                        overrideResolver.overriddenMembers(superKmClass, member.property)
                    )
                    is Member.Function -> InheritedCandidate(
                        superKmClass, supertype, member, memberKey(superKmClass, member.function),
                        overrideResolver.overriddenMembers(superKmClass, member.function),
                        javaKey = (supertype as? BuiltinClass)?.javaMemberKeyOf(member.function)
                    )
                }
            }
        }
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (visited.add(name)) collect(name)
        }
        if (kmClass.name != ANY_CLASS_NAME) collect(ANY_CLASS_NAME)
        if (candidates.isEmpty()) return emptyList()

        // the declared members override the inherited ones, matched by the overridden keys,
        // or else, for a declared override whose overridden Kotlin members are not told, like of a Java supertype,
        // by the same signature, compared as written, without substituting the type arguments of the supertypes
        val overridden = candidates.flatMapTo(HashSet()) { it.overridden }
        val overriddenJavaMembers = HashSet<String>()
        val declaredSignatures = HashSet<String>()
        apiMembersNamed(kmClass, declarations, memberName, scope).forEach { member ->
            if (member is Member.Function && candidates.any { it.javaKey != null }) {
                overriddenJavaMembers += overrideResolver.overriddenJavaMembers(kmClass, member.function)
            }
            val (keys, isOverride) = when (member) {
                is Member.Property -> overrideResolver.overriddenMembers(kmClass, member.property) to
                    { isOverride(kmClass, member.property) }
                is Member.Function -> overrideResolver.overriddenMembers(kmClass, member.function) to
                    { isOverride(kmClass, member.function) }
            }
            overridden += keys
            if (keys.isEmpty() && isOverride()) {
                declaredSignatures += when (member) {
                    is Member.Property -> memberKey(kmClass, member.property)
                    is Member.Function -> memberKey(kmClass, member.function)
                }.substringAfter('#')
            }
        }
        val survivors = candidates.filter {
            it.key !in overridden && it.signature !in declaredSignatures && it.javaKey !in overriddenJavaMembers
        }
        val inherited = survivors.filterIndexed { index, candidate ->
            survivors.withIndex().none { (rivalIndex, rival) ->
                // the members of the same supertype are its overloads, never competing
                rivalIndex != index && rival.kmClass.name != candidate.kmClass.name &&
                    (rival.member is Member.Property) == (candidate.member is Member.Property) &&
                    (rival.signature == candidate.signature || rival.overridden.any { it in candidate.overridden }) &&
                    when (val specificity = compareSpecificity(rival.returnType, candidate.returnType)) {
                        null -> false
                        0 -> rivalIndex < index
                        else -> specificity > 0
                    }
            }
        }

        val isOpenForOverrides = isOpenForOverrides(kmClass, scope)
        return inherited.map { candidate ->
            ListingItem.InheritedMember(
                candidate.kmClass.name.replace('/', '.'),
                formatMember(
                    candidate.member, candidate.kmClass, candidate.declarations, scope, contextPackage,
                    isOwnerOpenForOverrides = isOpenForOverrides,
                    // also the open member of an interface, unless inherited by an interface,
                    // where it is rendered as open, like its own members, telling it apart from an abstract one
                    isInheritedByFinalClass = !isOpenForOverrides && kmClass.kind != ClassKind.INTERFACE,
                    isInheritedByInterface = kmClass.kind == ClassKind.INTERFACE
                )
            )
        }
    }

    /**
     * Whether the type [a] is more specific than the type [b], a positive number, or less, a negative one,
     * or equally, `0`, like the same class of the same nullability, or two type parameters,
     * or `null` if they cannot be compared, as told by their classifiers, and their nullability, not by their type arguments.
     */
    private fun compareSpecificity(a: KmType, b: KmType): Int? {
        val aClassifier = a.classifier
        val bClassifier = b.classifier
        if (aClassifier is KmClassifier.TypeParameter && bClassifier is KmClassifier.TypeParameter) return 0
        if (aClassifier !is KmClassifier.Class || bClassifier !is KmClassifier.Class) return null
        if (aClassifier.name == bClassifier.name) return when {
            a.isNullable == b.isNullable -> 0
            b.isNullable -> 1
            else -> -1
        }
        fun isSubtype(name: String, of: String): Boolean = of == ANY_CLASS_NAME ||
            (builtinClasses[name] ?: loadKmClass(name))?.let { kmClass ->
                of.replace('/', '.') in supertypeNames(kmClass, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) })
            } == true
        return when {
            isSubtype(aClassifier.name, bClassifier.name) -> 1
            isSubtype(bClassifier.name, aClassifier.name) -> -1
            else -> null
        }
    }

    /**
     * The metadata names of the direct supertypes of a class, like `kotlin/collections/List`:
     * the ones of its [kmClass], or, of a Java class, without one, the ones its JVM class, if loaded, declares,
     * mapped to the built-in types, like `java.util.List` to `kotlin/collections/MutableList`,
     * see [OverrideResolver.builtinNameOfJavaClass].
     */
    private fun supertypeMetadataNames(kmClass: KmClass?, declarations: ClassDeclarations): List<String> {
        if (kmClass != null) return kmClass.supertypes.mapNotNull { (it.classifier as? KmClassifier.Class)?.name }
        val clazz = declarations.clazz ?: return emptyList()
        return reflectedValue(default = emptyList()) {
            (listOfNotNull(clazz.superclass) + clazz.interfaces).map {
                overrideResolver.builtinNameOfJavaClass(it.name) ?: kotlinClassName(it)
            }
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
     * only the ones of the public `kotlin.*` packages, see [isPublicKotlinPackageSource],
     * on the class and on all its supertypes, except `Any`, also the ones from the `kotlin.*` packages,
     * whose extensions are, after all, the ones of the standard library,
     * but the ones of any library on the companion object itself, like `fun Int.Companion.serializer()`
     * of kotlinx.serialization, as there are few of them, each extending that very type.

     * For any other class, all of them, on the class and its [userSupertypes], see [extensionReceiverNames],
     * not to flood its listing with the generic extensions of the standard library.
     * Either way, the ones on a type parameter with multiple bounds only when the class satisfies all of them,
     * see [receiverBoundsOf], also when none of them is among the [userSupertypes], see [listedFor],
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
        val listed = if (isBuiltin) {
            val supertypeNames = supertypeNames(kmClass, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) }) {
                it != "kotlin.Any"
            }
            val listed = extensionsListedFor(listOf(className) + supertypeNames, supertypeNames + className)
            // a library extends the companion object of a built-in type only deliberately, like with serializer()
            val isCompanion = kmClass.kind == ClassKind.COMPANION_OBJECT
            fun isListed(sourceClass: String, receiver: KmType?) = isPublicKotlinPackageSource(sourceClass) ||
                (isCompanion && !isInternalPackageSource(sourceClass) &&
                    (receiver?.classifier as? KmClassifier.Class)?.name == kmClass.name)
            ListedExtensions(
                listed.functions.filter { isListed(it.sourceClass, it.function.receiverParameterType) },
                listed.properties.filter { isListed(it.sourceClass, it.property.receiverParameterType) }
            )
        } else {
            extensionsListedFor(extensionReceiverNames(kmClass, className), satisfiedBoundsOf(kmClass, className))
        }
        val builtinClass = declarations as? BuiltinClass
        return listed.withoutShadowed(kmClass) { names ->
            (kmClass.functions + builtinClass?.jvmOnlyFunctions.orEmpty())
                .filter { it.name in names && it.receiverParameterType == null }
                .inApi(apiClass, declarations)
        }
    }

    /**
     * These extensions without the ones on a type parameter pinned to the [kmClass],
     * like `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T)` for `Int`, see [isComparableOnlyToItself],
     * shadowed by a more specific overload taking the same parameters, see [shadows],
     * like `fun Int.coerceAtLeast(minimumValue: Int)`, called instead,
     * unless the type argument is given explicitly, like `5.coerceAtLeast<Int>(3)`:
     * a member function of the class, given by [memberFunctions] their names, other than a member extension,
     * or one of these extensions on the class itself, imported from the same package as the shadowed one.
     * Any other extension on a type parameter is listed, as the type parameter can be inferred as a supertype of the class,
     * like for `ps.foo(object : PD {})` of `fun <T : PD> T.foo(value: T)`, next to the member `foo(value: PS)`.
     */
    private fun ListedExtensions.withoutShadowed(
        kmClass: KmClass,
        memberFunctions: (names: Set<String>) -> List<KmFunction>
    ): ListedExtensions {
        val pinned = functions.filter { it.function.hasReceiverPinnedByComparable }
        if (pinned.isEmpty() || !isComparableOnlyToItself(kmClass)) return this
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
     * Whether the [kmClass] pins the type parameter `T : Comparable<T>` to itself, see [hasReceiverPinnedByComparable]:
     * a final class, without type parameters, comparable to itself, like `Int`, a `Comparable<Int>`,
     * while none of its proper supertypes is comparable, so that no other type satisfies the bound,
     * as told by the Kotlin metadata, unlike an enum class, comparable through `kotlin.Enum`.
     */
    private fun isComparableOnlyToItself(kmClass: KmClass): Boolean {
        if (kmClass.modality != Modality.FINAL || kmClass.typeParameters.isNotEmpty()) return false
        var isComparableToItself = false
        val pending = ArrayDeque<KmClass>()
        for (supertype in kmClass.supertypes) {
            val name = (supertype.classifier as? KmClassifier.Class)?.name ?: return false
            if (name == COMPARABLE_METADATA_NAME) {
                val argument = supertype.arguments.singleOrNull()?.type ?: return false
                val argumentClassifier = argument.classifier as? KmClassifier.Class ?: return false
                if (argumentClassifier.name != kmClass.name || argument.isNullable) return false
                isComparableToItself = true
            } else {
                pending += builtinClasses[name] ?: loadKmClass(name) ?: return false
            }
        }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            for (supertype in pending.removeFirst().supertypes) {
                val name = (supertype.classifier as? KmClassifier.Class)?.name ?: return false
                if (name == COMPARABLE_METADATA_NAME) return false
                if (visited.add(name)) pending += builtinClasses[name] ?: loadKmClass(name) ?: return false
            }
        }
        return isComparableToItself
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
     * @param outer the [kmClass] as a class of the API, which the companion object is derived from.
     */
    private fun apiCompanionOf(kmClass: KmClass, outer: ApiClass): ApiCompanion? {
        val name = "${kmClass.name}.${kmClass.companionObject ?: return null}"
        val apiClass = apiClasses.nestedApiClass(outer, name) ?: return null
        val companionClass = classDeclarations(name) ?: return null
        return ApiCompanion(companionClass.kmClass ?: return null, companionClass, apiClass)
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
            val source = packageSourceOf(binaryName)
            format(kmPackage, foundClass, binaryName.substringBeforeLast('.', "")).map { ListingItem.Extension(source, it) }
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
     * see [builtinDeprecation], or of a class whose class file cannot be read, see [reflectedClassFile],
     * by the same rule as the one of the annotations read from the class files, see [deprecationVisitor],
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
     * A [value] which kotlin-reflect builds from the metadata of the built-ins, like an annotation,
     * or the companion object of a built-in type, see [reflectedBuiltinClass],
     * or the [default] when it cannot be read, like a parameter with the default value, absent from the metadata,
     * whose getter throws `KotlinReflectionInternalError`, as for the `level` of `@Deprecated` of `Long.toChar()`.
     */
    private inline fun <T> reflectedValue(default: T, value: () -> T): T = try {
        value()
    } catch (_: Exception) {
        default
    } catch (e: Error) {
        if (e is VirtualMachineError) throw e
        default
    }

    /**
     * The names of the supertypes of the [kmClass], walked breadth-first, like `kotlin.collections.Collection`,
     * the [included] ones, traversed further through their declarations, given by [kmClassOf] their metadata names,
     * or else through their Java classes, see [javaSupertypeNames].
     * A function type, like `suspend () -> Unit`, is named by its function type class,
     * like `kotlin.coroutines.SuspendFunction0`, see [receiverIndexKeys], and has the supertype `kotlin.Function`.
     */
    private fun supertypeNames(
        kmClass: KmClass,
        kmClassOf: (metadataName: String) -> KmClass?,
        included: (name: String) -> Boolean = { true }
    ): Set<String> {
        val supertypeNames = linkedSetOf<String>()
        // the metadata names of the supertypes to traverse
        val pending = ArrayDeque<String>()
        fun visit(name: String, metadataName: String) {
            val functionTypeClass = FunctionTypeClass.of(name)
            if (functionTypeClass != null) {
                if (included(name)) supertypeNames += name
                functionTypeClass.supertypeNames.forEach { visit(it, kotlinClassName(it)) }
            } else if (included(name) && supertypeNames.add(name)) {
                pending += metadataName
            }
        }
        fun visitSupertypes(supertypes: List<KmType>) {
            for (supertype in supertypes) {
                val classifier = supertype.classifier as? KmClassifier.Class ?: continue
                visit(receiverIndexKeys(supertype).single(), classifier.name)
            }
        }
        visitSupertypes(kmClass.supertypes)
        while (pending.isNotEmpty()) {
            val metadataName = pending.removeFirst()
            val supertypeKmClass = kmClassOf(metadataName)
            if (supertypeKmClass != null) {
                visitSupertypes(supertypeKmClass.supertypes)
            } else {
                javaSupertypeNames(metadataName).forEach { (name, supertypeMetadataName) -> visit(name, supertypeMetadataName) }
            }
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
     * The names of the class and all its supertypes, also the ones from `kotlin.*` and `java.*` packages,
     * unlike the [userSupertypes], telling whether the class satisfies the multiple bounds of a type parameter receiver,
     * see [receiverBoundsOf].
     */
    private fun satisfiedBoundsOf(kmClass: KmClass, className: String): Set<String> =
        ownExtensionReceiverNames(className).toSet() +
            supertypeNames(kmClass, kmClassOf = { builtinClasses[it] ?: loadKmClass(it) })

    /**
     * The receiver names of the extensions listed for a class, the class and its [userSupertypes],
     * and `kotlin.Comparable`, if the class is comparable to itself, see [isComparableToItself].
     */
    private fun extensionReceiverNames(kmClass: KmClass, className: String): List<String> {
        val userSupertypes = userSupertypes(kmClass)
        val comparable = COMPARABLE_CLASS_NAME.takeIf { isComparableToItself(kmClass, className, userSupertypes) }
        return ownExtensionReceiverNames(className) + userSupertypes + listOfNotNull(comparable)
    }

    /**
     * Whether the [kmClass], named [className], is comparable to itself, or to one of its [userSupertypes],
     * like `class Version : Comparable<Version>`, satisfying the bound `T : Comparable<T>`
     * of the extensions of the standard library on `Comparable`, like `coerceAtLeast`,
     * which are few and widely used, unlike the generic extensions on the other types of the standard library,
     * see [userSupertypes].
     * Told by the Kotlin metadata of the class and of its supertypes, not through a Java supertype.
     * An enum class, comparable to itself through `kotlin.Enum`, is not told to be,
     * as these extensions are rarely called on the enum entries, and would grow the listing of every enum class.
     */
    private fun isComparableToItself(kmClass: KmClass, className: String, userSupertypes: Set<String>): Boolean {
        val comparableTo = userSupertypes + className
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(kmClass))
        while (pending.isNotEmpty()) {
            for (supertype in pending.removeFirst().supertypes) {
                val name = (supertype.classifier as? KmClassifier.Class)?.name ?: continue
                if (name == COMPARABLE_METADATA_NAME) {
                    val argument = supertype.arguments.singleOrNull()?.type?.classifier as? KmClassifier.Class
                    if (argument != null && argument.name.replace('/', '.') in comparableTo) return true
                } else if (!name.startsWith("kotlin/") && !name.startsWith("java/") && visited.add(name)) {
                    loadKmClass(name)?.let { pending += it }
                }
            }
        }
        return false
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
        // like in the listing of the function type class
        val listedExtensions = functionTypeExtensions(className)
        val extensions = extensionSignatures(
            listedExtensions.functions.filter { it.function.name == memberName },
            listedExtensions.properties.filter { it.property.name == memberName },
            contextPackage = null
        )
        return MemberListing.of(members.map { ListingItem.Declaration(listOf(it)) } + extensions)
    }

    /**
     * Whether the extensions declared in the [sourceClass] are shown with the built-in types:
     * only the ones of the public `kotlin.*` packages, not the ones of the other libraries, like the test package,
     * or the ones of the internal packages, like `kotlin.reflect.jvm.internal`.
     * Such a package is not necessarily one of the standard library, it can also be of another library
     * on the classpath, like `kotlin.reflect.full` of kotlin-reflect, whose extensions, like `KClass.memberFunctions`,
     * can be called by the user code depending on it, even if some of them, like `KClass.cast()`,
     * have the same signature as the ones of the standard library, imported from another package.
     */
    private fun isPublicKotlinPackageSource(sourceClass: String): Boolean =
        sourceClass.startsWith("kotlin.") && !isInternalPackageSource(sourceClass)

    /**
     * Whether the [sourceClass] is declared in an internal package, like `kotlin.reflect.jvm.internal`,
     * or `kotlinx.serialization.internal`, not meant to be used by the user code.
     */
    private fun isInternalPackageSource(sourceClass: String): Boolean = sourceClass.split('.').contains("internal")

    /**
     * The `@JvmInline` annotation, which a value class with a single underlying property requires on the JVM.
     * It is rendered inline, so that the class declaration stays on a single line.
     */
    private fun formatJvmInline(isJvmInline: Boolean): String = if (isJvmInline) "@JvmInline " else ""

    /**
     * The modifiers and the [kind] of the class, like `data class ` or `object `.
     */
    private fun formatClassPrefix(kmClass: KmClass, kind: ClassKind): String = buildString {
        val isInterface = kmClass.kind == ClassKind.INTERFACE

        // a value class with multiple underlying properties (-Xvalue-classes) is not annotated
        +formatJvmInline(kmClass.isValue && kmClass.inlineClassUnderlyingPropertyName != null)

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
        val companion = apiCompanionOf(kmClass, apiClass)
        // the ones which are also sealed subclasses of this class are listed as such
        val companionNestedClasses = if (companion != null) {
            listedNestedClasses(companion.kmClass, companion.apiClass, kmClass.sealedSubclasses.toSet())
        } else {
            emptyList()
        }

        val sealedSubclasses = if (isSealed) listedSealedSubclasses(kmClass) else SealedSubclasses.NONE
        val nestedClasses = listedNestedClasses(kmClass, apiClass)

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
                    property.name to formatMember(Member.Property(property), kmClass, foundClass, apiClass, contextPackage, isOpenForOverrides)
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
                function.name to formatMember(Member.Function(function), kmClass, foundClass, apiClass, contextPackage, isOpenForOverrides)
            }

            if (isBuiltin) {
                // the members of a built-in type, sorted by name with its JVM-only members, and its extensions
                // reading like members, the constructors first, like in the source of the standard library
                constructorItems.forEach { add(ListingItem.Declaration(it)) }
                (propertyItems + functionItems + builtinBodyItems(kmClass, foundClass, apiClass, bodyExtensions))
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
     * The overload signatures, see [overloadSignature], of the extensions which a member [function] shadows,
     * being called instead, as it accepts the same arguments:
     * its own one, and the ones where any of its nullable parameters, like the platform type `Any!` of a Java parameter,
     * see [toKmType], is not nullable, like `printStackTrace(java.io.PrintStream)`
     * of the member `printStackTrace(arg0: java.io.PrintStream?)` of `java.lang.Throwable`.
     */
    private fun shadowingSignatures(function: KmFunction, classTypeParameters: Map<Int, String>): List<String> {
        val typeParameters = classTypeParameters + function.typeParameters.namesById()
        return function.valueParameters.fold(listOf(function.name + "(")) { prefixes, parameter ->
            val separator = if (parameter === function.valueParameters.first()) "" else ", "
            val types = listOfNotNull(
                formatType(parameter.type, typeParameters = typeParameters),
                parameter.type.takeIf { it.isNullable }?.let { formatType(it.notNullable(), typeParameters = typeParameters) }
            )
            prefixes.flatMap { prefix -> types.map { prefix + separator + it } }
        }.map { "$it)" }
    }

    /**
     * The [listedExtensions] of a built-in type, the [kmClass], reading like its members, see [bodyReceiverSubstitution],
     * which its body lists, except the ones shadowed by its members, called instead, see [shadowingSignatures],
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
        val memberFunctionSignatures = (kmClass.functions + builtinClass?.jvmOnlyFunctions.orEmpty())
            .flatMapTo(HashSet()) { shadowingSignatures(it, typeParameters) }
        val memberPropertyNames = (kmClass.properties + builtinClass?.jvmOnlyProperties.orEmpty()).mapTo(HashSet()) { it.name }
        val signatures = listedExtensions.functions.filter { !it.isObjectMember }.associateWith { extension ->
            bodyReceiverSubstitution(extension.function, kmClass)
                ?.let { overloadSignature(extension.function, emptyMap(), it) }
                ?.takeIf { extension.hidesMembers || it !in memberFunctionSignatures }
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
     * reading like a member of the same signature, see [shadowingSignatures], which is called instead,
     * like `fun Throwable.addSuppressed(exception: Throwable)` of the member `addSuppressed(arg0: Throwable?)`
     * of `java.lang.Throwable`, while a member of another signature is still called,
     * like `forEach(arg0: java.util.function.Consumer<in T>?)` of `java.lang.Iterable` next to `Iterable<T>.forEach(action)`.
     */
    private fun jvmOnlyFunctionsNotHidden(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        extensions: ListedExtensions
    ): List<KmFunction> {
        val jvmOnlyFunctions = (declarations as? BuiltinClass)?.jvmOnlyFunctions.orEmpty()
        val hidden = extensions.functions.filter { it.hidesMembers && !it.isObjectMember }.mapNotNullTo(HashSet()) { extension ->
            bodyReceiverSubstitution(extension.function, kmClass)
                ?.let { overloadSignature(extension.function, emptyMap(), it) }
        }
        if (hidden.isEmpty()) return jvmOnlyFunctions
        val typeParameters = typeParametersInScope(kmClass)
        return jvmOnlyFunctions.filter { function -> shadowingSignatures(function, typeParameters).none { it in hidden } }
    }

    /**
     * The declarations a built-in type lists in its body, besides its own properties and functions, each with its name:
     * its JVM-only members, see [BuiltinClass], but the ones hidden by its [bodyExtensions], see [jvmOnlyFunctionsNotHidden],
     * and the [bodyExtensions], see [bodyExtensionsOf].
     */
    private fun builtinBodyItems(
        kmClass: KmClass,
        declarations: ClassDeclarations,
        apiClass: ApiClass,
        bodyExtensions: ListedExtensions
    ): List<Pair<String, List<String>>> {
        val contextPackage = contextPackageOf(declarations, kmClass)
        val isOpenForOverrides = isOpenForOverrides(kmClass, apiClass)
        val builtinClass = declarations as? BuiltinClass
        // formatted like the members the kmClass declares, see formatClass
        val jvmOnlyProperties = builtinClass?.jvmOnlyProperties.orEmpty().inApi(apiClass, declarations).map { property ->
            property.name to formatMember(Member.Property(property), kmClass, declarations, apiClass, contextPackage, isOpenForOverrides)
        }
        val jvmOnlyFunctions = jvmOnlyFunctionsNotHidden(kmClass, declarations, bodyExtensions)
            .inApi(apiClass, declarations)
            .map { function ->
                function.name to formatMember(Member.Function(function), kmClass, declarations, apiClass, contextPackage, isOpenForOverrides)
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
        +formatClassPrefix(kmClass, kind).let { if (name.isEmpty()) it.trimEnd() else it + name }
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
     * named by their ids, like `T0`, see [referencedTypeParameterIds].
     */
    private fun outerTypeParametersOf(kmClass: KmClass, declarations: ClassDeclarations): List<OuterTypeParameters> {
        val outers = mutableListOf<OuterTypeParameters>()
        var currentName = (declarations as? FoundClass)?.binaryName?.let { locatedNameOf(it, kmClass.name) } ?: kmClass.name
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
            currentName = outerName
            current = outer
        }
        if (unknown != null) {
            outers[unreadableIndex] = OuterTypeParameters(
                outers[unreadableIndex].name, unknown.sorted().associateWith { "T$it" }
            )
        }
        return outers
    }

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
        val message = deprecation.message.trim()
        val firstLine = message.lineSequence().first().trimEnd()
        // telling that only the reading, or the assigning, of a var is deprecated
        +when (deprecation.target) {
            Deprecation.Target.DECLARATION -> "@Deprecated(\""
            Deprecation.Target.GETTER -> "@get:Deprecated(\""
            Deprecation.Target.SETTER -> "@set:Deprecated(\""
        }
        +firstLine.escapeKotlin('"')
        if (firstLine.length < message.length) +" …"
        +"\""
        deprecation.replaceWith?.let {
            +", ReplaceWith(\""
            +it.escapeKotlin('"')
            +"\")"
        }
        if (deprecation.level != DeprecationLevel.WARNING) +", level = DeprecationLevel.${deprecation.level.name}"
        +") "
        deprecation.accessors.forEach { +formatDeprecation(it) }
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
                        formatProperty(it.property, it.setterVisibility, contextPackage = contextPackage, deprecation = it.deprecation)
                    )
                } +
                functionsBySource[source].orEmpty()
                    .sortedWith(extensionFunctionOrder)
                    .map {
                        ListingItem.Extension(
                            source,
                            listOf(formatFunction(it.function, contextPackage = contextPackage, deprecation = it.deprecation))
                        )
                    }
        }
    }

    /**
     * The sealed subclasses of a sealed class.
     *
     * @param listed the ones which are part of the API, see [listedSealedSubclasses].
     * @param notFound the metadata names of the ones which cannot be found, as their class files are absent.
     * @param notRead the metadata names of the ones whose class files are present, but cannot be read,
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
     */
    private fun listedSealedSubclasses(kmClass: KmClass): SealedSubclasses {
        val notFound = mutableListOf<String>()
        val notRead = mutableListOf<String>()
        var hasOmitted = false
        val listed = kmClass.sealedSubclasses.mapNotNull { subclassName ->
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
            if (subclassName == "${kmClass.name}.${kmClass.companionObject}") return@mapNotNull null
            ListedClass(subclassName.removePrefix("${kmClass.name}.").substringAfterLast('/'), subclass, subclassClass)
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
     * @param apiClass the [kmClass] as a class of the API, which the nested classes are derived from.
     * @param sealedSubclasses the declared sealed subclasses to exclude, the ones of the [kmClass],
     *   or of the class enclosing a companion object, which might be nested in that companion object.
     */
    private fun listedNestedClasses(
        kmClass: KmClass,
        apiClass: ApiClass,
        sealedSubclasses: Set<String> = kmClass.sealedSubclasses.toSet()
    ): List<ListedClass> = kmClass.nestedClasses
        .map { "${kmClass.name}.$it" }
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
     * @param isOwnerOpenForOverrides whether the user code might override the members of the [owner],
     *   see [formatModalityModifiers], by default unless final.
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
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner),
        isInheritedByFinalClass: Boolean = false
    ): String = buildString {
        val typeParameters = classTypeParameters + function.typeParameters.namesById() + bodyReceiverSubstitution.orEmpty()
        // the type parameters standing for the ones of the class are not declared
        val declaredTypeParameters = function.typeParameters.filter { it.id !in bodyReceiverSubstitution.orEmpty() }

        +formatDeprecation(deprecation)
        +formatContextParameters(function.contextParameters, contextPackage, typeParameters)
        +formatVisibility(function.visibility)

        val override = override(owner, function)
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
     * with its deprecation told once by the [deprecations], also deciding whether its setter is part of the API.
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
        isInheritedByFinalClass: Boolean = false
    ): List<String> {
        val deprecation = deprecations?.deprecation(property)
        return formatProperty(
            property, property.apiSetterVisibility(scope, deprecation), inInterface, owner, contextPackage, classTypeParameters,
            deprecation = deprecation, isOwnerOpenForOverrides = isOwnerOpenForOverrides,
            isInheritedByFinalClass = isInheritedByFinalClass
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
     * @param isOwnerOpenForOverrides whether the user code might override the members of the [owner], like for [formatFunction].
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
        isOwnerOpenForOverrides: Boolean = isOpenForOverridesByDefault(owner),
        isInheritedByFinalClass: Boolean = false
    ): List<String> = buildList {
        val typeParameters = classTypeParameters + property.typeParameters.namesById() + bodyReceiverSubstitution.orEmpty()
        // the type parameters standing for the ones of the class are not declared
        val declaredTypeParameters = property.typeParameters.filter { it.id !in bodyReceiverSubstitution.orEmpty() }
        val isOverride = isOverride(owner, property)
        val declaration = propertyDeclaration(property, setterVisibility, isOverride, deprecation)
        val renderedSetterVisibility = declaration.setterVisibility
        add(buildString {
            +formatDeprecation(declaration.deprecation)
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
     * This type, but not nullable, as a copy, as the types of the metadata are shared.
     */
    private fun KmType.notNullable(): KmType = KmType().also { type ->
        type.classifier = classifier
        type.arguments += arguments
        type.abbreviatedType = abbreviatedType
        type.outerType = outerType
        type.flexibleTypeUpperBound = flexibleTypeUpperBound
        type.isSuspend = isSuspend
        type.isDefinitelyNonNull = isDefinitelyNonNull
        type.annotations += annotations
    }

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
        val outsideScopes = HashMap<String, ClassScope?>()
        val apiClasses = ApiClasses(
            scopeOf = { name ->
                classes[name]?.scope ?: if (scanner.isScanned(binaryNameOf(name))) null else {
                    // also when it cannot be read, kept as null
                    if (name in outsideScopes) outsideScopes[name] else readClassScope(binaryNameOf(name)).also { outsideScopes[name] = it }
                }
            },
            isAbsent = { name -> !scanner.isScanned(binaryNameOf(name)) && !classLoader.hasClassFile(binaryNameOf(name)) }
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
     */
    private fun scanDirectory(root: File, scanner: ClasspathScanner) {
        val directories = ArrayDeque(listOf(root))
        while (directories.isNotEmpty()) {
            val (subdirectories, files) = directories.removeFirst().listFiles().orEmpty().partition { it.isDirectory }
            directories.addAll(subdirectories)
            scanner.scan(
                files.filter { it.isFile && isIndexedClassFile(it.name) }.map { file ->
                    ClassFileEntry(file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class"), file::readBytes)
                }
            )
        }
    }

    /**
     * What a [scanClasspath] found, in the classpath scan order.
     *
     * @property classes the Kotlin classes found, whether part of the API or not.
     * @property readClassFileCount the number of the class files read.
     * @property skippedClassFileCount the number of the nested class files skipped without reading them,
     *   see [NestedClassFiles].
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
            val nestedClassFiles = if (skipNestedClassFiles) NestedClassFiles() else null
            classFiles.sortedBy { nestingDepth(it.name) }.forEach { classFile ->
                if (nestedClassFiles?.isNonKotlinNestedClass(classFile.name) == true) {
                    // still shadowing, like an indexed one, the class of the same name in the next roots,
                    // the name of the path being also the one a class file read declares for it
                    indexedClasses.add(classFile.name.replace('/', '.'))
                    skippedClassFileCount++
                    if (collectSkippedClassFiles) skippedClassFiles += classFile.name
                } else {
                    readClassFileCount++
                    indexClass(classFile.name, classFile.read(), nestedClassFiles)
                }
            }
        }

        /**
         * Indexes the class read from the [bytes] of the class file of the [path], unless a class of the same name
         * is already indexed, telling the [nestedClassFiles], if any, about its nested classes.
         * A class file which cannot be parsed still shadows the class of the same name in the next roots,
         * and is not absent from the classpath, like when resolving its nested classes, see [isAbsent].
         */
        private fun indexClass(path: String, bytes: ByteArray, nestedClassFiles: NestedClassFiles?) {
            val reader = try {
                ClassReader(bytes)
            } catch (_: Exception) {
                indexedClasses.add(path.replace('/', '.'))
                return
            }
            val className = reader.className.replace('/', '.')
            if (!indexedClasses.add(className)) return

            val classFile = try {
                readClassFile(reader, readsNesting = nestedClassFiles != null)
            } catch (_: Exception) {
                return
            }
            val metadata = try {
                classFile.metadata?.let { KotlinClassMetadata.readLenient(it) }
            } catch (_: Exception) {
                // nothing is told about the nested classes of a class with unreadable metadata
                return
            }
            nestedClassFiles?.add(reader.className, classFile, (metadata as? KotlinClassMetadata.Class)?.kmClass)
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
                        scope = classScope(kmClass, isUsableClass(kmClass, classFile), classFile),
                        extensions = if (isObject) {
                            apiExtensions(kmClass.functions, classFile, className, source, isObjectMember = true)
                        } else emptyList(),
                        extensionProperties = if (isObject) {
                            apiExtensionProperties(kmClass.properties, classFile, className, source, isObjectMember = true)
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
            val source = packageSourceOf(className)
            indexExtensions(
                apiExtensions(kmPackage.functions, classFile, className, source, isObjectMember = false),
                apiExtensionProperties(kmPackage.properties, classFile, className, source, isObjectMember = false),
                funcIndex,
                propIndex
            )
        }

    }

    /**
     * The scope of the class of this JVM [binaryName], read from its class file served by the [classLoader],
     * like one outside the classpath roots, see [scanClasspath], or `null` if it cannot be read, see [ClassScope].
     */
    private fun readClassScope(binaryName: String): ClassScope? {
        val classFile = readClassFile(binaryName, classLoader.readClassBytes(binaryName)) ?: return null
        val kmClass = (classFile.metadata?.let(::parseMetadata) as? KotlinClassMetadata.Class)?.kmClass ?: return null
        return classScope(kmClass, isUsableClass(kmClass, classFile), classFile)
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

    private fun scanJarFile(jarFile: File, roots: ArrayDeque<File>, scanner: ClasspathScanner) {
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
                scanner.scan(
                    jar.entries().asSequence()
                        // skip the class versions of multi-release jars
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
        isObjectMember: Boolean
    ): List<IndexedExtension> = functions
        .filter { it.receiverParameterType != null }
        .inApi(TopLevel, classFile)
        .map { IndexedExtension(it, className, source, isObjectMember, classFile.deprecation(it), classFile.hidesMembers(it)) }

    private fun apiExtensionProperties(
        properties: List<KmProperty>,
        classFile: ClassFile,
        className: String,
        source: String,
        isObjectMember: Boolean
    ): List<IndexedExtensionProperty> = properties
        .filter { it.receiverParameterType != null }
        .inApi(TopLevel, classFile)
        .map {
            val deprecation = classFile.deprecation(it)
            IndexedExtensionProperty(
                it, className, source, isObjectMember,
                setterVisibility = it.apiSetterVisibility(TopLevel, deprecation),
                deprecation = deprecation
            )
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
         * is the [annotationsDeprecation], combined with the one of its getter, see [highestOf],
         * except for a `var`, whose accessors are deprecated apart, as it can be read, and assigned, see [varDeprecationOf].
         */
        fun deprecation(property: KmProperty, annotationsDeprecation: Deprecation?): Deprecation? {
            val getterDeprecation = deprecation(property.getterSignature)
            return if (property.isVar) {
                varDeprecationOf(annotationsDeprecation, getterDeprecation, deprecation(property.setterSignature))
            } else {
                highestOf(annotationsDeprecation, getterDeprecation)
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
     *   declared by the `InnerClasses` attribute, all of them kept, as the ones of the other classes
     *   might tell a class nested in a class which is not read, like `Outer${'$'}Nested${'$'}Deep` of a Java class compiled
     *   without the `NestMembers` attribute, before Java 11, referred to by another class scanned together with it,
     *   see [ClasspathScanner.scan]: any class of the same jar, even of another package,
     *   while only a class of the same package in a directory, scanned one directory at a time.
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
    private class NestedClassFiles {

        private val memberClasses = mutableMapOf<String, MemberClass>()

        private val nonKotlinClasses = mutableSetOf<String>()

        // the simple names of the nested classes and enum entries of the Kotlin classes, declared in their metadata
        private val kotlinNestedClasses = mutableMapOf<String, Set<String>>()

        fun add(name: String, classFile: ClassFile, kmClass: KmClass?) {
            val nesting = classFile.nesting ?: return
            nesting.memberClasses.forEach { memberClasses.putIfAbsent(it.name, it) }
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
            extensionIndex.listedFor(receiverNames, supertypeNames),
            extensionPropertyIndex.listedFor(receiverNames, supertypeNames)
        )

    /**
     * The extensions of this index listed for a class, the ones on any of the [receiverNames], like the class
     * and its supertypes, each one once, skipping the ones on a type parameter with multiple bounds,
     * like `where T : A, T : B`, unless the class is a subtype of all of them, among the [supertypeNames],
     * which are then listed also when indexed under none of the [receiverNames],
     * like `R.contains(element)` where `R : ClosedRange<T>, R : Iterable<T>` for a class implementing both,
     * which does not list the generic extensions of the standard library on any of these bounds alone,
     * see [userSupertypes].
     */
    private fun <E : ReceiverBounded> Map<String, List<E>>.listedFor(
        receiverNames: Collection<String>,
        supertypeNames: Set<String>
    ): List<E> {
        fun E.isSatisfied() = receiverBounds.all { it in supertypeNames }
        val listed = receiverNames.flatMap { this[it].orEmpty() }.filter { it.receiverBounds.isEmpty() || it.isSatisfied() }
        val receiverNameSet = receiverNames.toSet()
        val boundedBySupertypesOnly = supertypeNames
            .filter { it !in receiverNameSet }
            .flatMap { this[it].orEmpty() }
            .filter { it.receiverBounds.isNotEmpty() && it.isSatisfied() }
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
 * Whether this extension function is on a type parameter `T` bounded only by `Comparable<T>`,
 * like `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T)`, its only type parameter,
 * pinned to the class it is listed for, when the class is comparable only to itself, see `isComparableOnlyToItself`.
 */
private val KmFunction.hasReceiverPinnedByComparable: Boolean
    get() {
        val receiver = receiverParameterType ?: return false
        val receiverTypeParameter = receiver.classifier as? KmClassifier.TypeParameter ?: return false
        if (receiver.isNullable) return false
        val typeParameter = typeParameters.singleOrNull()?.takeIf { it.id == receiverTypeParameter.id } ?: return false
        val bound = typeParameter.upperBounds.singleOrNull() ?: return false
        val argument = bound.arguments.singleOrNull()?.type ?: return false
        return (bound.classifier as? KmClassifier.Class)?.name == COMPARABLE_METADATA_NAME && !bound.isNullable &&
            (argument.classifier as? KmClassifier.TypeParameter)?.id == receiverTypeParameter.id && !argument.isNullable
    }

/**
 * Whether this function, a member, or an extension on the class named [className], like `fun Int.coerceAtLeast(minimumValue: Int)`,
 * is called instead of the extension [function] on a type parameter pinned to the class, see `withoutShadowed`,
 * like `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T)`, unless its type argument is given explicitly:
 * the value parameters of the [function] have neither a default value, nor a vararg, which this function could not be called with,
 * while this function takes the same value parameters, of the same names, as they can be named in a call,
 * and of the types of the parameters of the [function], where `T` stands for the class, see [isSameType],
 * without any type parameter of its own, or any context parameter,
 * and it is not suspend, unless the [function] is, and it is an operator, or infix, if the [function] is,
 * as only then it is called the same way.
 */
@OptIn(ExperimentalContextParameters::class)
private fun KmFunction.shadows(function: KmFunction, className: String): Boolean {
    val receiver = function.receiverParameterType ?: return false
    val receiverTypeParameter = receiver.classifier as? KmClassifier.TypeParameter ?: return false
    return !receiver.isNullable &&
        function.valueParameters.none { it.declaresDefaultValue || it.varargElementType != null } &&
        typeParameters.isEmpty() && contextParameters.isEmpty() &&
        (!isSuspend || function.isSuspend) &&
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
 * shown in the `// from …` header: its package,
 * or the facade class name for the default package, which has no name.
 */
private fun packageSourceOf(binaryName: String): String = binaryName.substringBeforeLast('.')

private const val ANY_RECEIVER_KEY = "kotlin.Any"

private const val COMPARABLE_CLASS_NAME = "kotlin.Comparable"

private const val COMPARABLE_METADATA_NAME = "kotlin/Comparable"

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
