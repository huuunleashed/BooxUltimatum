package app.booxultimatum.core.battery

import app.booxultimatum.core.HeldWakelock
import app.booxultimatum.kit.log.Level
import app.booxultimatum.kit.log.LogEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIN = 60_000L

class ProcessorPolicyTest {
    @Test fun aStretchWithTheProcessorAwakeForAllOfItIsAwake() {
        assertEquals(ProcessorPolicy.Verdict.Awake, ProcessorPolicy.judge(30 * MIN, 30 * MIN, pluggedDuring = false))
    }

    @Test fun theCleanSleepsOfTheLogAreRested() {
        // The most the processor was awake in a clean stretch of 20 minutes or more was 28.6 %, the median 2.1 %.
        assertEquals(ProcessorPolicy.Verdict.Rested, ProcessorPolicy.judge(30 * MIN, (30 * MIN * 0.286).toLong(), false))
        assertEquals(ProcessorPolicy.Verdict.Rested, ProcessorPolicy.judge(30 * MIN, (30 * MIN * 0.021).toLong(), false))
    }

    @Test fun ninetyPercentIsTheLine() {
        assertEquals(ProcessorPolicy.Verdict.Rested, ProcessorPolicy.judge(30 * MIN, (30 * MIN * 0.89).toLong(), false))
        assertEquals(ProcessorPolicy.Verdict.Awake, ProcessorPolicy.judge(30 * MIN, (30 * MIN * 0.90).toLong(), false))
    }

    @Test fun aStretchUnderTwentyFiveMinutesIsNotJudged() {
        assertEquals(ProcessorPolicy.Verdict.TooShort, ProcessorPolicy.judge(24 * MIN, 24 * MIN, false))
        assertEquals(ProcessorPolicy.Verdict.TooShort, ProcessorPolicy.judge(0, 0, false))
        assertEquals(ProcessorPolicy.Verdict.Awake, ProcessorPolicy.judge(25 * MIN, 25 * MIN, false))
    }

    @Test fun aStretchThatSpansTheChargerIsNotJudged() {
        assertEquals(ProcessorPolicy.Verdict.NotAsleep, ProcessorPolicy.judge(30 * MIN, 30 * MIN, pluggedDuring = true))
    }

    @Test fun theAwakeShareIsAWholePercent() {
        assertEquals(100, ProcessorPolicy.awakePercent(30 * MIN, 30 * MIN))
        assertEquals(2, ProcessorPolicy.awakePercent(30 * MIN, (30 * MIN * 0.021).toLong()))
        assertEquals(0, ProcessorPolicy.awakePercent(0, 5))
    }

    private fun lock(owner: String, ageMin: Long?, type: String = "PARTIAL_WAKE_LOCK") =
        HeldWakelock(type, "tag", owner, ageMin?.let { it * MIN })

    @Test fun onlyAnAppsLongHeldPartialWakeLockIsBlamed() {
        val hub = 10_162
        assertNull(ProcessorPolicy.blamedApp(listOf(lock("1000", 90)), hub), "the system's own")
        assertNull(ProcessorPolicy.blamedApp(listOf(lock("10162", 90)), hub), "the hub's own")
        assertNull(ProcessorPolicy.blamedApp(listOf(lock("10200", 2)), hub), "taken a moment ago")
        assertNull(ProcessorPolicy.blamedApp(listOf(lock("10200", null)), hub), "age unknown")
        assertNull(ProcessorPolicy.blamedApp(listOf(lock("10200", 90, type = "SCREEN_DIM_WAKE_LOCK")), hub), "not a partial one")
        assertEquals("10200", ProcessorPolicy.blamedApp(listOf(lock("1000", 90), lock("10200", 25)), hub)?.owner)
    }

