/*
 * xemantic-kotlin-introspection - Kotlin API introspection of the classpath, rendering LLM-friendly signatures
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.sameAs
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test

/**
 * The classpath scan of the directory and jar roots, telling which class files are indexed, read, or skipped.
 */
class ClasspathScanTest {

    @TempDir
    lateinit var tempDir: Path

    private val isolatedTestFixtures: List<File> = testFixtures("isolatedTestFixtures")

    private val isolatedPackage = "com/xemantic/kotlin/introspection/isolated"

    /**
     * The class files of `IsolatedTestClass`, and of the file facade declaring its extension, by their paths.
     */
    private val isolatedClassFiles: List<Pair<String, File>> = isolatedTestFixtures.flatMap { dir ->
        listOf("IsolatedTestClass", "IsolatedTestClassKt").map { name ->
            "$isolatedPackage/$name.class" to dir.resolve("$isolatedPackage/$name.class")
        }.filter { (_, file) -> file.exists() }
    }

    private fun directoryRoot(name: String, prefix: String = ""): Path = tempDir.resolve(name).also { root ->
        isolatedClassFiles.forEach { (path, file) -> file.copyTo(root.resolve(prefix + path).toFile()) }
    }

    @Test
    fun `should not index class files of multi-release versions of directory root, like of jar root without Multi-Release attribute`() {
        // given
        // a class loader of a directory root does not serve the class versions, so that a class only in
        // META-INF/versions cannot be loaded by its name, neither from this jar, without Multi-Release: true
        // in its manifest, unlike from a multi-release jar, see the tests below
        val root = directoryRoot("classes", prefix = "META-INF/versions/9/")
        val jar = tempDir.resolve("versions.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            isolatedClassFiles.forEach { (path, file) ->
                out.putNextEntry(JarEntry("META-INF/versions/9/$path"))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }

        // when
        val directoryScan = scan(root)
        val jarScan = scan(jar)

        // then
        assert(isolatedClassFiles.size == 2)
        assert(directoryScan.readClassFileCount == 0)
        assert(directoryScan.extensionIndex.isEmpty())
        assert(directoryScan.classIndex.isEmpty())
        directoryScan.indexDump() sameAs jarScan.indexDump()
    }

    /**
     * A jar of the class [entries], by their paths, with `Multi-Release: true` in its manifest.
     */
    private fun multiReleaseJar(vararg entries: List<Pair<String, ByteArray>>): Path =
        tempDir.resolve("multi-release.jar").also { jar ->
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
            }
            JarOutputStream(jar.outputStream(), manifest).use { out ->
                entries.toList().flatten().forEach { (path, bytes) ->
                    out.putNextEntry(JarEntry(path))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }

    /**
     * The entries of the isolated class files under the [prefix], like `META-INF/versions/9/`,
     * either [intact], or ones which cannot be parsed, so that a class indexed from them is not.
     */
    private fun isolatedEntries(prefix: String = "", intact: Boolean = true): List<Pair<String, ByteArray>> =
        isolatedClassFiles.map { (path, file) -> prefix + path to if (intact) file.readBytes() else ByteArray(64) }

    /**
     * Asserts that the class loader serves the intact isolated class files from the [jar],
     * which are the only ones indexed by the [jarScan], like from a directory root of them.
     */
    private fun assertIndexedAsServed(jar: Path, jarScan: DefaultKotlinIntrospector.ClasspathScan) {
        val (path, file) = isolatedClassFiles.first()
        val served = rootsClassLoader(jar).use { loader -> loader.getResourceAsStream(path)?.use { it.readBytes() } }
        assert(served contentEquals file.readBytes())
        assert(jarScan.readClassFileCount == 2)
        jarScan.indexDump() sameAs scan(directoryRoot("classes")).indexDump()
    }

    @Test
    fun `should index class versions of multi-release jar served by class loader without base entries`() {
        // given
        val jar = multiReleaseJar(isolatedEntries("META-INF/versions/9/"))

        // when
        val scan = scan(jar)

        // then
        assertIndexedAsServed(jar, scan)
    }

    @Test
    fun `should index class versions of multi-release jar served by class loader instead of base entries`() {
        // given
        val jar = multiReleaseJar(isolatedEntries(intact = false), isolatedEntries("META-INF/versions/9/"))

        // when
        val scan = scan(jar)

        // then
        assertIndexedAsServed(jar, scan)
    }

    @Test
    fun `should index latest class versions of multi-release jar not newer than running JDK`() {
        // given
        val runningVersion = Runtime.version().feature()
        val jar = multiReleaseJar(
            isolatedEntries(intact = false),
            isolatedEntries("META-INF/versions/9/", intact = false),
            isolatedEntries("META-INF/versions/$runningVersion/"),
            isolatedEntries("META-INF/versions/${runningVersion + 1}/", intact = false)
        )

        // when
        val scan = scan(jar)

        // then
        assertIndexedAsServed(jar, scan)
    }

    @Test
    fun `should index base entries of multi-release jar instead of class versions newer than running JDK`() {
        // given
        val jar = multiReleaseJar(
            isolatedEntries(),
            isolatedEntries("META-INF/versions/${Runtime.version().feature() + 1}/", intact = false)
        )

        // when
        val scan = scan(jar)

        // then
        assertIndexedAsServed(jar, scan)
    }

    /**
     * Creates the symbolic [link] to the [target], unless it cannot be created, like on Windows without the privilege.
     */
    private fun assumeSymbolicLink(link: Path, target: Path) {
        val isLinked = try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: FileSystemException) {
            false
        }
        assumeTrue(isLinked, "the symbolic link cannot be created")
    }

