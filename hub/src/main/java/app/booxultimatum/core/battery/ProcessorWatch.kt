package app.booxultimatum.core.battery

import app.booxultimatum.core.HeldWakelock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The rules for telling that the tablet's processor stayed awake while it slept, as pure functions, so they are tested
 * without a device (docs/11-not-resting.md). After Android restarts by itself the processor never goes to sleep until the
 * tablet is restarted: the sleeps of the log had it awake 0.2 to 28.6 % of the time when clean and 100 % in that state,
 * and a display stuck ON follows 90 minutes after the sleep screen last refreshed.
 */
object ProcessorPolicy {
    /** The shortest stretch of sleep a verdict is given for. */
    const val MIN_STRETCH_MS = 25 * 60_000L

    /** Awake this share of the stretch, or more. A clean sleep never went above 28.6 %, a sleep in the bad state was at 100 %. */
    const val AWAKE_MIN_SHARE = 0.90

    /** A wake lock must have been held this long to be blamed: one that was just taken says nothing about the stretch. */
    const val BLAME_MIN_AGE_MS = 20 * 60_000L

    /** Android's first application uid: below it are the system's own. */
    const val FIRST_APP_UID = 10_000

    /** The note about the system comes back at most this often while the tablet stays unrestarted. */
    const val REPOST_EVERY_MS = 12 * 3_600_000L

    enum class Verdict {
        /** Plugged in at some point in the stretch, so the processor may have been kept awake by the charger. */
        NotAsleep,

        /** Too short to judge. */
        TooShort,

        /** The processor slept as it should. */
        Rested,

        /** The processor stayed awake. */
        Awake,
    }

    /** [elapsedMs] and [uptimeMs] are the time that passed and the time the processor was awake in it. */
    fun judge(elapsedMs: Long, uptimeMs: Long, pluggedDuring: Boolean): Verdict = when {
        pluggedDuring -> Verdict.NotAsleep
        elapsedMs < MIN_STRETCH_MS -> Verdict.TooShort
        uptimeMs.toDouble() / elapsedMs >= AWAKE_MIN_SHARE -> Verdict.Awake
        else -> Verdict.Rested
    }

    /** The share of the stretch the processor was awake, as a whole percent. */
    fun awakePercent(elapsedMs: Long, uptimeMs: Long): Int =
        if (elapsedMs <= 0) 0 else (uptimeMs * 100.0 / elapsedMs).toInt().coerceIn(0, 100)

    /**
     * The partial wake lock of an app (not the system, not the hub) that has been held long enough to blame, else null.
     * With none, the cause is the system's own state, and the advice is to restart the tablet.
     */
    fun blamedApp(held: List<HeldWakelock>, hubUid: Int): HeldWakelock? = held.firstOrNull {
        val uid = it.owner.toIntOrNull() ?: return@firstOrNull false
        it.type == "PARTIAL_WAKE_LOCK" && uid >= FIRST_APP_UID && uid != hubUid && (it.ageMs ?: 0L) >= BLAME_MIN_AGE_MS
    }

    /** Whether to post the note about the system now: once per boot, then again after [REPOST_EVERY_MS] if it is still pending. */
    fun shouldPostSystemNote(lastPostedAtMs: Long, lastPostedBoot: Int, boot: Int, nowMs: Long): Boolean =
        boot != lastPostedBoot || nowMs - lastPostedAtMs >= REPOST_EVERY_MS
}

/** The power manager's own flags and suspend blockers, from `dumpsys power`. No app names. */
data class PowerFacts(
    /** `name=value` lines such as `mHoldingDisplaySuspendBlocker=false`. */
    val flags: List<String> = emptyList(),
    /** Suspend blocker name to its reference count: WakeLocks, Display, Broadcasts, Booting, WirelessChargerDetector. */
    val blockers: Map<String, Int> = emptyMap(),
)

