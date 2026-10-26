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
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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
    fun `should not index class files of multi-release versions of directory root, like of jar root`() {
        // given
        // a class loader of a directory root does not serve the class versions, so that a class only in
        // META-INF/versions cannot be loaded by its name, neither from this jar, without Multi-Release: true
        // in its manifest; the one of a multi-release jar does, which its scan, skipping META-INF, does not follow
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
