package app.booxultimatum.kit.core

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice

/**
 * What this tablet is, read once per process: one answer for every feature that behaves differently on Boox
 * firmware, instead of each screen guessing on its own.
 *
 * - Boox is recognised from the build fields (brand, manufacturer, model and fingerprint say "onyx" or "boox") or from
 *   Boox's own system app `com.onyx` being installed. A Boox-only feature still checks its own interface before use,
 *   since a firmware can drop one without the others.
 * - The pen is the input device Android lists as a stylus. Its `/dev/input` node is found by watching for a pen tool,
 *   because apps can't read `/sys/class/input`; nothing falls back to a guessed node.
 */
data class TabletProfile(
    val isBoox: Boolean,
    val manufacturer: String,
    val model: String,
    val series: BooxSeries,
    /** Boox firmware line such as "4.3", read from the build display id; null when it doesn't follow Boox's pattern. */
    val firmware: String?,
    val android: String,
    val platform: String,
    val pen: PenNode?,
    /** Every input device Android lists, for reports from tablets we haven't seen. */
    val inputs: List<InputNode>,
) {
    /** The tablet this project is developed and verified on. */
    val isReference: Boolean get() = isBoox && model.equals("NoteAir6C", ignoreCase = true)
    val hasPen: Boolean get() = pen != null
    val name: String get() = if (isBoox) "BOOX $model" else "$manufacturer $model"
}

enum class BooxSeries { NoteAir, Note, NoteMax, TabUltra, TabMini, TabX, Tab, Go, Palma, Page, Leaf, Poke, Nova, OtherBoox, NotBoox }

data class InputNode(val name: String, val stylus: Boolean)

/** The stylus: its name from Android, and its `/dev/input` node once it has been seen reporting a pen tool. */
data class PenNode(val name: String, val path: String?)

object Tablet {
    @Volatile private var cached: TabletProfile? = null

    fun current(context: Context): TabletProfile = cached ?: read(context.applicationContext).also { cached = it }

    private fun read(context: Context): TabletProfile {
        val boox = BOOX_WORDS.any { w ->
            listOf(Build.BRAND, Build.MANUFACTURER, Build.MODEL, Build.PRODUCT, Build.DEVICE, Build.FINGERPRINT).any { it.orEmpty().contains(w, ignoreCase = true) }
        } || hasPackage(context, "com.onyx")
        val inputs = inputDevices(context)
        val pen = inputs.firstOrNull { it.stylus }?.let { PenNode(it.name, PenNodes.remembered(context)) }
        return TabletProfile(
            isBoox = boox,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            series = if (boox) series(Build.MODEL.orEmpty()) else BooxSeries.NotBoox,
            firmware = FIRMWARE.find(Build.DISPLAY.orEmpty())?.groupValues?.get(1),
            android = Build.VERSION.RELEASE.orEmpty(),
            platform = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN } ?: Build.BOARD else Build.BOARD,
            pen = pen,
            inputs = inputs,
        )
    }

    private fun hasPackage(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    internal fun series(model: String): BooxSeries {
        val m = model.lowercase().replace(" ", "").replace("_", "").removePrefix("onyx").removePrefix("boox")
        return when {
            m.startsWith("noteair") -> BooxSeries.NoteAir
            m.startsWith("notemax") -> BooxSeries.NoteMax
            m.startsWith("note") -> BooxSeries.Note
            m.startsWith("tabultra") -> BooxSeries.TabUltra
            m.startsWith("tabmini") -> BooxSeries.TabMini
            m.startsWith("tabx") -> BooxSeries.TabX
            m.startsWith("tab") -> BooxSeries.Tab
            m.startsWith("go") -> BooxSeries.Go
            m.startsWith("palma") -> BooxSeries.Palma
            m.startsWith("page") -> BooxSeries.Page
            m.startsWith("leaf") -> BooxSeries.Leaf
            m.startsWith("poke") -> BooxSeries.Poke
            m.startsWith("nova") -> BooxSeries.Nova
            else -> BooxSeries.OtherBoox
        }
    }

    /**
     * Input devices as Android lists them: name, and whether each reports as a stylus. Android can't say which
     * `/dev/input` node a device is, and SELinux closes `/sys/class/input` to apps, so the pen's node is only known
     * once it has reported a pen tool ([PenNodes] remembers it).
     */
    fun inputDevices(context: Context): List<InputNode> {
        val im = context.getSystemService(InputManager::class.java) ?: return emptyList()
        return im.inputDeviceIds.toList().mapNotNull { id: Int -> im.getInputDevice(id) }
            .filter { !it.isVirtual }
            .map { d: InputDevice -> InputNode(d.name.orEmpty(), d.supportsSource(InputDevice.SOURCE_STYLUS)) }
            .distinct()
    }

    private val BOOX_WORDS = listOf("onyx", "boox")
    private val FIRMWARE = Regex("""_(\d+\.\d+(?:\.\d+)?)-rel""")
}