/** Parsers over `dumpsys power` and `dumpsys display`, checked against the Note Air6 C on firmware 4.3 (2026-10-13). */
object PowerFactsParser {
    private val FLAG = Regex(
        """^\s*(mWakefulness|mHoldingDisplaySuspendBlocker|mHoldingWakeLockSuspendBlocker|mDozeScreenStateOverrideFromDreamManager|""" +
            """mDreamsActivelyDozing|mDozeStartInProgress|mWakeLockSummary)=(\S+)""",
    )
    private val BLOCKER = Regex("""^\s*PowerManagerService\.(\w+): ref count=(\d+)""")
    private val DISPLAY_STATE = Regex("""^ {4}(mState|mCommittedState)=(\w+)""")
    private val DISPLAY_REQUEST = Regex("""mPendingRequestLocked=policy=(\w+).*?dozeScreenState=(\w+)""")
    private val AGE_PART = Regex("""(\d+)(ms|d|h|m|s)""")

    fun power(text: String): PowerFacts {
        val lines = text.lines()
        val flags = lines.mapNotNull { l -> FLAG.find(l)?.let { "${it.groupValues[1]}=${it.groupValues[2]}" } }.distinct()
        val blockers = LinkedHashMap<String, Int>()
        val start = lines.indexOfFirst { it.trim().startsWith("Suspend Blockers:") }
        if (start >= 0) {
            for (line in lines.drop(start + 1)) {
                val m = BLOCKER.find(line) ?: break
                blockers[m.groupValues[1]] = m.groupValues[2].toInt()
            }
        }
        return PowerFacts(flags, blockers)
    }

    /**
     * The display's state and the power request it was given. In sleep the request's `dozeScreenState` reads DOZE or
     * DOZE_SUSPEND, and only the second lets the processor suspend *[verify]*.
     */
    fun display(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.lines()) {
            DISPLAY_STATE.find(line)?.let { out += "${it.groupValues[1]}=${it.groupValues[2]}" }
            DISPLAY_REQUEST.find(line)?.let { out += "policy=${it.groupValues[1]}"; out += "dozeScreenState=${it.groupValues[2]}" }
        }
        return out.take(8)
    }

    /** `ACQ=-1m10s89ms` as milliseconds (the sign is how long ago); null when the text has no such part. */
    fun ageMs(wakeLockLine: String): Long? {
        val token = Regex("""ACQ=-?([0-9a-z]+)""").find(wakeLockLine)?.groupValues?.get(1) ?: return null
        var total = 0L
        var any = false
        for (part in AGE_PART.findAll(token)) {
            any = true
            val n = part.groupValues[1].toLong()
            total += when (part.groupValues[2]) {
                "d" -> n * 86_400_000L
                "h" -> n * 3_600_000L
                "m" -> n * 60_000L
                "s" -> n * 1_000L
                else -> n
            }
        }
        return if (any) total else null
    }
}

/** The one diagnostic record the watch writes to the deep log, as JSON. Pure: the caller gives it everything it needs. */
internal object ProcessorSnapshot {
    /**
     * [reason] is `processor_awake` for the first stretch of a boot in which the processor stayed awake, and
     * `processor_ref` for the first stretch of a boot in which it slept (the reference to compare it with). The
     * wake locks of apps go under `heldApps`, which a shared report leaves out unless apps are included.
     */
    fun json(
        epoch: Long,
        reason: String,
        boot: Int,
        awakePercent: Int?,
        minutes: Long?,
        cause: String?,
        facts: PowerFacts,
        display: List<String>,
        idle: String?,
        held: List<HeldWakelock>,
        packageOf: (Int) -> String?,
    ): JSONObject = JSONObject().apply {
        put("epoch", epoch)
        put("reason", reason)
        put("boot", boot)
        awakePercent?.let { put("awakePct", it) }
        minutes?.let { put("minutes", it) }
        cause?.let { put("cause", it) }
        put("power", JSONArray(facts.flags))
        put("blockers", JSONObject().apply { facts.blockers.forEach { (k, v) -> put(k, v) } })
        put("display", JSONArray(display))
        idle?.let { put("idle", it) }
        put(
            "heldApps",
            JSONArray().apply {
                held.filter { (it.owner.toIntOrNull() ?: 0) >= ProcessorPolicy.FIRST_APP_UID }.forEach {
                    put(
                        JSONObject().put("type", it.type).put("tag", it.tag).put("uid", it.owner)
                            .put("pkg", it.owner.toIntOrNull()?.let(packageOf) ?: "").put("ageMin", (it.ageMs ?: 0L) / 60_000L),
                    )
                }
            },
        )
    }
}
