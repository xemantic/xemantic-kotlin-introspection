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
import java.io.File
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Attributes
import java.util.jar.JarFile
import kotlin.metadata.*
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.jvm.syntheticMethodForAnnotations
import kotlin.metadata.jvm.annotations
import kotlin.jvm.optionals.getOrNull
import kotlin.reflect.KCallable
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
    val isUsable: Boolean,
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
    scanEntireClasspath: Boolean = true,
    // whether the scan skips the nested class files which cannot contain an indexed Kotlin class, see NestedClassFiles,
    // disabled only by the tests telling that skipping them does not change the index
    private val skipNonKotlinNestedClassFiles: Boolean = true
) : KotlinIntrospector {

    private val logger = KotlinLogging.logger {}

    // Index of extension functions: receiver type name -> list of extensions
    internal val extensionIndex: Map<String, List<IndexedExtension>>

    // Index of extension properties: receiver type name -> list of extension properties
    internal val extensionPropertyIndex: Map<String, List<IndexedExtensionProperty>>

    // Index of all Kotlin classes: simple name (lowercase) -> list of fully qualified names
    internal val classIndex: Map<String, List<String>>

    // the number of the class files the classpath scan skipped without reading them, see NestedClassFiles
    internal var skippedClassFileCount: Int = 0
        private set

    /**
     * The version of the standard library of the classpath, telling whether a declaration
     * annotated with `@DeprecatedSinceKotlin(hiddenSince = …)` is hidden, see [isHiddenSince].
     * Declared before the classpath scan, which reads it.
     */
    private val stdlibVersion: KotlinVersion by lazy {
        try {
            // the Implementation-Version of the manifest of the jar of the standard library the class loader finds,
            // which is the same as the one of this library, unless the class loader prefers its own resources;
            // unless the manifest is of another jar, like a fat jar with the standard library inside
            val connection = classLoader.getResource("kotlin/KotlinVersion.class")?.openConnection() as? JarURLConnection
            // not cached, so that the jar file is closed, and not locked for the life of the JVM
            connection?.useCaches = false
            connection?.jarFile?.use { jarFile ->
                jarFile.manifest?.mainAttributes
                    ?.takeIf { it.getValue(Attributes.Name.IMPLEMENTATION_TITLE) == "kotlin-stdlib" }
                    ?.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
                    ?.let { parseKotlinVersion(it) }
            }
        } catch (_: Exception) {
            null
        } ?: KotlinVersion.CURRENT // like for a standard library in a directory, or in a fat jar
    }

    // Declarations of the built-in types, by their Kotlin class name, like kotlin/collections/List
    private val builtinClasses: Map<String, KmClass> by lazy { readKotlinBuiltinClasses() }

    // Declarations of the built-in types, by their fully qualified Kotlin name, like kotlin.collections.List,
    // telling apart the built-in collection types from their mutable counterparts, which kotlin-reflect cannot,
    // as both map to the same JVM type, and telling which members of the built-in types are overrides
    private val builtinKotlinClasses: Map<String, KmClass> by lazy {
        builtinClasses.mapKeys { it.key.replace('/', '.') }
    }

    private val overrideResolver = OverrideResolver(
        classLoader = classLoader,
        loadClassByMetadataName = { name -> findClassByMetadataName(name)?.clazz },
        loadKmClassByMetadataName = ::loadKmClass,
        // read directly, without loading the class, like a DefaultImpls class probed for every interface
        readClassBytesByMetadataName = { name -> readClassBytes(binaryNameOf(name)) },
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

        private const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

        private const val DEPRECATED_DESCRIPTOR = "Lkotlin/Deprecated;"

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
        internal const val DEFAULT_VALUE_MARKER = "…"

        private const val DEFAULT_COMPANION_NAME = "Companion"

        private const val DEFAULT_IMPLS = "DefaultImpls"

        private const val ANNOTATIONS_METHOD_SUFFIX = "\$annotations"

    }

    init {
        val scanTime = measureTime {
            if (scanEntireClasspath) {
                val (funcIndex, propIndex, clsIndex) = buildFullClasspathIndex()
                // sorted once, so that the output does not depend on the classpath scan order
                extensionIndex = funcIndex.sortedByNameAndSourceClass()
                extensionPropertyIndex = propIndex.sortedByNameAndSourceClass()
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

    private fun resolve(name: String): Resolved<*>? {
        val parts = unescapeQualifiedName(name).split(".")

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
            builtinCompanion(className)?.let { companion ->
                return if (memberName != null) {
                    resolveBuiltinCompanionMember(companion, className, memberName)
                } else {
                    formatBuiltinCompanion(companion, className)
                }
            }

            val foundClass = findClassByName(className) ?: continue
            val metadata = foundClass.metadata ?: continue

            val resolved = when (metadata) {

                is KotlinClassMetadata.Class -> {
                    val kmClass = metadata.kmClass
                    // neither a class which is not part of the API, nor anything declared within it, is resolved,
                    // while its name might still be a member of the outer class, like an extension property
                    val outerScopes = enclosingScopes(kmClass) ?: continue
                    if (!kmClass.visibility.isApiIn(outerScopes)) continue
                    val scopes = outerScopes + kmClass.scope
                    if (memberName != null) {
                        resolveMember(kmClass, scopes, className, foundClass, memberName)
                    } else {
                        formatClass(kmClass, scopes, className, foundClass)
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
     * A class of the classpath, by its JVM [binaryName], either loaded, or, when it cannot be loaded,
     * like when it extends a class missing from the classpath, only read from its class file.
     */
    private inner class FoundClass(val binaryName: String, val clazz: Class<*>?, readFile: ClassFile? = null) {

        /**
         * The class file, read once, and only when needed: already when the class cannot be loaded,
         * otherwise for the flags telling whether a listed class is usable, see [isUsableClass],
         * and for the hidden declarations, see [isHidden].
         */
        val classFile: ClassFile? by lazy { readFile ?: readClassFile(binaryName) }

        // parsed once, and only when needed, as some classes are loaded only for the Java reflection
        val metadata: KotlinClassMetadata? by lazy {
            (if (clazz != null) clazz.getAnnotation(Metadata::class.java) else classFile?.metadata)
                ?.let { parseMetadata(it) }
        }

        /**
         * The `DefaultImpls` class file of an interface, holding the synthetic methods for the annotations
         * of its properties, unless compiled with `-jvm-default=no-compatibility`, read only when needed.
         */
        private val defaultImplsFile: ClassFile? by lazy { readClassFile("$binaryName\$$DEFAULT_IMPLS") }

        // whether a declaration of this class is hidden, see ClassFile, never when the class file cannot be read

        fun isHidden(function: KmFunction): Boolean = classFile?.isHidden(function) == true

        fun isHidden(constructor: KmConstructor): Boolean = classFile?.isHidden(constructor) == true

        fun isHidden(property: KmProperty): Boolean {
            val classFile = classFile ?: return false
            if (classFile.isHidden(property)) return true
            // the synthetic method for the annotations of an interface property is rather in its DefaultImpls
            val annotationsMethod = property.syntheticMethodForAnnotations ?: return false
            return !classFile.declares(annotationsMethod) &&
                kmClass?.kind == ClassKind.INTERFACE &&
                defaultImplsFile?.isHidden(property) == true
        }

    }

    private val FoundClass.kmClass: KmClass?
        get() = (metadata as? KotlinClassMetadata.Class)?.kmClass

    /**
     * The functions which are part of the API in the [scopes], see [isApiIn], unless hidden,
     * see [FoundClass.isHidden], when declared in the [declaringClass] known.
     */
    @JvmName("apiFunctions")
    private fun List<KmFunction>.inApi(scopes: List<ClassScope>, declaringClass: FoundClass?): List<KmFunction> =
        filter { it.visibility.isApiIn(scopes) && declaringClass?.isHidden(it) != true }

    /**
     * The properties which are part of the API, like the functions, see [inApi].
     */
    @JvmName("apiProperties")
    private fun List<KmProperty>.inApi(scopes: List<ClassScope>, declaringClass: FoundClass?): List<KmProperty> =
        filter { it.visibility.isApiIn(scopes) && declaringClass?.isHidden(it) != true }

    /**
     * The functions of a mutable built-in collection type which are part of the API.
     * Whether one is hidden cannot be told: kotlin-metadata-jvm does not read the annotations of the built-ins,
     * while kotlin-reflect describes the read-only counterpart, sharing the same JVM type, instead.
     * None of the built-in collection declarations is deprecated, which `KotlinBuiltinsTest` guards.
     */
    @JvmName("mutableBuiltinApiFunctions")
    private fun List<KmFunction>.inMutableBuiltinApi(): List<KmFunction> = inApi(emptyList(), declaringClass = null)

    /**
     * The properties of a mutable built-in collection type which are part of the API, see the functions.
     */
    @JvmName("mutableBuiltinApiProperties")
    private fun List<KmProperty>.inMutableBuiltinApi(): List<KmProperty> = inApi(emptyList(), declaringClass = null)

    /**
     * Whether a member of a built-in type, described by kotlin-reflect, is part of the API:
     * public, and not hidden, see [isHiddenDeprecated].
     */
    private fun isBuiltinApi(member: KCallable<*>): Boolean =
        member.visibility == KVisibility.PUBLIC && !isHiddenDeprecated(member)

    /**
     * Whether the found class is listed in its outer class, see [isListedClass],
     * where a class file which cannot be read is judged by the metadata only.
     */
    private fun FoundClass.isListed(kmClass: KmClass): Boolean =
        isListedClass(kmClass, classFile ?: ClassFile(null, isSynthetic = false, isHiddenDeprecated = false))

    /**
     * The classes found so far by their binary names, also the ones not found, as [Optional.empty],
     * so that neither a class is loaded, nor its metadata parsed, nor a missing class looked up, more than once,
     * like for every nested class listed on every page of a paged resolve.
     * It is not bounded, just like the indexes built from the whole classpath on creation:
     * the classes found are only the ones of the classpath, and the ones not found are only the ones
     * referred to by the metadata of the classes found, like a supertype missing from the classpath,
     * never the names of the resolve queries, which are not cached when not found, see [findClassByName].
     * It is concurrent, so that the introspector can still be used by many threads at once,
     * at worst finding a class twice, and then keeping the one found first.
     */
    private val foundClasses = ConcurrentHashMap<String, Optional<FoundClass>>()

    /**
     * Finds a class by its binary name, like `com.example.Outer${'$'}Nested`, known to be exact, like the one derived
     * from the metadata of another class, and therefore cached also when the class is not found.
     */
    private fun findClassByBinaryName(binaryName: String): FoundClass? {
        foundClasses[binaryName]?.let { return it.getOrNull() }
        val lookup = lookUpClass(binaryName)
        if (!lookup.isCacheable) return lookup.foundClass
        return foundClasses.putIfAbsent(binaryName, Optional.ofNullable(lookup.foundClass))?.getOrNull()
            ?: lookup.foundClass
    }

    /**
     * Finds a class by its metadata name, like `com/example/Outer.Nested`.
     */
    private fun findClassByMetadataName(name: String): FoundClass? = findClassByBinaryName(binaryNameOf(name))

    /**
     * The JVM binary name, like `com.example.Outer${'$'}Nested`, of a metadata name, like `com/example/Outer.Nested`.
     */
    private fun binaryNameOf(metadataName: String): String {
        val packageName = metadataName.substringBeforeLast('/', "")
        val simpleName = metadataName.substringAfterLast('/').replace('.', '$')
        return if (packageName.isEmpty()) simpleName else "${packageName.replace('/', '.')}.$simpleName"
    }

    /**
     * Finds a class by its fully qualified name as given to [resolve], where nested classes are separated
     * either with `.`, or with the `$` of the JVM binary name, trying every possible binary name.
     * The classes found are cached, but not the names which are not found, as they come from the user input.
     */
    private fun findClassByName(name: String): FoundClass? {
        var binaryName = name
        while (true) {
            val cached = foundClasses[binaryName]
            if (cached != null) {
                if (cached.isPresent) return cached.get()
            } else {
                val lookup = lookUpClass(binaryName)
                if (lookup.foundClass != null) {
                    if (!lookup.isCacheable) return lookup.foundClass
                    return foundClasses.putIfAbsent(binaryName, Optional.of(lookup.foundClass))?.getOrNull()
                        ?: lookup.foundClass
                }
            }
            val lastDot = binaryName.lastIndexOf('.')
            if (lastDot < 0) return null
            binaryName = binaryName.substring(0, lastDot) + "$" + binaryName.substring(lastDot + 1)
        }
    }

    /**
     * @param isCacheable whether the outcome is final, unlike after a linkage error which might be transient.
     */
    private class ClassLookup(val foundClass: FoundClass?, val isCacheable: Boolean)

    private fun lookUpClass(binaryName: String): ClassLookup = try {
        val clazz = classLoader.loadClass(binaryName)
        ClassLookup(FoundClass(binaryName, clazz), isCacheable = true)
    } catch (_: ClassNotFoundException) {
        ClassLookup(null, isCacheable = true)
    } catch (e: LinkageError) {
        // the class file is there, but refers to a class which is not, like its supertype,
        // or is of a newer JVM version, while its declarations can still be read from its metadata;
        // the class file is read also after any other linkage error, but then the class is to be loaded again
        val isFinal = e is NoClassDefFoundError || e is UnsupportedClassVersionError
        ClassLookup(
            readClassFile(binaryName)
                ?.takeIf { it.metadata != null }
                ?.let { classFile -> FoundClass(binaryName, clazz = null, classFile) },
            isCacheable = isFinal
        )
    }

    private fun readClassBytes(binaryName: String): ByteArray? = try {
        classLoader.getResourceAsStream(binaryName.replace('.', '/') + ".class")?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    /**
     * Reads the class file of a class by its JVM [binaryName], whether it can be loaded or not, to tell the flags,
     * which neither [Class.isSynthetic] tells for a nested class, as it reports the flags of the `InnerClasses` attribute,
     * nor the reflection of annotations, which would load the classes of all of them,
     * and the metadata of a class which cannot be loaded,
     * unless the file is of another class, like the one found for the name `com/example/Outer`,
     * or for a name differing only in case.
     */
    private fun readClassFile(binaryName: String): ClassFile? = try {
        readClassBytes(binaryName)
            ?.let { ClassReader(it) }
            ?.takeIf { it.className.replace('/', '.') == binaryName }
            ?.let { readClassFile(it) }
    } catch (_: Exception) {
        null
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
     * The scopes of the classes enclosing the [kmClass], from the outermost one,
     * or null if any of them cannot be loaded.
     */
    private fun enclosingScopes(kmClass: KmClass): List<ClassScope>? =
        enclosingClassNames(kmClass.name).map { name ->
            loadKmClass(name)?.scope ?: return null
        }

    /**
     * The package in which the types are referred to by their simple names when rendering the [kmClass].
     * For nested classes, it is the actual Java package, not the outer class,
     * also of a class which cannot be loaded, taken then from its metadata name.
     */
    private fun contextPackageOf(foundClass: FoundClass, kmClass: KmClass): String =
        foundClass.clazz?.`package`?.name
            ?: kmClass.name.substringBeforeLast('/', "").replace('/', '.')

    /**
     * Resolves the members named [memberName] which the listing of the class shows, in the same order:
     * the properties, the functions, the members of the companion object, within its declaration,
     * as they are called on the class name, like `Foo.create()`,
     * and the extensions of the class and of its supertypes, under the `// from …` header
     * of the package, or the object, to import them from.
     *
     * @param scopes the scopes of the [kmClass] and its enclosing classes, from the outermost one.
     */
    private fun resolveMember(
        kmClass: KmClass,
        scopes: List<ClassScope>,
        className: String,
        foundClass: FoundClass,
        memberName: String
    ): MemberListing? {
        val isInterface = kmClass.kind == ClassKind.INTERFACE
        val typeParameters = typeParametersInScope(kmClass)
        val contextPackage = contextPackageOf(foundClass, kmClass)

        val members = kmClass.properties.filter { it.name == memberName }.inApi(scopes, foundClass).map { property ->
            formatProperty(property, inInterface = isInterface, owner = kmClass, contextPackage = contextPackage, classTypeParameters = typeParameters)
        } + kmClass.functions.filter { it.name == memberName }.inApi(scopes, foundClass).map { function ->
            formatFunction(function, inInterface = isInterface, owner = kmClass, contextPackage = contextPackage, classTypeParameters = typeParameters)
        }

        val companionMembers = apiCompanionOf(kmClass, scopes, foundClass)?.let { companion ->
            val signatures = companion.propertiesNamed(memberName).map {
                formatCompanionProperty(it, companion, contextPackage)
            } + companion.functionsNamed(memberName).map {
                formatCompanionFunction(it, companion, contextPackage)
            }
            // the header is formatted only when there are signatures to render within it
            val header by lazy { formatCompanionHeader(kmClass, companion, contextPackage) }
            signatures.map { ListingItem.CompanionMember(header, it) }
        }.orEmpty()

        val receiverNames = extensionReceiverNames(kmClass, className)
        val extensions = extensionSignatures(
            receiverNames.flatMap { getExtensionsFor(it) }.filter { it.function.name == memberName },
            receiverNames.flatMap { getExtensionPropertiesFor(it) }.filter { it.property.name == memberName },
            contextPackage
        )

        return MemberListing.of(members.map { ListingItem.Declaration(it) } + companionMembers + extensions)
    }

    /**
     * The companion object of a class, unless it is not part of the API,
     * with its [properties] and [functions] which are, see [inApi].
     *
     * @param outerScopes the scopes of the outer class and its enclosing classes, from the outermost one.
     */
    private inner class ApiCompanion(val kmClass: KmClass, val foundClass: FoundClass, outerScopes: List<ClassScope>) {

        /**
         * The scopes of the companion object and its enclosing classes, from the outermost one.
         */
        val scopes: List<ClassScope> = outerScopes + kmClass.scope

        val properties: List<KmProperty> by lazy { propertiesNamed(null) }

        val functions: List<KmFunction> by lazy { functionsNamed(null) }

        /**
         * The [properties] named [name], or all of them, if `null`, filtered by name first,
         * not to tell whether each of the others is hidden.
         */
        fun propertiesNamed(name: String?): List<KmProperty> =
            kmClass.properties.filter { name == null || it.name == name }.inApi(scopes, foundClass)

        /**
         * The [functions] named [name], or all of them, like the [propertiesNamed].
         */
        fun functionsNamed(name: String?): List<KmFunction> =
            kmClass.functions.filter { name == null || it.name == name }.inApi(scopes, foundClass)

    }

    /**
     * The companion object of the [kmClass], see [ApiCompanion], or `null` if it has none which is part of the API.
     *
     * @param scopes the scopes of the [kmClass] and its enclosing classes, from the outermost one.
     */
    private fun apiCompanionOf(kmClass: KmClass, scopes: List<ClassScope>, foundClass: FoundClass): ApiCompanion? {
        val name = kmClass.companionObject ?: return null
        // the binary name, as the class might be nested
        val companionClass = findClassByBinaryName("${foundClass.binaryName}\$$name") ?: return null
        val companion = companionClass.kmClass?.takeIf { it.visibility.isApiIn(scopes) } ?: return null
        return ApiCompanion(companion, companionClass, scopes)
    }

    /**
     * The declaration of the [companion] object of the [kmClass], like `companion object`,
     * named only when custom, as the name is then needed to qualify the members, like `Outer.Factory.create()`.
     */
    private fun formatCompanionHeader(kmClass: KmClass, companion: ApiCompanion, contextPackage: String): String {
        val companionName = kmClass.companionObject.takeIf { it != DEFAULT_COMPANION_NAME } ?: ""
        return formatClassHeader(companion.kmClass, companionName, contextPackage)
    }

    private fun formatCompanionProperty(property: KmProperty, companion: ApiCompanion, contextPackage: String): String =
        formatProperty(property, owner = companion.kmClass, contextPackage = contextPackage)

    private fun formatCompanionFunction(function: KmFunction, companion: ApiCompanion, contextPackage: String): String =
        formatFunction(function, inInterface = false, owner = companion.kmClass, contextPackage = contextPackage)

    /**
     * The receiver types whose extensions are shown with a class, the class named [className] itself,
     * and its supertypes, see [userSupertypes].
     */
    private fun extensionReceiverNames(kmClass: KmClass, className: String): List<String> =
        listOf(className) + userSupertypes(kmClass)

    /**
     * Resolves the top-level declarations named [memberName] of a file facade like the members of a class,
     * see [resolveMember]: under the `// from …` header of the package to import them from,
     * referring to the types of the package by their simple names, the properties first, then the functions.
     *
     * @param kmPackages the package declarations, with the class declaring them, like a file facade.
     */
    private fun resolvePackageMember(kmPackages: List<Pair<KmPackage, FoundClass>>, memberName: String): MemberListing? {
        fun signatures(
            format: (KmPackage, FoundClass, contextPackage: String) -> List<String>
        ): List<ListingItem.Extension> = kmPackages.flatMap { (kmPackage, foundClass) ->
            val binaryName = foundClass.binaryName
            val source = packageSourceOf(binaryName)
            format(kmPackage, foundClass, binaryName.substringBeforeLast('.', "")).map { ListingItem.Extension(source, it) }
        }
        val properties = signatures { kmPackage, foundClass, contextPackage ->
            kmPackage.properties.filter { it.name == memberName }.inApi(emptyList(), foundClass).map {
                formatProperty(it, contextPackage = contextPackage)
            }
        }
        val functions = signatures { kmPackage, foundClass, contextPackage ->
            kmPackage.functions.filter { it.name == memberName }.inApi(emptyList(), foundClass).map {
                formatFunction(it, contextPackage = contextPackage)
            }
        }
        return MemberListing.of(properties + functions)
    }

    /**
     * Format a Kotlin built-in type using kotlin-reflect.
     */
    private fun formatBuiltinClass(
        kClass: KClass<*>,
        kotlinName: String
    ): ClassListing {
        val simpleName = kotlinName.substringAfterLast(".")
        val builtinKmClass = builtinKotlinClasses[kotlinName]
        val mutableKmClass = if (kotlinName in mutableBuiltinTypes) builtinKmClass else null

        // Collect all members to sort them together alphabetically
        data class Member(val name: String, val formatted: String)
        val allMembers = mutableListOf<Member>()

        val declaration: String

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
            mutableKmClass.properties.inMutableBuiltinApi().forEach { property ->
                allMembers.add(
                    Member(
                        property.name,
                        formatProperty(property, inInterface = true, owner = mutableKmClass, classTypeParameters = typeParameters)
                    )
                )
            }
            mutableKmClass.functions.inMutableBuiltinApi().forEach { function ->
                allMembers.add(
                    Member(
                        function.name,
                        formatFunction(function, inInterface = true, owner = mutableKmClass, classTypeParameters = typeParameters)
                    )
                )
            }
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
                // the unsigned types are value classes
                +formatJvmInline(kClass.java.isAnnotationPresent(JvmInline::class.java))
                if (kClass.isValue) +"value "
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
                .filter { isBuiltinApi(it) }
                .forEach { prop ->
                    allMembers.add(Member(prop.name, formatKProperty(prop, isOverride(builtinKmClass, prop))))
                }

            // Functions (only public, non-extension, excluding synthetic, internal, and Java-only methods taking parameters)
            kClass.memberFunctions
                .asSequence()
                .filter { isBuiltinApi(it) }
                .filter { !it.name.startsWith("component") }  // Exclude componentN
                .filter { it.name != "copy" }  // Exclude copy for data classes
                .filter { !it.name.contains("$") }  // Exclude synthetic
                .filter { !it.toString().contains("internal ") }  // Exclude internal
                .filter { !hasSyntheticParameterNames(it) }
                .toList()
                .forEach { func ->
                    allMembers.add(Member(func.name, formatKFunction(func, isOverride(builtinKmClass, func))))
                }

        }
        val supertypeNames = builtinSupertypeNames(kClass, mutableKmClass)

        // Extension functions on this exact type (shown inside class body)
        // Only include extensions from kotlin.* packages for built-in types
        // The ones to import from an object, like Int.seconds of Duration.Companion, are shown outside class body
        val (objectExtensions, packageExtensions) = getExtensionsFor(kotlinName)
            .filter { isPublicKotlinPackageSource(it.sourceClass) }
            .partition { it.isObjectMember }
        packageExtensions.forEach { ext ->
            allMembers.add(Member(ext.function.name, formatFunction(ext.function, withReceiver = false)))
        }

        // Extension properties on this exact type (shown inside class body)
        val (objectExtensionProperties, packageExtensionProperties) = getExtensionPropertiesFor(kotlinName)
            .filter { isPublicKotlinPackageSource(it.sourceClass) }
            .partition { it.isObjectMember }
        packageExtensionProperties.forEach { extProp ->
            allMembers.add(Member(extProp.property.name, formatProperty(extProp.property, withReceiver = false)))
        }

        // Extension functions and properties on all the supertypes (shown outside class body)
        // Only include extensions from kotlin.* packages for built-in types
        val supertypeExtensions = objectExtensions + supertypeNames.flatMap { supertypeName ->
            getExtensionsFor(supertypeName)
                .filter { isPublicKotlinPackageSource(it.sourceClass) }
        }
        val supertypeExtensionProperties = objectExtensionProperties + supertypeNames.flatMap { supertypeName ->
            getExtensionPropertiesFor(supertypeName)
                .filter { isPublicKotlinPackageSource(it.sourceClass) }
        }

        return ClassListing.of(
            header = declaration,
            // Sort all members alphabetically
            members = allMembers.sortedBy { it.name }.map { ListingItem.Declaration(it.formatted) },
            extensions = extensionSignatures(supertypeExtensions, supertypeExtensionProperties, contextPackage = null)
        )
    }

    /**
     * Whether a member of a built-in type, described by kotlin-reflect, is hidden, like the `getFirst()`
     * of `java.util.List` since JDK 21, which Kotlin hides in favor of the `first()` extension,
     * see [hiddenDeprecationVisitor].
     * The annotations are built by kotlin-reflect from the metadata of the built-ins, never failing the resolve.
     */
    private fun isHiddenDeprecated(member: KCallable<*>): Boolean = reflectedAnnotationValue(default = false) {
        member.annotations.any { annotation ->
            val isHidden = annotation is Deprecated &&
                reflectedAnnotationValue(DeprecationLevel.WARNING) { annotation.level } == DeprecationLevel.HIDDEN
            isHidden || (
                annotation is DeprecatedSinceKotlin &&
                    isHiddenSince(reflectedAnnotationValue("") { annotation.hiddenSince })
                )
        }
    }

    /**
     * A [value] read from an annotation which kotlin-reflect builds from the metadata of the built-ins,
     * or the [default] when it cannot be read, like a parameter with the default value, absent from the metadata,
     * whose getter throws `KotlinReflectionInternalError`, as for the `level` of `@Deprecated` of `Long.toChar()`.
     */
    private inline fun <T> reflectedAnnotationValue(default: T, value: () -> T): T = try {
        value()
    } catch (_: Exception) {
        default
    } catch (e: Error) {
        if (e is VirtualMachineError) throw e
        default
    }

    /**
     * All the supertypes of a built-in type, excluding `Any`, taken from the [mutableKmClass],
     * if it is a mutable built-in collection type, which kotlin-reflect would describe as its read-only counterpart.
     */
    private fun builtinSupertypeNames(kClass: KClass<*>, mutableKmClass: KmClass?): Collection<String> =
        if (mutableKmClass != null) {
            builtinCollectionSupertypes(mutableKmClass)
        } else {
            kClass.allSuperclasses
                .filter { it != Any::class }
                .mapNotNull { it.qualifiedName }
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
                builtinKotlinClasses[name]?.let { pending.add(it) }
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
                val metadataName = (supertype.classifier as? KmClassifier.Class)?.name ?: continue
                val name = metadataName.replace('/', '.')
                if (name.startsWith("kotlin.") || name.startsWith("java.") || !supertypeNames.add(name)) continue
                loadKmClass(metadataName)?.let { pending.add(it) }
            }
        }
        return supertypeNames
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
        sourceClass.startsWith("kotlin.") && !sourceClass.split('.').contains("internal")

    /**
     * Resolve a member of a built-in type.
     */
    private fun resolveBuiltinMember(
        kClass: KClass<*>,
        className: String,
        memberName: String
    ): MemberListing? {
        val builtinKmClass = builtinKotlinClasses[className]
        val mutableKmClass = if (className in mutableBuiltinTypes) builtinKmClass else null
        val mutableMembers = mutableKmClass?.let { kmClass ->
            val typeParameters = kmClass.typeParameters.namesById()
            kmClass.properties.filter { it.name == memberName }.inMutableBuiltinApi().map {
                formatProperty(it, inInterface = true, owner = kmClass, classTypeParameters = typeParameters)
            } + kmClass.functions.filter { it.name == memberName }.inMutableBuiltinApi().map {
                formatFunction(it, inInterface = true, owner = kmClass, classTypeParameters = typeParameters)
            }
        } ?: emptyList()

        // Members are sorted, as kotlin-reflect does not guarantee their order,
        // the properties first, like in the listing, where a property precedes a function of the same name
        val members = mutableMembers.ifEmpty {
            kClass.memberProperties.filter {
                it.name == memberName && isBuiltinApi(it)
            }.map { formatKProperty(it, isOverride(builtinKmClass, it)) } + kClass.memberFunctions.filter {
                it.name == memberName && isBuiltinApi(it)
            }.map { formatKFunction(it, isOverride(builtinKmClass, it)) }.sortedBy {
                it.removePrefix("override ")
            }
        }

        // The same extensions as the listing of the built-in type shows, the ones of the public kotlin.* packages
        // on the type and on its supertypes, but all with their receivers, under the `// from …` header,
        // while the listing renders the ones on the type itself, imported from a package, like `kotlin.text`,
        // within the class body, without the receiver
        val receiverNames = listOf(className) + builtinSupertypeNames(kClass, mutableKmClass)
        val extensions = extensionSignatures(
            receiverNames.flatMap { getExtensionsFor(it) }.filter {
                it.function.name == memberName && isPublicKotlinPackageSource(it.sourceClass)
            },
            receiverNames.flatMap { getExtensionPropertiesFor(it) }.filter {
                it.property.name == memberName && isPublicKotlinPackageSource(it.sourceClass)
            },
            contextPackage = null
        )

        return MemberListing.of(members.map { ListingItem.Declaration(it) } + extensions)
    }

    /**
     * The companion object of a built-in type, like `kotlin.Int.Companion`, by its fully qualified [name],
     * or `null` if it is not one, read from the `.kotlin_builtins` metadata, as kotlin-reflect would describe
     * the companion object of the JVM type instead, if any.
     */
    private fun builtinCompanion(name: String): KmClass? {
        val outerName = name.substringBeforeLast('.')
        if (outerName !in builtinTypes) return null
        val companionName = builtinKotlinClasses[outerName]?.companionObject ?: return null
        return if (name.substringAfterLast('.') == companionName) builtinKotlinClasses[name] else null
    }

    /**
     * The declarations of the [companion] object of a built-in type, named [name], like `kotlin.Int.Companion`,
     * the ones named [memberName], or all of them, if `null`:
     * its properties, its functions, and its extensions from the public `kotlin.*` packages, see [isPublicKotlinPackageSource],
     * like `String.Companion.format()`.
     */
    private fun builtinCompanionDeclarations(
        companion: KmClass,
        name: String,
        memberName: String?
    ): Pair<List<ListingItem.Declaration>, List<ListingItem.Extension>> {
        fun String.matches() = memberName == null || this == memberName
        val members = companion.properties.filter { it.name.matches() }.inMutableBuiltinApi().map {
            formatProperty(it, owner = companion)
        } + companion.functions.filter { it.name.matches() }.inMutableBuiltinApi().map {
            formatFunction(it, owner = companion)
        }
        val extensions = extensionSignatures(
            getExtensionsFor(name).filter { it.function.name.matches() && isPublicKotlinPackageSource(it.sourceClass) },
            getExtensionPropertiesFor(name).filter {
                it.property.name.matches() && isPublicKotlinPackageSource(it.sourceClass)
            },
            contextPackage = null
        )
        return members.map { ListingItem.Declaration(it) } to extensions
    }

    /**
     * Lists the [companion] object of a built-in type, named [name], see [builtinCompanionDeclarations].
     */
    private fun formatBuiltinCompanion(companion: KmClass, name: String): ClassListing {
        val (members, extensions) = builtinCompanionDeclarations(companion, name, memberName = null)
        return ClassListing.of(
            header = formatClassHeader(companion, simpleClassName(companion.name), contextPackage = ""),
            members = members,
            extensions = extensions
        )
    }

    /**
     * Resolves the members named [memberName] of the [companion] object of a built-in type, named [name],
     * see [builtinCompanionDeclarations].
     */
    private fun resolveBuiltinCompanionMember(companion: KmClass, name: String, memberName: String): MemberListing? {
        val (members, extensions) = builtinCompanionDeclarations(companion, name, memberName)
        return MemberListing.of(members + extensions)
    }

    /**
     * The `@JvmInline` annotation, which a value class with a single underlying property requires on the JVM.
     * It is rendered inline, so that the class declaration stays on a single line.
     */
    private fun formatJvmInline(isJvmInline: Boolean): String = if (isJvmInline) "@JvmInline " else ""

    /**
     * The modifiers and the kind of the class, like `data class ` or `object `.
     */
    private fun formatClassPrefix(kmClass: KmClass): String = buildString {
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
    private fun formatClass(kmClass: KmClass, scopes: List<ClassScope>, className: String, foundClass: FoundClass): ClassListing {
        val clazz = foundClass.clazz
        val isInterface = kmClass.kind == ClassKind.INTERFACE
        val isEnum = kmClass.kind == ClassKind.ENUM_CLASS
        val isAnnotation = kmClass.kind == ClassKind.ANNOTATION_CLASS
        val isSealed = kmClass.modality == Modality.SEALED
        val typeParameters = typeParametersInScope(kmClass)

        // Extract the package name for context-aware type formatting
        val contextPackage = contextPackageOf(foundClass, kmClass)

        // Only the members which can be used from the user code
        val properties = kmClass.properties.inApi(scopes, foundClass)
        val functions = kmClass.functions.inApi(scopes, foundClass)

        // Find primary constructor and its parameter names
        val primaryConstructor = kmClass.constructors.firstOrNull { !it.isSecondary }
        // The parameters of a constructor which cannot be called are not rendered, while the properties
        // they declare still are, in the class body. Except for an enum class, whose constructor is always private,
        // while its parameters are the idiomatic way of declaring the properties of the entries.
        val renderedConstructor = primaryConstructor?.takeIf {
            isEnum || (it.visibility.isApiIn(scopes) && !foundClass.isHidden(it))
        }
        // The other ways of creating the class, never of an enum class, as they are private,
        // nor of an annotation class, which cannot have any
        val secondaryConstructors = kmClass.constructors.filter {
            it.isSecondary && it.visibility.isApiIn(scopes) && !foundClass.isHidden(it)
        }
        // The properties declared by the parameters of the rendered constructor, by the parameter name
        val constructorProperties = renderedConstructor?.valueParameters.orEmpty().mapNotNull { param ->
            properties.firstOrNull { it.isDeclaredBy(param) }?.let { param.name to it }
        }.toMap()

        // The companion object, unless it is not part of the API, with its members which are
        val companion = apiCompanionOf(kmClass, scopes, foundClass)
        // the ones which are also sealed subclasses of this class are listed as such
        val companionNestedClasses = if (companion != null) {
            listedNestedClasses(
                companion.kmClass, companion.scopes, companion.foundClass.binaryName, kmClass.sealedSubclasses.toSet()
            )
        } else {
            emptyList()
        }

        // Extensions from the index on this type and its supertypes (shown after class body with source)
        val receiverNames = extensionReceiverNames(kmClass, className)
        val extensions = receiverNames.flatMap { getExtensionsFor(it) }
        val extensionProperties = receiverNames.flatMap { getExtensionPropertiesFor(it) }

        // For annotation classes, the parameters are shown inline, without body
        val annotationParameters = primaryConstructor?.valueParameters
            ?.takeIf { isAnnotation && it.isNotEmpty() }

        val constructorDeclaration = when {
            annotationParameters != null -> annotationParameters.joinToString(", ", "(", ")") { param ->
                formatValueParameter(
                    param,
                    contextPackage,
                    prefix = "val ",
                    // the default values are only present in the JVM annotation, not in the Kotlin metadata
                    defaultValue = { clazz?.let { formatAnnotationDefaultValue(it, param.name, contextPackage) } }
                )
            }
            // the implicit constructor without parameters is omitted, unless there are other constructors,
            // as `class Foo { constructor(x: Int) }` has no constructor without parameters
            renderedConstructor != null && (
                renderedConstructor.valueParameters.isNotEmpty() || secondaryConstructors.isNotEmpty()
            ) -> buildString {
                // a protected constructor can be called only from a subclass
                if (!isEnum && renderedConstructor.visibility != Visibility.PUBLIC) {
                    +" "
                    +formatVisibility(renderedConstructor.visibility)
                    +"constructor"
                }
                +(renderedConstructor.valueParameters.joinToString(", ", "(", ")") { param ->
                    // a parameter not declaring a property is still needed to call the constructor
                    val prop = constructorProperties[param.name]
                    if (prop == null) {
                        formatValueParameter(param, contextPackage, typeParameters)
                    } else {
                        formatValueParameter(
                            param,
                            contextPackage,
                            typeParameters,
                            prefix = if (prop.isVar) "var " else "val ",
                            // a constructor property is never abstract, but it can be open or final
                            modifiers = formatVisibility(prop.visibility) + formatModalityModifiers(
                                prop.modality, isOverride(kmClass, prop), inInterface = false, kmClass
                            )
                        )
                    }
                })
            }
            else -> ""
        }

        val header = formatClassHeader(
            kmClass, simpleClassName(kmClass.name), contextPackage, typeParameters, constructorDeclaration
        )

        // the parameters are the only members of an annotation class, listed without its extensions
        if (annotationParameters != null) return ClassListing.of(header)

        val sealedSubclasses = if (isSealed) listedSealedSubclasses(kmClass) else emptyList()
        val nestedClasses = listedNestedClasses(kmClass, scopes, foundClass.binaryName)

        val members = buildList<ListingItem.ClassBodyItem> {
            // The sealed subclasses first, then the other nested classes, only referenced,
            // as they can be resolved by their own name
            (sealedSubclasses + nestedClasses).forEach { (name, listedKmClass) ->
                add(ListingItem.Declaration(formatClassHeader(listedKmClass, name, contextPackage, typeParametersInScope(listedKmClass))))
            }

            // Properties (excluding constructor properties)
            val declaredInConstructor = constructorProperties.values.toSet()
            properties
                .filter { it !in declaredInConstructor }
                .forEach { property ->
                    add(ListingItem.Declaration(formatProperty(property, inInterface = isInterface, owner = kmClass, contextPackage = contextPackage, classTypeParameters = typeParameters)))
                }

            // Secondary constructors, in the conventional order, between the properties and the functions
            secondaryConstructors.forEach { constructor ->
                add(ListingItem.Declaration(formatSecondaryConstructor(constructor, contextPackage, typeParameters)))
            }

            // Functions
            functions.forEach { function ->
                add(ListingItem.Declaration(formatFunction(function, inInterface = isInterface, owner = kmClass, contextPackage = contextPackage, classTypeParameters = typeParameters)))
            }

            // even without members of the API, the companion object is a value, like a CoroutineContext.Key,
            // rendered then without a body
            if (companion != null) {
                // a single member of the outer class, never split across pages
                add(ListingItem.CompanionObject(
                    header = formatCompanionHeader(kmClass, companion, contextPackage),
                    members = buildList {
                        // Companion nested classes, only referenced, like the ones of the outer class
                        companionNestedClasses.forEach { (name, listedKmClass) ->
                            add(formatClassHeader(listedKmClass, name, contextPackage, typeParametersInScope(listedKmClass)))
                        }

                        // Companion properties
                        companion.properties.forEach { property ->
                            add(formatCompanionProperty(property, companion, contextPackage))
                        }

                        // Companion functions
                        companion.functions.forEach { function ->
                            add(formatCompanionFunction(function, companion, contextPackage))
                        }
                    },
                    name = escapeQualifiedName("$className.${kmClass.companionObject}")
                ))
            }
        }

        return ClassListing.of(
            header = header,
            isEnum = isEnum,
            enumEntryNames = if (isEnum) kmClass.kmEnumEntries.map { escapeIdentifier(it.name) } else emptyList(),
            members = members,
            extensions = extensionSignatures(extensions, extensionProperties, contextPackage)
        )
    }

    /**
     * A secondary constructor, like `constructor(name: String, vararg tags: String)`, without the delegation call.
     */
    private fun formatSecondaryConstructor(
        constructor: KmConstructor,
        contextPackage: String,
        typeParameters: Map<Int, String>
    ): String = buildString {
        +formatVisibility(constructor.visibility)
        +"constructor"
        +constructor.valueParameters.joinToString(", ", "(", ")") { param ->
            formatValueParameter(param, contextPackage, typeParameters)
        }
    }

    /**
     * The header of a class declaration: the modifiers and the kind, the [name], the type parameters,
     * the [constructor] declaration, if any, the supertypes and the where clause.
     *
     * @param name the name to render, possibly qualified, like `Holder.Nested`, or empty for a default companion object,
     *   unescaped, as each of its segments is escaped with [escapeQualifiedName].
     * @param typeParameters the names of the type parameters in scope, including the ones
     *   of the outer class of an inner class.
     */
    private fun formatClassHeader(
        kmClass: KmClass,
        name: String,
        contextPackage: String,
        typeParameters: Map<Int, String> = kmClass.typeParameters.namesById(),
        constructor: String = ""
    ): String = buildString {
        // the prefix ends with a space, like in `companion object `
        +formatClassPrefix(kmClass).let { if (name.isEmpty()) it.trimEnd() else it + escapeQualifiedName(name) }
        +formatTypeParameters(kmClass.typeParameters, typeParameters, contextPackage)
        +constructor
        val supertypes = formatSupertypes(kmClass, contextPackage, typeParameters)
        if (supertypes.isNotEmpty()) {
            +" : "
            +supertypes.joinToString(", ")
        }
        +formatWhereClause(kmClass.typeParameters, typeParameters, contextPackage)
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
                .map { ListingItem.Extension(source, formatProperty(it.property, contextPackage = contextPackage)) } +
                functionsBySource[source].orEmpty()
                    .sortedWith(extensionFunctionOrder)
                    .map { ListingItem.Extension(source, formatFunction(it.function, contextPackage = contextPackage)) }
        }
    }

    /**
     * The sealed subclasses of the [kmClass] which are part of the API, by their names relative to the [kmClass]
     * when nested within it, or else to their package, like `Holder.Nested`.
     * The ones which cannot be found are not listed, as they cannot be resolved either,
     * while the ones which cannot be loaded, but whose class files can still be read, are, like by [lookUpClass].
     * Neither is the own companion object, already rendered as such,
     * while the companion object of another class is, as a branch of a `when` expression.
     */
    private fun listedSealedSubclasses(kmClass: KmClass): List<Pair<String, KmClass>> =
        kmClass.sealedSubclasses.mapNotNull { subclassName ->
            if (subclassName == "${kmClass.name}.${kmClass.companionObject}") return@mapNotNull null
            val subclassClass = findClassByMetadataName(subclassName) ?: return@mapNotNull null
            val subclass = subclassClass.kmClass ?: return@mapNotNull null
            val enclosing = enclosingScopes(subclass) ?: return@mapNotNull null
            val isListed = subclass.kind == ClassKind.COMPANION_OBJECT || subclassClass.isListed(subclass)
            if (!isListed || !subclass.visibility.isApiIn(enclosing)) return@mapNotNull null
            subclassName.removePrefix("${kmClass.name}.").substringAfterLast('/') to subclass
        }

    /**
     * The nested classes worth listing in the outer class, by their simple names, excluding
     * the sealed subclasses and the companion object, which are already rendered,
     * and the ones not visible to the users of the outer class.
     * Like the sealed subclasses, the ones which cannot be found are not listed, as neither their kind,
     * nor their visibility, is known, and they cannot be resolved either.
     *
     * @param binaryName the binary name of the [kmClass], like `com.example.Outer$Companion`.
     * @param sealedSubclasses the declared sealed subclasses to exclude, the ones of the [kmClass],
     *   or of the class enclosing a companion object, which might be nested in that companion object.
     */
    private fun listedNestedClasses(
        kmClass: KmClass,
        scopes: List<ClassScope>,
        binaryName: String,
        sealedSubclasses: Set<String> = kmClass.sealedSubclasses.toSet()
    ): List<Pair<String, KmClass>> {
        return kmClass.nestedClasses
            .filter { "${kmClass.name}.$it" !in sealedSubclasses }
            .mapNotNull { name ->
                // the binary name, as the class might be nested
                findClassByBinaryName("$binaryName\$$name")?.let { nestedClass ->
                    nestedClass.kmClass?.takeIf { nestedClass.isListed(it) }
                }?.let { name to it }
            }
            .filter { (_, nestedKmClass) -> nestedKmClass.visibility.isApiIn(scopes) }
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
     * @param withReceiver whether to render the receiver of an extension function,
     *   which is omitted when the extension is listed in the body of its receiver class.
     */
    private fun formatFunction(
        function: KmFunction,
        inInterface: Boolean = false,
        owner: KmClass? = null,
        contextPackage: String? = null,
        classTypeParameters: Map<Int, String> = emptyMap(),
        withReceiver: Boolean = true
    ): String = buildString {
        val typeParameters = classTypeParameters + function.typeParameters.namesById()

        +formatVisibility(function.visibility)

        val override = override(owner, function)
        +formatModalityModifiers(function.modality, override != null, inInterface, owner)

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

        // the receiver of an extension function
        function.receiverParameterType?.takeIf { withReceiver }?.let { receiverType ->
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

        +formatWhereClause(function.typeParameters, typeParameters, contextPackage)
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
     * Format a member, top-level or extension property.
     *
     * @param owner the class declaring the [property], to tell whether it is an override.
     * @param withReceiver whether to render the receiver of an extension property,
     *   which is omitted when the extension is listed in the body of its receiver class.
     */
    private fun formatProperty(
        property: KmProperty,
        inInterface: Boolean = false,
        owner: KmClass? = null,
        contextPackage: String? = null,
        classTypeParameters: Map<Int, String> = emptyMap(),
        withReceiver: Boolean = true
    ): String = buildString {
        val typeParameters = classTypeParameters + property.typeParameters.namesById()

        +formatVisibility(property.visibility)

        +formatModalityModifiers(property.modality, isOverride(owner, property), inInterface, owner)
        if (property.isConst) +"const "

        +(if (property.isVar) "var " else "val ")
        formatTypeParameters(property.typeParameters, typeParameters, contextPackage).let {
            if (it.isNotEmpty()) +"$it "
        }

        // the receiver of an extension property
        property.receiverParameterType?.takeIf { withReceiver }?.let { receiverType ->
            +formatReceiverType(receiverType, contextPackage, typeParameters)
            +"."
        }

        +escapeIdentifier(property.name)
        +": "
        +formatType(property.returnType, contextPackage, typeParameters)
        +formatWhereClause(property.typeParameters, typeParameters, contextPackage)
    }

    private fun List<KmTypeParameter>.namesById(): Map<Int, String> = associate { it.id to it.name }

    /**
     * The names of the type parameters which the [kmClass] can refer to, by their ids,
     * including the ones of its outer classes when it is an inner class,
     * which can be declared in a class other than the one listing it, like a sealed subclass.
     */
    private fun typeParametersInScope(kmClass: KmClass): Map<Int, String> {
        val outerClass = if (kmClass.isInner) enclosingClassNames(kmClass.name).lastOrNull()?.let { loadKmClass(it) } else null
        return outerClass?.let { typeParametersInScope(it) }.orEmpty() + kmClass.typeParameters.namesById()
    }

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
     * An override is implicitly open, therefore its `final` is rendered,
     * unless the [owner] class is final itself, which makes all its members final.
     * An enum class is final in the metadata, even when its entries have bodies subclassing it,
     * which the metadata does not tell, therefore `final` is always rendered in an enum class.
     * The members of an interface are implicitly abstract.
     */
    private fun formatModalityModifiers(
        modality: Modality,
        isOverride: Boolean,
        inInterface: Boolean,
        owner: KmClass?
    ): String = buildString {
        when (modality) {
            Modality.ABSTRACT -> if (!inInterface) +"abstract "
            Modality.OPEN -> if (!isOverride) +"open "
            Modality.FINAL -> if (
                isOverride && owner != null && (owner.modality != Modality.FINAL || owner.kind == ClassKind.ENUM_CLASS)
            ) +"final "
            Modality.SEALED -> {}
        }
        if (isOverride) +"override "
    }

    /**
     * Whether this property might be declared by the constructor [parameter], like `class Foo(val x: Int)`.
     *
     * The metadata does not tell where a property is declared, so a property of the same name is excluded
     * when it cannot be declared in the constructor: it has a receiver, an accessor other than the default one
     * (with a body, also the only way of declaring a property without a backing field,
     * or only changing the visibility, like `private set`), a delegate, a constant initializer, no implementation,
     * or a type other than the parameter type, like `class Foo(x: Int) { val x: Long = x.toLong() }`.
     * What remains, like `class Foo(x: Int) { val x: Int = x }`, cannot be distinguished from
     * a constructor property in the metadata, and has the same API.
     */
    private fun KmProperty.isDeclaredBy(parameter: KmValueParameter): Boolean =
        name == parameter.name &&
            kind == MemberKind.DECLARATION &&
            modality != Modality.ABSTRACT &&
            receiverParameterType == null &&
            typeParameters.isEmpty() &&
            !isDelegated &&
            !isLateinit &&
            !isConst &&
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
     * Whether the [function] reflected from a built-in type is an override of a member of its supertypes,
     * told by its declaration in the built-in [owner] class.
     * A member not declared there, like the one inherited from a supertype, or the one of the JVM type only,
     * like `String.chars()`, is not an override.
     */
    private fun isOverride(owner: KmClass?, function: KFunction<*>): Boolean {
        if (owner == null || function.parameters.any { it.kind == KParameter.Kind.EXTENSION_RECEIVER }) return false
        val parameters = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
        val declaration = owner.functions.firstOrNull { candidate ->
            candidate.name == function.name
                    && candidate.receiverParameterType == null
                    && candidate.valueParameters.size == parameters.size
                    && candidate.valueParameters.zip(parameters).all { (kmParameter, parameter) ->
                        sameClassifier(kmParameter.type, parameter.type)
                    }
        } ?: return false
        return isOverride(owner, declaration)
    }

    private fun isOverride(owner: KmClass?, property: KProperty1<*, *>): Boolean {
        val declaration = owner?.properties?.firstOrNull {
            it.name == property.name && it.receiverParameterType == null
        } ?: return false
        return isOverride(owner, declaration)
    }

    private fun sameClassifier(kmType: KmType, type: kotlin.reflect.KType): Boolean =
        when (val classifier = type.classifier) {
            is KClass<*> -> (kmType.classifier as? KmClassifier.Class)?.name?.replace('/', '.') == classifier.qualifiedName
            is KTypeParameter -> kmType.classifier is KmClassifier.TypeParameter
            else -> false
        }

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
                val nestedClassFiles = newNestedClassFiles()
                root.walkTopDown()
                    .filter { it.isFile && isIndexedClassFile(it.name) }
                    .map { file -> file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class") to file }
                    .sortedBy { (name, _) -> nestingDepth(name) }
                    .forEach { (name, file) ->
                        if (isSkipped(name, nestedClassFiles, indexedClasses)) return@forEach
                        indexClass(file.readBytes(), indexedClasses, funcIndex, propIndex, classes, nestedClassFiles)
                    }
            } else {
                scanJarFile(root, roots, indexedClasses, funcIndex, propIndex, classes)
            }
        }

        // the scopes of the enclosing classes, from the outermost one, unless any of them is missing or unusable
        fun enclosingScopes(indexedClass: IndexedClass): List<ClassScope>? =
            enclosingClassNames(indexedClass.name).map { name ->
                classes[name]?.takeIf { it.isUsable }?.scope ?: return null
            }

        fun isApi(indexedClass: IndexedClass): Boolean =
            enclosingScopes(indexedClass)?.let { indexedClass.scope.visibility.isApiIn(it) } == true

        // the member extensions of public objects, which can be imported like the top-level ones,
        // not the ones of protected objects, as they would be shown to all the users of the receiver type
        classes.values
            .filter { indexedClass ->
                indexedClass.scope.visibility == Visibility.PUBLIC && indexedClass.isUsable &&
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
     * to avoid reading them, while the other ones are read, as `$` can be part of a class name, like in `Outer$$Named`.
     */
    private fun isIndexedClassFile(fileName: String): Boolean =
        fileName.endsWith(".class") && fileName != "module-info.class" &&
            fileName.removeSuffix(".class").split('$').drop(1).none { it.firstOrNull()?.isDigit() == true }

    private fun newNestedClassFiles(): NestedClassFiles? =
        if (skipNonKotlinNestedClassFiles) NestedClassFiles() else null

    /**
     * The number of `$` in the simple name of the class file, as the binary name of a nested class
     * usually extends the one of its outer class, like `Outer$Nested`, so that ordering by it reads the outer class first.
     */
    private fun nestingDepth(path: String): Int = path.substringAfterLast('/').count { it == '$' }

    /**
     * Whether the class file of the class with the internal [name] is skipped without reading it,
     * which still shadows, like an indexed one, the class of the same name in the next roots.
     */
    private fun isSkipped(
        name: String,
        nestedClassFiles: NestedClassFiles?,
        indexedClasses: MutableSet<String>
    ): Boolean {
        if (nestedClassFiles == null || !nestedClassFiles.isNonKotlinNestedClass(name)) return false
        indexedClasses.add(name.replace('/', '.'))
        skippedClassFileCount++
        return true
    }

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
                val nestedClassFiles = newNestedClassFiles()
                jar.entries().asSequence()
                    // skip the class versions of multi-release jars
                    .filter { !it.name.startsWith("META-INF/") }
                    .filter { isIndexedClassFile(it.name.substringAfterLast('/')) }
                    // the entries are in the archive order, while an outer class should be read before its nested ones
                    .sortedBy { nestingDepth(it.name) }
                    .forEach { entry ->
                        if (isSkipped(entry.name.removeSuffix(".class"), nestedClassFiles, indexedClasses)) return@forEach
                        val bytes = jar.getInputStream(entry).use { it.readBytes() }
                        indexClass(bytes, indexedClasses, funcIndex, propIndex, classes, nestedClassFiles)
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
        classes: MutableMap<String, IndexedClass>,
        nestedClassFiles: NestedClassFiles?
    ) {
        val reader = try {
            ClassReader(bytes)
        } catch (_: Exception) {
            return
        }
        val className = reader.className.replace('/', '.')
        if (!indexedClasses.add(className)) return

        val classFile = try {
            readClassFile(reader)
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
            classes.getOrPut(kmClass.name) {
                IndexedClass(
                    name = kmClass.name,
                    binaryName = className,
                    scope = kmClass.scope,
                    isUsable = isUsableClass(kmClass, classFile),
                    isSearchable = isListedClass(kmClass, classFile),
                    extensions = if (isObject) {
                        kmClass.functions.filter { it.receiverParameterType != null && !classFile.isHidden(it) }
                    } else emptyList(),
                    extensionProperties = if (isObject) {
                        kmClass.properties.filter { it.receiverParameterType != null && !classFile.isHidden(it) }
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
            kmPackage.functions.filter { it.receiverParameterType != null && !classFile.isHidden(it) },
            kmPackage.properties.filter { it.receiverParameterType != null && !classFile.isHidden(it) },
            className,
            source = packageSourceOf(className),
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
        // Only the extensions of the API are indexed, the hidden ones are already filtered out by the caller.
        // Once imported, even the members of an object are shown to all the users of the receiver type,
        // like the top-level ones, so only the public ones are.
        for (function in functions.filter { it.visibility.isApiIn(emptyList()) }) {
            val receiverType = function.receiverParameterType ?: continue
            val receiverName = receiverIndexKey(receiverType)

            funcIndex.getOrPut(receiverName) { mutableListOf() }
                .add(IndexedExtension(function, className, source, isObjectMember))
        }

        for (property in properties.filter { it.visibility.isApiIn(emptyList()) }) {
            val receiverType = property.receiverParameterType ?: continue
            val receiverName = receiverIndexKey(receiverType)

            propIndex.getOrPut(receiverName) { mutableListOf() }
                .add(IndexedExtensionProperty(property, className, source, isObjectMember))
        }
    }

    /**
     * Whether the class is listed in its outer class, if nested, and found by the [search],
     * provided that it is part of the API, as well as all its enclosing classes.
     * Companion objects are not, as they are already rendered within their outer class,
     * neither are the classes which are not [usable][isUsableClass] in the source code.
     */
    private fun isListedClass(kmClass: KmClass, classFile: ClassFile): Boolean =
        kmClass.kind != ClassKind.COMPANION_OBJECT &&
            isUsableClass(kmClass, classFile)

    /**
     * Whether the class, declared in the metadata, can be used in the source code, regardless of its visibility,
     * which is not the case for the `$serializer` generated by kotlinx.serialization,
     * deprecated with the `HIDDEN` level, or recognized by its name and supertype, if generated by an older plugin,
     * neither for a class marked as `ACC_SYNTHETIC` by a bytecode tool.
     * The classes nested in an unusable class are not usable either.
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
     * Visits a `@kotlin.Deprecated` annotation of a class or a method, calling [onHidden] when its level is `HIDDEN`,
     * or a `@kotlin.DeprecatedSinceKotlin` one, calling [onHidden] when it is [hidden since][isHiddenSince]
     * the Kotlin version of the classpath, either of which makes the declaration unusable in the source code.
     * Returns `null` for any other annotation.
     */
    private fun hiddenDeprecationVisitor(descriptor: String, onHidden: () -> Unit): AnnotationVisitor? =
        when (descriptor) {
            DEPRECATED_DESCRIPTOR -> object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visitEnum(name: String?, descriptor: String, value: String) {
                    if (name == "level" && value == DeprecationLevel.HIDDEN.name) onHidden()
                }
            }
            DEPRECATED_SINCE_KOTLIN_DESCRIPTOR -> object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any?) {
                    if (name == "hiddenSince" && value is String && isHiddenSince(value)) onHidden()
                }
            }
            else -> null
        }

    /**
     * Whether a declaration annotated with `@DeprecatedSinceKotlin(hiddenSince = version)` is hidden.
     * The compiler hides it from the code compiled with the API version of at least [version],
     * which defaults to the version of the compiler, and should not exceed the one of the standard library,
     * so the [stdlibVersion] of the classpath is what the up-to-date user code is assumed to be compiled with.
     * A malformed version, rejected by the compiler, does not hide anything.
     */
    private fun isHiddenSince(version: String): Boolean =
        parseKotlinVersion(version)?.let { it <= stdlibVersion } == true

    /**
     * Reads the `@Metadata` annotation, and the flags telling whether the class is usable in the source code,
     * and which of its methods are hidden, from the class bytes, without loading the class.
     */
    private fun readClassFile(reader: ClassReader): ClassFile {
        var metadata: Metadata? = null
        var isHiddenDeprecated = false
        val memberClasses = mutableListOf<MemberClass>()
        val nestMembers = mutableListOf<String>()
        val hiddenMethods = mutableSetOf<String>()
        val annotationsMethods = mutableSetOf<String>()
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                if (descriptor == METADATA_DESCRIPTOR) {
                    MetadataVisitor { metadata = it }
                } else {
                    hiddenDeprecationVisitor(descriptor) { isHiddenDeprecated = true }
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
                if (name.endsWith(ANNOTATIONS_METHOD_SUFFIX)) annotationsMethods += name + descriptor
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor? =
                        hiddenDeprecationVisitor(annotationDescriptor) { hiddenMethods += name + descriptor }
                }
            }

            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                // the anonymous and local classes are not members
                if (outerName != null && innerName != null) {
                    memberClasses += MemberClass(name, outerName, innerName)
                }
            }

            override fun visitNestMember(nestMember: String) {
                nestMembers += nestMember
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return ClassFile(
            metadata = metadata,
            isSynthetic = reader.access and Opcodes.ACC_SYNTHETIC != 0,
            isHiddenDeprecated = isHiddenDeprecated,
            memberClasses = memberClasses,
            nestMembers = nestMembers,
            hiddenMethods = hiddenMethods,
            annotationsMethods = annotationsMethods
        )
    }

    /**
     * What is read from a class file, without loading the class.
     *
     * @property memberClasses the member classes, of this or the other classes it refers to,
     *   declared by the `InnerClasses` attribute.
     * @property nestMembers the classes compiled from the same source as this nest host,
     *   declared by the `NestMembers` attribute.
     * @property hiddenMethods the JVM signatures, like `max(Ljava/lang/CharSequence;)Ljava/lang/Character;`,
     *   of the methods annotated as hidden, see [hiddenDeprecationVisitor].
     * @property annotationsMethods the JVM signatures of the synthetic methods for the annotations of the properties,
     *   like `getName${'$'}annotations()V`.
     */
    private class ClassFile(
        val metadata: Metadata?,
        val isSynthetic: Boolean,
        val isHiddenDeprecated: Boolean,
        val memberClasses: List<MemberClass> = emptyList(),
        val nestMembers: List<String> = emptyList(),
        val hiddenMethods: Set<String> = emptySet(),
        val annotationsMethods: Set<String> = emptySet()
    ) {

        /*
         * Whether a declaration of the class is deprecated as hidden, and cannot be used in the source code,
         * like an overload kept only for the binary compatibility, which the metadata does not tell.
         * The annotation is found on the JVM method of a function or a constructor,
         * and on the synthetic method for the annotations of a property, or its getter, if annotated itself.
         * The ACC_SYNTHETIC flag, which the compiler also sets on the hidden methods, is not relied on,
         * as @JvmSynthetic sets it on the declarations which are still callable from Kotlin,
         * while the field of a hidden @JvmField or const property does not have it.
         */

        fun isHidden(function: KmFunction): Boolean = isHidden(function.signature)

        fun isHidden(constructor: KmConstructor): Boolean = isHidden(constructor.signature)

        fun isHidden(property: KmProperty): Boolean =
            isHidden(property.syntheticMethodForAnnotations) || isHidden(property.getterSignature)

        /**
         * Whether the class declares the synthetic method for the annotations of a property, by its [signature].
         */
        fun declares(signature: JvmMethodSignature): Boolean = signature.toString() in annotationsMethods

        // most classes have no hidden methods, so that no signature is rendered to a string for them
        private fun isHidden(signature: JvmMethodSignature?): Boolean =
            hiddenMethods.isNotEmpty() && signature != null && signature.toString() in hiddenMethods

    }

    /**
     * The class with the internal [name], declared as the member named [simpleName] of the [outerName] class.
     */
    private class MemberClass(
        val name: String,
        val outerName: String,
        val simpleName: String
    )

    /**
     * What the classpath scan learns, from the class files read in a classpath root, about the classes nested in them,
     * by their internal names, telling the nested class files which cannot contain an indexed Kotlin class,
     * so that they are not even read, which matters for Java libraries with thousands of nested classes:
     *
     * - the classes nested in a class without the Kotlin class metadata, like a Java class or a Kotlin file facade,
     *   as only a Kotlin class can contain a Kotlin class,
     * - the members of a Kotlin class declared in its metadata neither as nested classes nor as enum entries,
     *   like the generated `$DefaultImpls` or `$WhenMappings`.
     *
     * Only what the class files declare is trusted, never the names of the class files,
     * as a top-level Kotlin class can be named like `JavaOuter$Name`.
     * A class nested in a class which is missing from the root, or not read yet, is read, as any other class.
     */
    private class NestedClassFiles {

        private val memberClasses = mutableMapOf<String, MemberClass>()

        private val nonKotlinClasses = mutableSetOf<String>()

        // the simple names of the nested classes and enum entries of the Kotlin classes, declared in their metadata
        private val kotlinNestedClasses = mutableMapOf<String, Set<String>>()

        fun add(name: String, classFile: ClassFile, kmClass: KmClass?) {
            classFile.memberClasses.forEach { memberClasses.putIfAbsent(it.name, it) }
            if (kmClass != null) {
                // an enum entry with a body is compiled to a nested class too, having the class metadata
                kotlinNestedClasses[name] = (kmClass.nestedClasses + kmClass.kmEnumEntries.map { it.name }).toSet()
            } else {
                nonKotlinClasses += name
                nonKotlinClasses += classFile.nestMembers
            }
        }

        fun isNonKotlinNestedClass(name: String): Boolean {
            if (name in nonKotlinClasses) return true
            val memberClass = memberClasses[name] ?: return false
            val isNonKotlin = memberClass.outerName in nonKotlinClasses ||
                kotlinNestedClasses[memberClass.outerName]?.let { memberClass.simpleName !in it } == true
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
        return resolve(name)?.render(name, page, pageSize)
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
     * A single-line declaration, like a function, of the body of a [ClassListing],
     * or found by a [MemberListing], rendered there as is.
     */
    class Declaration(val signature: String) : ClassBodyItem, MemberItem

    /**
     * The companion object of a [ClassListing], declared with the [header], with its [members],
     * a single item, as the members of the companion object are paged by resolving it directly by the [name].
     *
     * @param name the fully qualified name of the companion object to resolve, like `com.example.Outer.Companion`.
     */
    class CompanionObject(val header: String, val members: List<String>, val name: String) : ClassBodyItem

    /**
     * A member of a companion object found by a [MemberListing],
     * rendered within the declaration of the companion object, the [header], repeated on every page.
     */
    class CompanionMember(val header: String, val signature: String) : MemberItem

    /**
     * An extension, listed under the `// from` header of its [source], repeated on every page.
     */
    class Extension(val source: String, val signature: String) : ClassItem, MemberItem

}

/**
 * The extensions rendered under the `// from` header of their source, repeated whenever the source changes.
 */
private fun List<ListingItem.Extension>.renderLines(): List<String> = buildList {
    var source: String? = null
    for (extension in this@renderLines) {
        if (extension.source != source) {
            add("// from ${extension.source}")
            source = extension.source
        }
        add(extension.signature)
    }
}

/**
 * A class listed with its [header], the declaration line without the body, followed by the [items]:
 * the enum entries, and the members of the class body, see [ListingItem.ClassBodyItem],
 * then the extensions, see [ListingItem.Extension].
 *
 * @param isEnum whether the class is an enum class, whose members are separated from its entries with a semicolon.
 */
private class ClassListing(
    val header: String,
    val isEnum: Boolean,
    override val items: List<ListingItem.ClassItem>
) : Resolved<ListingItem.ClassItem> {

    /**
     * The class body is shown only on a page with entries or members, and the semicolon separating the members
     * of an enum class from its entries on every such page without entries, as the last entry carries it.
     * A class without a body still has its header shown on the first page, e.g. to show the constructor.
     */
    override fun renderPage(page: Page<ListingItem.ClassItem>): List<String> = buildList {
        val body = page.items.filterIsInstance<ListingItem.ClassBodyItem>()
        if (body.isNotEmpty()) {
            add("$header {")
            if (isEnum && body.none { it is ListingItem.EnumEntry }) add("    ;")
            body.forEach { item ->
                when (item) {
                    is ListingItem.EnumEntry -> add("    ${item.line}")
                    is ListingItem.Declaration -> add("    ${item.signature}")
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
            companion.members.forEach { add("    $it") }
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
            isEnum: Boolean = false,
            enumEntryNames: List<String> = emptyList(),
            members: List<ListingItem.ClassBodyItem> = emptyList(),
            extensions: List<ListingItem.Extension> = emptyList()
        ): ClassListing = ClassListing(
            header,
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
 * the members, see [ListingItem.Declaration], then the members of the companion object,
 * see [ListingItem.CompanionMember], and then the extensions, see [ListingItem.Extension].
 */
private class MemberListing(override val items: List<ListingItem.MemberItem>) : Resolved<ListingItem.MemberItem> {

    override fun renderPage(page: Page<ListingItem.MemberItem>): List<String> = buildList {
        page.items.filterIsInstance<ListingItem.Declaration>().forEach { add(it.signature) }
        page.items.filterIsInstance<ListingItem.CompanionMember>().groupBy { it.header }.forEach { (header, members) ->
            add("$header {")
            members.forEach { add("    ${it.signature}") }
            add("}")
        }
        addAll(page.items.filterIsInstance<ListingItem.Extension>().renderLines())
    }

    companion object {

        /**
         * The listing of the [items], or `null` if nothing is found.
         */
        fun of(items: List<ListingItem.MemberItem>): MemberListing? = if (items.isEmpty()) null else MemberListing(items)

    }

}

/**
 * The key of the extension index under which an extension with the given receiver [type] is listed.
 * Extensions on a type parameter, like `fun <T> T.let()`, are not listed for any class,
 * so their key contains `[`, which cannot appear in a class name, nor in a segment of its package (JVMS §4.2.2).
 * A suspend function type, like `suspend () -> Unit`, is stored in the metadata as `Function1<Continuation<Unit>, Any?>`,
 * so it is keyed as `kotlin.coroutines.SuspendFunction0` instead, not to be listed as an extension of `kotlin.Function1`.
 */
internal fun receiverIndexKey(type: KmType): String = when (val classifier = type.classifier) {
    is KmClassifier.Class -> type.takeIf { it.isSuspend }
        ?.decodeRuntimeFunctionType()
        ?.let { "kotlin.coroutines.SuspendFunction${it.parameters.size}" }
        ?: classifier.name.replace('/', '.')
    is KmClassifier.TypeAlias -> classifier.name.replace('/', '.')
    is KmClassifier.TypeParameter -> TYPE_PARAMETER_RECEIVER_KEY
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
