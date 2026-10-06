import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import np.opd.asr.WhisperCppRecognizer
import np.opd.asr.WhisperNative
import np.opd.speech.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

var failures = 0
fun ok(name: String, cond: Boolean, detail: String = "") { if (cond) println("PASS  $name") else { failures++; println("FAIL  $name  $detail") } }
inline fun expectAsr(kind: AsrErrorKind, block: () -> Unit): AsrException? = try { block(); null } catch (e: AsrException) { e }

fun wav(samples: FloatArray): ByteArray {
    val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()); b.putInt(36 + samples.size * 2); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
    b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(samples.size * 2)
    samples.forEach { b.putShort((it * 32767).toInt().toShort()) }
    return b.array()
}

fun main(): Unit = runBlocking {
    val dir = kotlin.io.path.createTempDirectory("bridge").toFile()
    val model = File(dir, "good.bin").also { it.writeBytes(byteArrayOf(0x6c, 0x6d, 0x67, 0x67) + ByteArray(2000)) }
    val bad = File(dir, "bad.bin").also { it.writeBytes("PK not a model".toByteArray()) }
    val one = FloatArray(16000) { 0.1f }

    // ---- loading and error mapping (the missing-library case must run BEFORE the library is loaded in this JVM)
    val noLib = expectAsr(AsrErrorKind.RUNTIME_UNAVAILABLE) { runBlocking { WhisperCppRecognizer(model, WhisperNative(), libraryName = "does_not_exist").initialize() } }
    ok("missing native library -> RUNTIME_UNAVAILABLE", noLib?.kind == AsrErrorKind.RUNTIME_UNAVAILABLE, noLib?.message ?: "")
    val r = WhisperCppRecognizer(model)
    ok("not ready before initialize", !r.isReady())
    r.initialize()
    ok("ready after initialize", r.isReady())
    ok("diagnostics name the pinned version and CPU flags", r.diagnostics().contains("whisper.cpp v1.8.3") && r.diagnostics().contains("-march=host-check") && r.diagnostics().contains("DOTPROD"), r.diagnostics())
    r.initialize(); ok("second initialize is a no-op", r.isReady())

    ok("missing file -> MODEL_MISSING", expectAsr(AsrErrorKind.MODEL_MISSING) { runBlocking { WhisperCppRecognizer(File(dir, "nope.bin")).initialize() } }?.kind == AsrErrorKind.MODEL_MISSING)
    val badEx = expectAsr(AsrErrorKind.INIT_FAILED) { runBlocking { WhisperCppRecognizer(bad).initialize() } }
    ok("damaged file -> INIT_FAILED with a readable reason", badEx?.kind == AsrErrorKind.INIT_FAILED && badEx.message!!.contains("bad.bin"), badEx?.message ?: "")

    // ---- transcription: marshalling of audio, parameters and results
    val segs = r.transcribe(one, AsrConfig(language = "ne", translate = false, threads = 6, audioContext = 0), prompt = null)
    ok("three segments came back", segs.size == 3, segs.toString())
    ok("Devanagari text intact", segs[0].text == "नमस्ते, मलाई ज्वरो छ।", segs[0].text)
    ok("timestamps converted from centiseconds to ms", segs[0].startMs == 0L && segs[0].endMs == 1500L && segs[1].startMs == 1500L, "${segs[0]} ${segs[1]}")
    ok("no-speech probability passed through", segs[0].noSpeechProb == 0.05f)
    ok("avg log-prob excludes the end-of-text token (expected -0.3)", Math.abs(segs[0].avgLogProb!! - (-0.3f)) < 1e-5, "${segs[0].avgLogProb}")
    ok("a split multi-byte character does not crash (U+FFFD instead)", segs[1].text.contains('�'), segs[1].text)
    ok("parameters reached whisper: ne, no translate, 6 threads, full window, fresh context", segs[2].text == "lang=ne translate=0 threads=6 ctx=0 prompt=<none> nocontext=1 samples=16000", segs[2].text)
    val withPrompt = r.transcribe(one, AsrConfig(language = "en", translate = true, threads = 3, audioContext = 600), prompt = "ज्वरो")
    ok("prompt, language, translate, threads, audio_ctx marshalled", withPrompt[2].text == "lang=en translate=1 threads=3 ctx=600 prompt=ज्वरो nocontext=1 samples=16000", withPrompt[2].text)
    ok("diagnostics show the encoder/decoder split", r.diagnostics().contains("encoder 111 ms, decoder 222 ms"), r.diagnostics())

    // ---- failure and cancellation
    val fail = expectAsr(AsrErrorKind.INFERENCE_FAILED) { runBlocking { r.transcribe(FloatArray(8000) { -1f }) } }
    ok("whisper failure -> INFERENCE_FAILED (no crash)", fail?.kind == AsrErrorKind.INFERENCE_FAILED, fail?.message ?: "")
    ok("engine usable after a failed segment", r.transcribe(one).size == 3)
    val empty = expectAsr(AsrErrorKind.BAD_AUDIO) { runBlocking { r.transcribe(FloatArray(0)) } }
    ok("empty audio -> BAD_AUDIO", empty?.kind == AsrErrorKind.BAD_AUDIO, empty?.message ?: "")

    r.cancel(); // a stale cancel while idle (e.g. a watchdog firing late) must NOT poison the next run
    ok("late cancel does not abort the next segment", r.transcribe(one).size == 3)

    var cancelled: AsrException? = null
    val hung = Thread { try { runBlocking { r.transcribe(FloatArray(16000) { 0.99f }) } } catch (e: AsrException) { cancelled = e } }
    hung.start(); Thread.sleep(150)
    val t0 = System.currentTimeMillis(); r.cancel(); hung.join(5000)
    ok("a hung inference stops promptly when cancelled", !hung.isAlive && cancelled?.kind == AsrErrorKind.CANCELLED && System.currentTimeMillis() - t0 < 3000, "alive=${hung.isAlive} ${cancelled?.kind}")

    // ---- through the real app stack: WAV -> GuardedRecognizer -> LocalAsrService -> TranscriptLine
    val spec = ModelSpec("t", "T", "ne", AsrBackend.CPU_GGML, "ggml-whisper", listOf(ModelFileSpec("good.bin", null, null, null)), SourceInfo("o/r", null, false, ""), LicenseInfo("MIT", false, false, ""))
    val root = File(dir, "models").also { File(it, "t").mkdirs() }
    val installer = ModelInstaller(spec, root, null)
    installer.importFrom(model.inputStream().readBytes().let { java.io.ByteArrayInputStream(it + ByteArray(1_000_000)) })
    val service = LocalAsrService(installer, { f -> WhisperCppRecognizer(f) }, dispatcher = Dispatchers.Default)
    val lines = service.transcribeWav(wav(FloatArray(32000) { 0.1f }), voicedFraction = 0.5f)
    ok("end to end: WAV in, TranscriptLines out (unknown speaker, Nepali kept, broken fragment still present as text)", lines.size >= 2 && lines.all { it.speaker == "unknown" } && lines[0].text == "नमस्ते, मलाई ज्वरो छ।", lines.toString())
    ok("status is Ready (CPU) after use", service.status().label == "Ready (CPU)", service.status().label)
    ok("stats recorded a real-time factor", service.statsSummary().contains("RTF"), service.statsSummary())
    ok("engine diagnostics available for the developer area", service.engineDiagnostics().contains("whisper.cpp"), service.engineDiagnostics())
    service.deleteModel()
    ok("deleting the model releases the engine and files", !File(root, "t").exists() && service.status() is LocalAsrStatus.NeedsFile)  // this test manifest pins no checksum

    // ---- release
    r.release(); r.release()
    ok("release is idempotent and the engine reports not ready", !r.isReady())
    val after = expectAsr(AsrErrorKind.INIT_FAILED) { runBlocking { r.transcribe(one) } }
    ok("use after release is an error, not a crash", after?.kind == AsrErrorKind.INIT_FAILED, after?.message ?: "")

    println(if (failures == 0) "\nALL BRIDGE CHECKS PASSED" else "\n$failures CHECK(S) FAILED")
    dir.deleteRecursively()
    exitProcess(if (failures == 0) 0 else 1)
}
