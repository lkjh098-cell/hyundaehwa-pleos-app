package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Immutable packaged recordings; no network, arbitrary file lookup, or approximate text lookup. */
class CachedVoiceCatalog(private val context: Context) {
    private var matcher: CachedSpeechMatcher? = null
    private val verified = mutableSetOf<String>()
    @Synchronized fun plan(text: String): CachedSpeechPlan? {
        if (matcher == null) {
            val manifest = context.assets.open("speech-cache/provenance.json").bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.getString("version") == "speech-cache-v1")
            val chunks = manifest.getJSONArray("chunks")
            matcher = CachedSpeechMatcher((0 until chunks.length()).map { index ->
                val item = chunks.getJSONObject(index)
                CachedSpeechEntry(item.getString("text"), item.getString("file"), item.getDouble("durationSeconds"),
                    item.getString("sha256"), item.getLong("bytes"))
            })
        }
        return matcher?.match(text)
    }
    fun assetPath(entry: CachedSpeechEntry): String {
        require(CachedSpeechMatcher.validEntry(entry))
        return if (entry.file.startsWith("speech-cache/")) entry.file else "speech-cache/${entry.file}"
    }
    @Synchronized fun verify(entry: CachedSpeechEntry) {
        val key = "${entry.file}:${entry.sha256}:${entry.bytes}"
        if (key in verified) return
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        context.assets.open(assetPath(entry)).use { input ->
            val buffer = ByteArray(16_384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count; digest.update(buffer, 0, count)
            }
        }
        require(bytes == entry.bytes && digest.digest().joinToString("") { "%02x".format(it) }.equals(entry.sha256, true))
        verified += key
    }
}

/** Last fallback after both synthesis engines fail. Its start signal requires a progressing player. */
class CachedRecordedVoiceBackend(private val context: Context, private val attributes: AudioAttributes,
    private val catalog: CachedVoiceCatalog, private val requestedText: String) : VoiceBackend {
    override val source = VoiceSource.RECORDED_CACHE
    override val engineName = "앱에 저장된 한국어 음성 · Yuna · 로컬 재생"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var released = false
    private var plan: CachedSpeechPlan? = null
    private var player: MediaPlayer? = null
    private var listener: PlaybackListener? = null
    private var clipIndex = 0
    private var clipStarted = false
    private var outputStarted = false
    private var terminal = false
    private var watchdog: Runnable? = null
    private var onsetCheck: Runnable? = null
    override val remainingText: String? get() = plan?.remainingText(clipIndex)
    override val checkpoint: String get() = "cache:$clipIndex:${plan?.segments?.getOrNull(clipIndex)?.startOffset ?: 0}"
    override fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        worker.execute {
            val prepared = runCatching {
                val matched = catalog.plan(requestedText) ?: error("exact-text cache miss")
                matched.segments.forEach { if (released) return@execute; catalog.verify(it.entry) }
                matched
            }
            main.post {
                if (released) return@post
                prepared.onSuccess { plan = it; onReady() }
                    .onFailure { onError("기기 음성 엔진을 사용할 수 없고, 이 문장과 일치하는 저장 음성도 준비되지 않았어요.") }
            }
        }
    }
    override fun speak(text: String, listener: PlaybackListener) {
        if (released || CachedSpeechMatcher.normalize(text) != plan?.text) {
            listener.onFailed("요청 문장과 저장 음성이 일치하지 않아 재생하지 않았어요."); return
        }
        this.listener = listener
        playNext()
    }
    private fun playNext() {
        if (released || terminal) return
        val segment = plan?.segments?.getOrNull(clipIndex) ?: run { fail("저장 음성의 다음 문장을 찾지 못했어요."); return }
        clipStarted = false
        val candidate = MediaPlayer()
        player = candidate
        runCatching {
            candidate.setAudioAttributes(attributes)
            candidate.setOnPreparedListener { prepared ->
                if (!current(prepared)) return@setOnPreparedListener
                runCatching {
                    val expectedMs = (segment.entry.durationSeconds * 1000).toLong()
                    require(prepared.duration > 0 && kotlin.math.abs(prepared.duration.toLong() - expectedMs) <= maxOf(1_000L, expectedMs / 4))
                    prepared.setVolume(1f, 1f); prepared.start()
                    fun confirmOnset() {
                        if (!current(prepared)) return
                        if (prepared.isPlaying && prepared.currentPosition > 0) {
                            clipStarted = true
                            if (!outputStarted) { outputStarted = true; listener?.onStarted() }
                        } else onsetCheck = Runnable { runCatching { confirmOnset() }.onFailure { fail("저장 음성의 재생 상태를 확인하지 못했어요.") } }
                            .also { main.postDelayed(it, 25) }
                    }
                    confirmOnset()
                }.onFailure { fail("저장 음성을 시작하지 못했어요.") }
            }
            candidate.setOnCompletionListener { completed ->
                if (!current(completed)) return@setOnCompletionListener
                if (!clipStarted) { fail("저장 음성의 실제 재생 시작을 확인하지 못했어요."); return@setOnCompletionListener }
                clearTimers(); completed.release(); player = null
                clipIndex++
                if (clipIndex == plan?.segments?.size) { terminal = true; listener?.onCompleted() } else playNext()
            }
            candidate.setOnErrorListener { failed, _, _ ->
                if (current(failed)) fail("저장 음성을 읽지 못했어요. 다시 눌러 주세요.")
                true
            }
            context.assets.openFd(catalog.assetPath(segment.entry)).use { candidate.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            watchdog = Runnable { if (current(candidate)) fail("저장 음성의 재생 완료를 확인하지 못했어요.") }
                .also { main.postDelayed(it, (segment.entry.durationSeconds * 1000).toLong() + 5_000) }
            candidate.prepareAsync()
        }.onFailure { fail("저장 음성 파일을 열지 못했어요.") }
    }
    private fun current(candidate: MediaPlayer) = !released && !terminal && player === candidate
    private fun clearTimers() {
        watchdog?.let(main::removeCallbacks); watchdog = null
        onsetCheck?.let(main::removeCallbacks); onsetCheck = null
    }
    private fun fail(reason: String) {
        if (released || terminal) return
        terminal = true; clearTimers(); player?.let { runCatching { it.release() } }; player = null
        listener?.onFailed(reason)
    }
    override fun release() {
        if (released) return
        released = true; clearTimers(); listener = null
        player?.let { runCatching { it.release() } }; player = null; worker.shutdownNow()
    }
}