    @Test fun theSystemNoteIsPostedOncePerBootThenEveryTwelveHours() {
        val hour = 3_600_000L
        assertTrue(ProcessorPolicy.shouldPostSystemNote(0, Int.MIN_VALUE, boot = 8, nowMs = 1_000 * hour))
        assertFalse(ProcessorPolicy.shouldPostSystemNote(1_000 * hour, 8, boot = 8, nowMs = 1_011 * hour))
        assertTrue(ProcessorPolicy.shouldPostSystemNote(1_000 * hour, 8, boot = 8, nowMs = 1_012 * hour))
        assertTrue(ProcessorPolicy.shouldPostSystemNote(1_000 * hour, 8, boot = 9, nowMs = 1_001 * hour))
    }
}

class PowerFactsParserTest {
    // Lines as the Note Air6 C (firmware 4.3) prints them, 2026-10-13.
    private val power = """
        mWakefulness=Awake
        mWakefulnessChanging=false
        mHoldingWakeLockSuspendBlocker=true
        mHoldingDisplaySuspendBlocker=false
        mWakeLockSummary=0x1
        mDozeScreenStateOverrideFromDreamManager=0
        mDreamsActivelyDozing=false
        Suspend Blockers: size=5
          PowerManagerService.Booting: ref count=0 []
          PowerManagerService.WakeLocks: ref count=1 [unknown: (10-13 09:38:35.633)]
          PowerManagerService.Display: ref count=0 []
          PowerManagerService.Broadcasts: ref count=0 []
          PowerManagerService.WirelessChargerDetector: ref count=0 []

        Display Power: com.android.server.power.PowerManagerService${'$'}1@6dc9ef9
    """.trimIndent()

    @Test fun theFlagsAndTheSuspendBlockersAreRead() {
        val facts = PowerFactsParser.power(power)
        assertEquals(
            listOf(
                "mWakefulness=Awake", "mHoldingWakeLockSuspendBlocker=true", "mHoldingDisplaySuspendBlocker=false",
                "mWakeLockSummary=0x1", "mDozeScreenStateOverrideFromDreamManager=0", "mDreamsActivelyDozing=false",
            ),
            facts.flags,
        )
        assertEquals(mapOf("Booting" to 0, "WakeLocks" to 1, "Display" to 0, "Broadcasts" to 0, "WirelessChargerDetector" to 0), facts.blockers)
    }

    @Test fun aDumpWithoutThemGivesNothing() {
        assertEquals(PowerFacts(), PowerFactsParser.power("nothing here"))
    }

    @Test fun theDisplayStateAndItsDozeRequestAreRead() {
        // The state lines sit four spaces in; the same name two spaces in belongs to another part of the dump.
        val display = listOf(
            "  Display State=ON",
            "    mState=ON",
            "    mCommittedState=ON",
            "  mPendingRequestLocked=policy=DIM, useProximitySensor=false, screenBrightnessOverride=NaN, dozeScreenBrightness=NaN, dozeScreenState=UNKNOWN, dozeScreenStateReason=DEFAULT_POLICY",
            "  mState=AUTO_BRIGHTNESS_DISABLED",
        ).joinToString("\n")
        assertEquals(listOf("mState=ON", "mCommittedState=ON", "policy=DIM", "dozeScreenState=UNKNOWN"), PowerFactsParser.display(display))
    }

    @Test fun aWakeLocksAgeIsReadFromItsAcqPart() {
        assertEquals(70_089L, PowerFactsParser.ageMs("  PARTIAL_WAKE_LOCK 'x' ACQ=-1m10s89ms (uid=1000 pid=1445)"))
        assertEquals(23_000L, PowerFactsParser.ageMs("  PARTIAL_WAKE_LOCK 'x' ACQ=-23s (uid=10200 pid=9)"))
        assertEquals(3_723_000L, PowerFactsParser.ageMs("  PARTIAL_WAKE_LOCK 'x' ACQ=-1h2m3s (uid=10200 pid=9)"))
        assertEquals(5L, PowerFactsParser.ageMs("  PARTIAL_WAKE_LOCK 'x' ACQ=-5ms (uid=1000 pid=9)"))
        assertNull(PowerFactsParser.ageMs("  PARTIAL_WAKE_LOCK 'x' (uid=1000 pid=9)"))
    }

