package org.futo.voiceinput.shared.types

import android.content.Context
import androidx.annotation.StringRes
import org.futo.voiceinput.shared.ggml.ParakeetEngineConfig
import org.futo.voiceinput.shared.ggml.ParakeetSherpa
import org.futo.voiceinput.shared.ggml.SpeechEngine
import org.futo.voiceinput.shared.ggml.WhisperGGML
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


// Taken from https://github.com/tensorflow/tflite-support/blob/483c45d002cbed57d219fae1676a4d62b28fba73/tensorflow_lite_support/java/src/java/org/tensorflow/lite/support/common/FileUtil.java#L158
/**
 * Loads a file from the asset folder through memory mapping.
 *
 * @param context Application context to access assets.
 * @param filePath Asset path of the file.
 * @return the loaded memory mapped file.
 * @throws IOException if an I/O error occurs when loading the file model.
 */
@Throws(IOException::class)
private fun loadMappedFile(context: Context, filePath: String): MappedByteBuffer {
    context.assets.openFd(filePath).use { fileDescriptor ->
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
}

// Maybe add `val languages: Set<Language>`
interface ModelLoader {
    @get:StringRes
    val name: Int

    fun exists(context: Context): Boolean
    fun getRequiredDownloadList(context: Context): List<String>

    fun loadEngine(context: Context): SpeechEngine

    fun key(context: Context): Any
}

internal class ModelBuiltInAsset(
    override val name: Int,
    val ggmlFile: String
) : ModelLoader {
    override fun exists(context: Context): Boolean {
        return true
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf()
    }

    override fun loadEngine(context: Context): SpeechEngine {
        val file = loadMappedFile(context, ggmlFile)
        return WhisperGGML(file)
    }

    override fun key(context: Context): Any {
        return "BuiltIn$ggmlFile"
    }
}

@Throws(IOException::class)
private fun tryOpenDownloadedModel(file: File): MappedByteBuffer {
    val fis = file.inputStream()
    val channel = fis.channel

    return channel.map(
        FileChannel.MapMode.READ_ONLY,
        0, channel.size()
    ).load()
}

@Throws(IOException::class)
private fun Context.tryOpenDownloadedModel(pathStr: String): MappedByteBuffer {
    return tryOpenDownloadedModel(File(filesDir, pathStr))
}

internal class ModelDownloadable(
    override val name: Int,
    val ggmlFile: String,
    val checksum: String
) : ModelLoader {
    override fun exists(context: Context): Boolean {
        return getRequiredDownloadList(context).isEmpty()
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf(ggmlFile).filter {
            !File(context.filesDir, it).exists()
        }
    }

    override fun loadEngine(context: Context): SpeechEngine {
        val file = context.tryOpenDownloadedModel(ggmlFile)
        return WhisperGGML(file)
    }

    override fun key(context: Context): Any {
        return "Downloadable$ggmlFile$checksum"
    }
}

public class ModelFileFile(
    override val name: Int,
    val file: File,
) : ModelLoader {
    override fun exists(context: Context): Boolean {
        return file.exists()
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf()
    }

    override fun loadEngine(context: Context): SpeechEngine {
        val file = tryOpenDownloadedModel(file)
        return WhisperGGML(file)
    }

    override fun key(context: Context): Any {
        return "File${file.absolutePath}"
    }
}

/**
 * Parakeet TDT (sherpa-onnx) built-in model loader.
 *
 * The ONNX model (~250MB int8) is NOT bundled in the phone APK assets. It reaches the
 * device either by:
 *  - sideload/adb push directly into filesDir/models/<assetDir>/ (4 files:
 *    encoder*.onnx, decoder*.onnx, joiner*.onnx, tokens.txt), or
 *  - bundled assets under <assetDir>/ (emulator-only test variant), which are seeded
 *    into filesDir on first load.
 *
 * Seed copy discipline (known lessons applied):
 *   copy asset -> .tmp-<name> file -> verify copied byte count against the asset's
 *   declaredLength (when available; uncompressed assets only) -> atomic rename() to
 *   final name -> write a .complete marker LAST. Presence of the marker guarantees
 *   every file finished; without it a partially-copied ONNX file would cause an
 *   uncatchable native abort (SIGABRT) inside onnxruntime.
 */
internal class ModelParakeetAsset(
    override val name: Int,
    val assetDir: String,
    private val engineConfig: ParakeetEngineConfig = ParakeetEngineConfig()
) : ModelLoader {
    private fun targetDir(context: Context) = File(context.filesDir, "models/$assetDir")
    private fun completeMarker(context: Context) = File(targetDir(context), ".complete")

    private fun listAssetFiles(context: Context): List<String> {
        return try {
            context.assets.list(assetDir)?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    override fun exists(context: Context): Boolean {
        // Seeded successfully at some point in the past
        if (completeMarker(context).exists()) return true

        // Sideloaded (adb push) without going through the seed path: require ALL
        // four expected files present and non-empty. A partial sideload must NOT
        // count as present — otherwise the downloader never triggers and a
        // truncated .onnx SIGABRTs inside onnxruntime (uncatchable).
        val dir = targetDir(context)
        if (dir.isDirectory) {
            val required = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            val allPresent = required.all { name ->
                val f = File(dir, name)
                f.isFile && f.length() > 0L
            }
            if (allPresent) return true
        }

        // Seedable from bundled assets (emulator-only test variant)
        return listAssetFiles(context).isNotEmpty()
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf()
    }

    override fun loadEngine(context: Context): SpeechEngine {
        seedFromAssetsIfNeeded(context)

        val dir = targetDir(context)
        if (!dir.isDirectory) {
            throw IOException("Parakeet model directory missing: ${dir.absolutePath} - sideload the model files there")
        }

        return ParakeetSherpa(dir.absolutePath, engineConfig)
    }

    @Throws(IOException::class)
    private fun seedFromAssetsIfNeeded(context: Context) {
        if (completeMarker(context).exists()) return

        val assetFiles = listAssetFiles(context)
        if (assetFiles.isEmpty()) {
            // Nothing bundled in assets: expect the files to have been sideloaded
            // directly into the target directory.
            return
        }

        val target = targetDir(context)
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("Could not create model directory: ${target.absolutePath}")
        }

        val copiedManifest = mutableListOf<String>()

        for (name in assetFiles) {
            val assetPath = "$assetDir/$name"
            val finalFile = File(target, name)
            val tmpFile = File(target, ".tmp-$name")

            // declaredLength is only available for uncompressed assets
            val declaredLength: Long? = try {
                context.assets.openFd(assetPath).use { it.declaredLength }
            } catch (e: IOException) {
                null
            }

            tmpFile.delete()

            var copiedBytes = 0L
            val input = try {
                context.assets.open(assetPath)
            } catch (e: IOException) {
                null
            } ?: continue // e.g. a subdirectory entry such as test_wavs

            input.use { inp ->
                tmpFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val read = inp.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read
                    }
                }
            }

            if (declaredLength == null && copiedBytes == 0L) {
                // Compressed directory entry or empty asset - not a model file
                tmpFile.delete()
                continue
            }

            if (declaredLength != null && declaredLength != copiedBytes) {
                tmpFile.delete()
                throw IOException("Truncated asset copy for $assetPath: expected $declaredLength bytes, got $copiedBytes")
            }

            if (finalFile.exists() && !finalFile.delete()) {
                tmpFile.delete()
                throw IOException("Could not replace existing model file: ${finalFile.absolutePath}")
            }

            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.delete()
                throw IOException("Atomic rename failed for $assetPath")
            }

            copiedManifest.add("$name:$copiedBytes")
        }

        // Marker is written LAST: its presence guarantees every file above completed.
        completeMarker(context).writeText(copiedManifest.joinToString("\n"))
    }

    override fun key(context: Context): Any {
        return "Parakeet$assetDir"
    }
}
