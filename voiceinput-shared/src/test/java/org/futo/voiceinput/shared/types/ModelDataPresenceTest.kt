package org.futo.voiceinput.shared.types

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelDataPresenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeModelFiles(dir: File) {
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            .forEach { File(dir, it).writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun isPresent_markerWritten_true() {
        val dir = tempFolder.newFolder("model")
        writeModelFiles(dir)
        File(dir, ParakeetModelPresence.COMPLETE_MARKER_NAME).writeText("manifest")

        assertTrue(ParakeetModelPresence.isPresent(dir, bundledAssetsAvailable = false))
    }

    @Test
    fun isPresent_fourFilesButNoMarker_false() {
        // Regression: "4 non-empty files" used to count as present — an
        // interrupted download or partial sideload must NOT (the engine would
        // mmap a truncated .onnx and SIGABRT inside onnxruntime).
        val dir = tempFolder.newFolder("model")
        writeModelFiles(dir)

        assertFalse(ParakeetModelPresence.isPresent(dir, bundledAssetsAvailable = false))
    }

    @Test
    fun isPresent_emptyDir_false() {
        val dir = tempFolder.newFolder("model")

        assertFalse(ParakeetModelPresence.isPresent(dir, bundledAssetsAvailable = false))
    }

    @Test
    fun isPresent_bundledAssets_true() {
        // Emulator-only test variant: bundled assets seed on first load.
        val dir = tempFolder.newFolder("model")

        assertTrue(ParakeetModelPresence.isPresent(dir, bundledAssetsAvailable = true))
    }
}