    @Test fun theSnapshotKeepsAppsApartFromThePowerState() {
        val json = ProcessorSnapshot.json(
            epoch = 1L, reason = "processor_awake", boot = 8, awakePercent = 100, minutes = 30, cause = "system",
            facts = PowerFactsParser.power(power), display = listOf("dozeScreenState=DOZE"), idle = "ACTIVE/ACTIVE",
            held = listOf(HeldWakelock("PARTIAL_WAKE_LOCK", "sync", "10200", 25 * MIN), HeldWakelock("PARTIAL_WAKE_LOCK", "*alarm*", "1000", 1_000)),
            packageOf = { "com.example.app" },
        )
        assertEquals(100, json.getInt("awakePct"))
        assertEquals(1, json.getJSONObject("blockers").getInt("WakeLocks"))
        assertEquals("dozeScreenState=DOZE", json.getJSONArray("display").getString(0))
        val apps = json.getJSONArray("heldApps")
        assertEquals(1, apps.length())
        assertEquals("com.example.app", apps.getJSONObject(0).getString("pkg"))
        assertEquals(25L, apps.getJSONObject(0).getLong("ageMin"))
        assertFalse(json.getJSONArray("power").toString().contains("com.example"))
    }
}

class RestartContextTest {
    private val crash = listOf(
        "Process: system_server", "PID: 1388", "Flags: 0x3e8dcc5", "Package: android v36 (16)", "Build: Onyx/NoteAir6C/test", "",
        "java.lang.IndexOutOfBoundsException: Index: 11, Size: 11",
        "\tat java.util.ArrayList.get(ArrayList.java:437)",
        "\tat com.android.server.wm.RecentTasks.getPersistableTaskIds(RecentTasks.java:2036)",
        "\tat com.android.server.wm.TaskPersister${'$'}LazyTaskWriterThread.run(TaskPersister.java:508)",
    ).joinToString("\n")

    @Test fun theCrashHeadKeepsTheProcessThePackageAndTheStack() {
        val head = RestartContext.crashHead(crash)
        assertEquals("Process: system_server", head[0])
        assertEquals("Package: android v36 (16)", head[1])
        assertEquals("java.lang.IndexOutOfBoundsException: Index: 11, Size: 11", head[2])
        assertTrue(head.any { it.contains("RecentTasks.getPersistableTaskIds") })
        assertFalse(head.any { it.startsWith("Build:") || it.startsWith("PID:") })
    }

    @Test fun aTextWithNoExceptionKeepsOnlyTheHeader() {
        assertEquals(listOf("Process: system_server"), RestartContext.crashHead("Process: system_server\nPID: 1"))
        assertEquals(emptyList(), RestartContext.crashHead(""))
    }

    private fun event(wallMs: Long, category: String, message: String, vararg fields: Pair<String, String>) =
        LogEvent(wallMs, 0, Level.Info, category, message, fields.toMap(), "main", 1, "s", null)

    @Test fun theHubsActionsAreTheOnesInTheFiveMinutesBeforeTheRestartOldestFirst() {
        val restart = 1_000_000_000L
        val actions = RestartContext.hubActions(
            events = listOf(
                event(restart - 6 * MIN, "exec", "too early"),
                event(restart - 90_000, "sleep.live", "tick", "skew_s" to "0"),
                event(restart - 12_000, "exec", "pm disable-user"),
                event(restart + 20_000, "ui", "after, still counted"),
                event(restart + 40_000, "ui", "too late"),
            ),
            journal = listOf((restart - 30_000) to "apply Stop the status bar"),
            restartAtMs = restart,
        )
        assertEquals(
            listOf("-90s sleep.live tick skew_s=0", "-30s journal apply Stop the status bar", "-12s exec pm disable-user", "20s ui after, still counted"),
            actions,
        )
    }

    @Test fun equalLinesFromTwoSourcesAreKeptOnce() {
        val restart = 1_000_000_000L
        val e = event(restart - 12_000, "exec", "same")
        assertEquals(1, RestartContext.hubActions(listOf(e, e), emptyList(), restart).size)
    }
}