    @Test
    fun `should scan each directory of directory root once through symbolic link cycle`() {
        // given
        val root = directoryRoot("classes")
        val directoryScan = scan(root)
        // like pkg/loop -> .., reaching the package directory again through com/xemantic/.../isolated/loop/isolated
        val link = root.resolve("$isolatedPackage/loop")
        assumeSymbolicLink(link, link.parent.parent)

        // when
        val scan = scan(root)

        // then
        assert(scan.readClassFileCount == 2)
        scan.indexDump() sameAs directoryScan.indexDump()
    }

    @Test
    fun `should shadow class of next root by class file reached also through shallower symbolic link`() {
        // given
        // the class file, which cannot be parsed, is reached first through the alias, by a name it is not loaded by,
        // and then by its own name, under which it shadows the intact one of the next root, like for the class loader
        val path = "$isolatedPackage/IsolatedTestClass.class"
        val first = tempDir.resolve("first")
        first.resolve(path).toFile().apply { parentFile.mkdirs() }.writeBytes(ByteArray(4))
        assumeSymbolicLink(first.resolve("alias"), first.resolve(isolatedPackage))
        val second = directoryRoot("second")

        // when
        val scan = scan(first, second)

        // then
        val served = rootsClassLoader(first, second).use { loader -> loader.getResourceAsStream(path)?.use { it.readBytes() } }
        assert(served?.size == 4)
        assert(scan.extensionIndex.values.flatten().map { it.function.name } == listOf("isolatedExtension"))
        assert(scan.classIndex["isolatedtestclass"] == null)
    }

    @Test
    fun `should index rest of jar after failing to read class file`() {
        // given
        val jar = tempDir.resolve("corrupt.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            // the first entry, read first, as all of them are top-level classes
            out.putNextEntry(JarEntry("$isolatedPackage/Corrupt.class"))
            out.write(ByteArray(64) { 0xCA.toByte() })
            out.closeEntry()
            isolatedClassFiles.forEach { (path, file) ->
                out.putNextEntry(JarEntry(path))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        corruptFirstEntryData(jar)

        // when
        val scan = scan(jar)

        // then
        assert(scan.readClassFileCount == 3)
        assert(scan.extensionIndex.values.flatten().map { it.function.name } == listOf("isolatedExtension"))
        assert(scan.classIndex["isolatedtestclass"] == listOf("com.xemantic.kotlin.introspection.isolated.IsolatedTestClass"))
    }

    @Test
    fun `should index rest of directory root after failing to read class file`() {
        // given
        val root = directoryRoot("classes")
        val unreadable = root.resolve("$isolatedPackage/Unreadable.class").toFile().apply {
            writeBytes(ByteArray(64))
            setReadable(false)
        }
        // like when run with the privileges reading any file
        assumeTrue(!unreadable.canRead(), "the class file cannot be made unreadable")

        // when
        val scan = try {
            scan(root)
        } finally {
            unreadable.setReadable(true)
        }

        // then
        assert(scan.readClassFileCount == 3)
        assert(scan.extensionIndex.values.flatten().map { it.function.name } == listOf("isolatedExtension"))
        assert(scan.classIndex["isolatedtestclass"] == listOf("com.xemantic.kotlin.introspection.isolated.IsolatedTestClass"))
    }

    @Test
    fun `should count class files shadowed by earlier root neither as read nor as skipped`() {
        // given
        val roots = listOf("first", "second").map { name ->
            tempDir.resolve(name).also { root ->
                isolatedTestFixtures.forEach { dir ->
                    dir.walkTopDown().filter { it.isFile && it.name.startsWith("JavaOuter") }.forEach { file ->
                        file.copyTo(root.resolve(file.relativeTo(dir).path).toFile())
                    }
                }
            }
        }

        // when
        val oneRoot = scan(roots.first())
        val twoRoots = scan(*roots.toTypedArray())

        // then
        assert(oneRoot.readClassFileCount > 0 && oneRoot.skippedClassFileCount > 0)
        assert(twoRoots.readClassFileCount == oneRoot.readClassFileCount)
        assert(twoRoots.skippedClassFileCount == oneRoot.skippedClassFileCount)
        assert(twoRoots.skippedClassFiles == oneRoot.skippedClassFiles)
        twoRoots.indexDump() sameAs oneRoot.indexDump()
    }

}

/**
 * Overwrites the start of the compressed data of the first entry of the [jar] with an invalid deflate block,
 * so that reading the entry fails, while the jar itself, and its other entries, can still be read.
 */
private fun corruptFirstEntryData(jar: Path) {
    val bytes = jar.readBytes()
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    // the local file header: the name and the extra field lengths at 26 and 28, followed by them, and by the data
    val dataOffset = 30 + header.getShort(26).toUShort().toInt() + header.getShort(28).toUShort().toInt()
    // BFINAL set, and the reserved BTYPE 11, which no inflater accepts
    bytes[dataOffset] = 0xFF.toByte()
    jar.writeBytes(bytes)
}
