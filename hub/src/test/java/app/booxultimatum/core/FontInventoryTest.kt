package app.booxultimatum.core

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEPT = "/storage/emulated/0/Android/data/app.booxultimatum/files/fonts"
private const val OFF = "$KEPT/off"
private const val READER = "/storage/emulated/0/fonts"

private fun app(name: String, size: Long = 100, modified: Long = 1_000, sha: String? = null) =
    FontFileEntry(FontPlace.App, false, name, "$KEPT/$name", size, modified, sha)

private fun appOff(name: String, size: Long = 100, modified: Long = 1_000) = FontFileEntry(FontPlace.App, true, name, "$OFF/app/$name", size, modified)

private fun reader(name: String, size: Long = 100, sha: String? = null) = FontFileEntry(FontPlace.Reader, false, name, "$READER/$name", size, 2_000, sha)

private fun readerOff(name: String, size: Long = 100) = FontFileEntry(FontPlace.Reader, true, name, "$OFF/reader/$name", size, 2_000)

private fun docs(name: String, size: Long = 100) =
    FontFileEntry(FontPlace.Documents, false, name, "/storage/emulated/0/Documents/BooxUltimatum/$name", size, 3_000, id = 7)

class FontNamesTest {
    @Test fun readsBaseAndStyleFromTheAppsFileNames() {
        assertEquals("EBGaramond", FontNames.baseOf("EBGaramond-BoldItalic.ttf"))
        assertEquals(FontStyleKey(700, true), FontNames.styleOf("EBGaramond-BoldItalic.ttf"))
        assertEquals(FontStyleKey(400, true), FontNames.styleOf("Lora-Italic.ttf"))
        assertEquals(FontStyleKey(400, false), FontNames.styleOf("Lora-Regular.ttf"))
        assertEquals(FontStyleKey(600, false), FontNames.styleOf("Inter-SemiBold.ttf"))
        assertEquals(FontStyleKey(450, false), FontNames.styleOf("Odd-W450.ttf"))
        assertNull(FontNames.styleOf("Odd-450.ttf"))
        assertNull(FontNames.baseOf("NotoSansCJK-Regular.otf"))
        assertNull(FontNames.baseOf("Foo Bar-Regular.ttf"))
        assertEquals("Inter", FontNames.baseOf("Inter-Regular.ttf"))
        assertEquals("InterTight", FontNames.baseOf("InterTight-Regular.ttf"))
    }

    @Test fun makesReadableNamesForUnrecordedFamilies() {
        assertEquals("EB Garamond", FontNames.display("EBGaramond"))
        assertEquals("Source Serif 4", FontNames.display("SourceSerif4"))
        assertEquals("IBM Plex Sans", FontNames.display("IBMPlexSans"))
        assertEquals("Lora", FontNames.display("Lora"))
    }

    @Test fun spellsSharedStoragePathsOneWay() {
        assertEquals("$READER/A-Regular.ttf", FontNames.normalise("/sdcard/fonts/A-Regular.ttf"))
        assertEquals("$READER/A-Regular.ttf", FontNames.normalise("/storage/self/primary/fonts/A-Regular.ttf"))
        assertEquals("/system/fonts/Roboto.ttf", FontNames.normalise("/system/fonts/Roboto.ttf"))
    }

    @Test fun parsesTheShellsListingAndHashes() {
        assertEquals(Triple(6L, 1_790_582_998_000L, "/sdcard/fonts/Foo Bar.otf"), FontNames.parseStat("6|1790582998|/sdcard/fonts/Foo Bar.otf"))
        assertNull(FontNames.parseStat("__BU_END__"))
        assertNull(FontNames.parseStat("x|1|/sdcard/fonts/a.ttf"))
        val h = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03"
        assertEquals(mapOf("A-Regular.ttf" to h), FontNames.parseSha256("$h  A-Regular.ttf\nsha256sum: B.ttf: No such file\n"))
    }
}

class FontInventoryTest {
    private val now = 50_000L

    @Test fun adoptsFamiliesFoundOnDiskWithoutARecord() {
        val scan = FontScan(listOf(app("EBGaramond-Regular.ttf", modified = 900), app("EBGaramond-Bold.ttf", modified = 1_200)), readerChecked = true)
        val r = FontInventory.reconcile(emptyList(), scan, now)
        assertEquals(1, r.adopted.size)
        val rec = r.records.single()
        assertEquals("EB Garamond", rec.family)
        assertEquals(900L, rec.installedAt)
        assertTrue(r.changed)
        assertEquals(listOf(FontStyleKey(400, false), FontStyleKey(700, false)), r.fonts.single().styles)
    }

    @Test fun adoptionTakesTheCataloguesNameAndHealsAGuessedOne() {
        val scan = FontScan(listOf(app("PTSerif-Regular.ttf")), readerChecked = true)
        assertEquals("PT Serif", FontInventory.reconcile(emptyList(), scan, now) { if (it == "PTSerif") "PT Serif" else null }.records.single().family)
        val guessed = FontRecord("MPLUS1p", FontNames.display("MPLUS1p"), 1)
        val healed = FontInventory.reconcile(listOf(guessed), FontScan(listOf(app("MPLUS1p-Regular.ttf")), true), now) { "M PLUS 1p" }
        assertEquals("M PLUS 1p", healed.records.single().family)
        val chosen = FontRecord("MPLUS1p", "My name", 1)
        assertEquals("My name", FontInventory.reconcile(listOf(chosen), FontScan(listOf(app("MPLUS1p-Regular.ttf")), true), now) { "M PLUS 1p" }.records.single().family)
    }

    @Test fun dropsRecordsWhoseFilesAreGone() {
        val gone = FontRecord("Lora", "Lora", 1)
        val r = FontInventory.reconcile(listOf(gone), FontScan(emptyList(), readerChecked = true), now)
        assertEquals(listOf(gone), r.dropped)
        assertTrue(r.records.isEmpty())
        assertTrue(r.fonts.isEmpty())
        assertTrue(r.changed)
    }

    @Test fun keepsRecordsWhoseCopiesMaySitInAPlaceItCouldNotRead() {
        val rec = FontRecord("Lora", "Lora", 1, reader = setOf("Lora-Regular.ttf"))
        val r = FontInventory.reconcile(listOf(rec), FontScan(emptyList(), readerChecked = false), now)
        assertTrue(r.dropped.isEmpty())
        val f = r.fonts.single()
        assertEquals(setOf("Lora-Regular.ttf"), f.readerNames)
        assertEquals(FontState.On, f.state)
        assertEquals(setOf(FontPlace.Reader), f.placesOn)
        val plain = FontRecord("Inter", "Inter", 1)
        val unread = FontInventory.reconcile(listOf(plain), FontScan(emptyList(), readerChecked = true, documentsChecked = false), now)
        assertEquals(listOf(plain), unread.records)
        assertTrue(unread.fonts.isEmpty())
    }

    @Test fun claimsBooxFolderFilesOnlyWhenRecordedOrIdentical() {
        val rec = FontRecord("Lora", "Lora", 1, reader = setOf("Lora-Regular.ttf"))
        val files = listOf(
            app("Lora-Regular.ttf"), app("Lora-Bold.ttf", size = 120, sha = "b".repeat(64)),
            reader("Lora-Regular.ttf"),
            reader("Lora-Bold.ttf", size = 120, sha = "b".repeat(64)),
            app("Inter-Regular.ttf", sha = "c".repeat(64)), reader("Inter-Regular.ttf", sha = "d".repeat(64)),
            reader("Literata-Regular.ttf", sha = "e".repeat(64)),
            reader("NotoSansCJK.otf"),
        )
        val r = FontInventory.reconcile(listOf(rec), FontScan(files, readerChecked = true), now)
        val lora = r.fonts.first { it.base == "Lora" }
        assertEquals(setOf("Lora-Regular.ttf", "Lora-Bold.ttf"), lora.readerNames)
        assertEquals(mapOf("Lora" to setOf("Lora-Bold.ttf")), r.adoptedReader)
        assertEquals(setOf("Lora-Regular.ttf", "Lora-Bold.ttf"), r.records.first { it.base == "Lora" }.reader)
        assertEquals(listOf("Inter-Regular.ttf", "Literata-Regular.ttf", "NotoSansCJK.otf"), r.foreign.map { it.name })
        assertTrue(r.fonts.first { it.base == "Inter" }.readerNames.isEmpty())
        assertTrue(r.fonts.none { it.base == "Literata" })
    }

    @Test fun hashesOnlyUnrecordedLookalikes() {
        val rec = FontRecord("Lora", "Lora", 1, reader = setOf("Lora-Regular.ttf"))
        val files = listOf(app("Lora-Regular.ttf"), app("Lora-Bold.ttf", size = 120), reader("Lora-Regular.ttf"), reader("Lora-Bold.ttf", size = 120), reader("Lora-Italic.ttf"), reader("Inter-Regular.ttf"))
        assertEquals(setOf("Lora-Bold.ttf"), FontInventory.hashCandidates(listOf(rec), files))
        assertTrue(FontInventory.hashCandidates(emptyList(), listOf(app("A-Regular.ttf", size = 1), reader("A-Regular.ttf", size = 2))).isEmpty())
    }

    @Test fun tellsOnOffAndPartlyOff() {
        fun state(vararg f: FontFileEntry) = FontInventory.reconcile(emptyList(), FontScan(f.toList(), true), now).fonts.single().state
        assertEquals(FontState.On, state(app("Lora-Regular.ttf"), docs("Lora-Medium.ttf")))
        assertEquals(FontState.Off, state(appOff("Lora-Regular.ttf"), readerOff("Lora-Regular.ttf")))
        assertEquals(FontState.Partial, state(app("Lora-Regular.ttf"), appOff("Lora-Bold.ttf")))
    }

    @Test fun offCopiesFromTheBooxFolderStayInTheRecord() {
        val rec = FontRecord("Lora", "Lora", 1, reader = setOf("Lora-Regular.ttf"), released = mapOf("Home" to "Lora-Regular.ttf"))
        val r = FontInventory.reconcile(listOf(rec), FontScan(listOf(appOff("Lora-Regular.ttf"), readerOff("Lora-Regular.ttf")), readerChecked = true), now)
        val kept = r.records.single()
        assertEquals(setOf("Lora-Regular.ttf"), kept.reader)
        assertEquals(mapOf("Home" to "Lora-Regular.ttf"), kept.released)
        assertTrue(r.fonts.single().readerNames.isEmpty())
        val back = FontInventory.reconcile(listOf(kept), FontScan(listOf(app("Lora-Regular.ttf"), reader("Lora-Regular.ttf")), true), now)
        assertTrue(back.records.single().released.isEmpty())
    }

    @Test fun findsEveryUse() {
        val files = listOf(app("Lora-Regular.ttf"), app("Lora-Medium.ttf"), reader("Lora-Medium.ttf"))
        val rec = FontRecord("Lora", "Lora", 1, reader = setOf("Lora-Medium.ttf"))
        val font = FontInventory.reconcile(listOf(rec), FontScan(files, true), now).fonts.single()
        assertEquals(setOf(FontUse.Reader), FontInventory.uses(font, FontSettings()))
        val all = FontSettings(
            tabletPath = "/sdcard/fonts/Lora-Medium.ttf",
            appPath = "$KEPT/Lora-Regular.ttf",
            homeFollowsApp = true,
            sleepPath = "$KEPT/Lora-Regular.ttf",
        )
        assertEquals(FontUse.entries.toSet(), FontInventory.uses(font, all))
        val custom = FontSettings(homePath = "$KEPT/Lora-Regular.ttf")
        assertEquals(setOf(FontUse.Reader, FontUse.Home), FontInventory.uses(font, custom))
        val other = FontSettings(tabletPath = "/system/fonts/Manrope.ttf", appPath = "$KEPT/Inter-Regular.ttf", homeFollowsApp = true)
        assertEquals(setOf(FontUse.Reader), FontInventory.uses(font, other))
    }

    @Test fun seesTheTabletFontInTheBooxFolderEvenWhenItCannotLookThere() {
        val rec = FontRecord("Lora", "Lora", 1, reader = setOf("Lora-Medium.ttf"))
        val font = FontInventory.reconcile(listOf(rec), FontScan(listOf(app("Lora-Medium.ttf")), readerChecked = false), now).fonts.single()
        assertTrue(FontUse.Tablet in FontInventory.uses(font, FontSettings(tabletPath = "/storage/emulated/0/fonts/Lora-Medium.ttf")))
        val docsFont = FontInventory.reconcile(emptyList(), FontScan(listOf(docs("Inter-Medium.ttf")), true), now).fonts.single()
        assertEquals(setOf(FontUse.Tablet), FontInventory.uses(docsFont, FontSettings(tabletPath = "/storage/emulated/0/Documents/BooxUltimatum/Inter-Medium.ttf")))
    }

    @Test fun offFilesAreNeverInUse() {
        val font = FontInventory.reconcile(emptyList(), FontScan(listOf(appOff("Lora-Regular.ttf")), true), now).fonts.single()
        assertTrue(FontInventory.uses(font, FontSettings(appPath = "$OFF/app/Lora-Regular.ttf")).isEmpty())
    }

    @Test fun unusedMeansOnAndUsedNowhere() {
        val files = listOf(
            app("Lora-Regular.ttf"),
            app("Inter-Regular.ttf"), reader("Inter-Regular.ttf"),
            app("Bitter-Regular.ttf"),
            appOff("Literata-Regular.ttf"),
        )
        val rec = FontRecord("Inter", "Inter", 1, reader = setOf("Inter-Regular.ttf"))
        val fonts = FontInventory.reconcile(listOf(rec), FontScan(files, true), now).fonts
        val unused = FontInventory.unused(fonts, FontSettings(sleepPath = "$KEPT/Bitter-Regular.ttf"))
        assertEquals(listOf("Lora"), unused.map { it.base })
    }

    @Test fun sortsAndFilters() {
        val files = listOf(
            app("Lora-Regular.ttf", size = 300, modified = 3),
            app("Bitter-Regular.ttf", size = 100, modified = 9),
            appOff("Alegreya-Regular.ttf", size = 200, modified = 5),
        )
        val fonts = FontInventory.reconcile(emptyList(), FontScan(files, true), now).fonts
        val uses = mapOf("Lora" to setOf(FontUse.App))
        fun names(s: InstalledSort, f: InstalledFilter) = FontInventory.arrange(fonts, uses, s, f).map { it.base }
        assertEquals(listOf("Alegreya", "Bitter", "Lora"), names(InstalledSort.Name, InstalledFilter.All))
        assertEquals(listOf("Lora", "Alegreya", "Bitter"), names(InstalledSort.Size, InstalledFilter.All))
        assertEquals(listOf("Bitter", "Alegreya", "Lora"), names(InstalledSort.Recent, InstalledFilter.All))
        assertEquals(listOf("Lora"), names(InstalledSort.Name, InstalledFilter.InUse))
        assertEquals(listOf("Bitter"), names(InstalledSort.Name, InstalledFilter.Unused))
        assertEquals(listOf("Alegreya"), names(InstalledSort.Name, InstalledFilter.Off))
    }

    @Test fun picksTheRegularUprightForTheHomeScreenAndApp() {
        val font = FontInventory.reconcile(emptyList(), FontScan(listOf(app("Lora-Italic.ttf"), app("Lora-Bold.ttf"), app("Lora-Medium.ttf")), true), now).fonts.single()
        assertEquals("Lora-Medium.ttf", font.regular?.name)
        assertEquals(listOf(500, 700), font.uprightWeights)
        assertEquals("Lora-Bold.ttf", font.appFile(700)?.name)
        assertNull(font.appFile(400))
    }

    @Test fun recordsSurviveJson() {
        val records = listOf(
            FontRecord("Lora", "Lora", 12, setOf("Lora-Regular.ttf", "Lora-Bold.ttf"), mapOf("Tablet" to "Lora-Medium.ttf", FontRecord.TABLET_AFTER to "")),
            FontRecord("EBGaramond", "EB Garamond", 34),
        )
        assertEquals(records, FontRecords.fromJson(FontRecords.toJson(records)))
        assertTrue(FontRecords.fromJson("not json").isEmpty())
        assertTrue(FontRecords.fromJson(null).isEmpty())
    }
}

class FontFoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun folders(): FontFolders = tmp.newFolder("kept").let { FontFolders(it, File(it, "off")) }

    private fun FontFolders.write(name: String, text: String = "font $name") = File(app, name).apply { writeText(text) }

    private fun FontFolders.fonts() = app.listFiles { x -> x.isFile }!!.map { it.name }.sorted()

    @Test fun offFilesSitInsideTheFontsFolderWherePickersDoNotLook() {
        val f = folders()
        f.write("Lora-Regular.ttf"); f.write("Bitter-Regular.ttf")
        f.turnOff("Lora")
        assertTrue(File(f.app, "off/app/Lora-Regular.ttf").isFile)
        // What Appearance, the sleep screen and UiFonts list: the top level of the fonts folder only.
        assertEquals(listOf("Bitter-Regular.ttf"), f.app.listFiles { x -> x.name.endsWith(".ttf") }!!.map { it.name })
        assertEquals(listOf("Bitter-Regular.ttf"), f.scan().filter { !it.off }.map { it.name })
        assertEquals(listOf("Lora-Regular.ttf"), f.scan().filter { it.off }.map { it.name })
        assertEquals(2, f.app.walkTopDown().count { it.isFile })
    }

    @Test fun sweepsHalfCopiedFiles() {
        val f = folders()
        f.write("Lora-Regular.ttf")
        f.write("Lora-Bold.ttf.part")
        f.offDir(FontPlace.Reader).mkdirs()
        File(f.offDir(FontPlace.Reader), "Lora-Regular.ttf.part").writeText("x")
        assertEquals(2, f.sweep())
        assertEquals(listOf("Lora-Regular.ttf"), f.app.list()!!.filter { it.endsWith(".ttf") || it.endsWith(".part") }.sorted())
        assertEquals(0, f.sweep())
    }

    @Test fun turningOffMovesOnlyThatFamilyAndTurningOnPutsItBack() {
        val f = folders()
        f.write("Inter-Regular.ttf"); f.write("Inter-Bold.ttf"); f.write("InterTight-Regular.ttf")
        val moved = f.turnOff("Inter")
        assertEquals(listOf("Inter-Bold.ttf", "Inter-Regular.ttf"), moved.map { it.name })
        assertEquals(listOf("InterTight-Regular.ttf"), f.fonts())
        assertTrue(f.hasOff("Inter"))
        assertFalse(f.hasOff("InterTight"))
        assertEquals("font Inter-Bold.ttf", File(f.offDir(FontPlace.App), "Inter-Bold.ttf").readText())
        val scan = f.scan()
        assertEquals(2, scan.count { it.off && it.place == FontPlace.App })
        assertEquals(1, scan.count { !it.off })

        f.turnOn("Inter")
        assertEquals(listOf("Inter-Bold.ttf", "Inter-Regular.ttf", "InterTight-Regular.ttf"), f.fonts())
        assertFalse(f.hasOff("Inter"))
        assertEquals("font Inter-Regular.ttf", File(f.app, "Inter-Regular.ttf").readText())
    }

    @Test fun turningOnKeepsACopyAlreadyBack() {
        val f = folders()
        f.write("Lora-Regular.ttf", "old")
        f.turnOff("Lora")
        f.write("Lora-Regular.ttf", "new")
        f.turnOn("Lora")
        assertEquals("new", File(f.app, "Lora-Regular.ttf").readText())
        assertFalse(f.hasOff("Lora"))
    }

    @Test fun stashesSharedCopiesAndDeletesEverything() {
        val f = folders()
        f.write("Lora-Regular.ttf", "12345")
        f.stash(FontPlace.Reader, "Lora-Regular.ttf") { it.write("abc".toByteArray()) }
        f.stash(FontPlace.Documents, "Lora-Medium.ttf") { it.write("defg".toByteArray()) }
        f.write("Bitter-Regular.ttf")
        val scan = f.scan()
        assertEquals(setOf(FontPlace.Reader, FontPlace.Documents), scan.filter { it.off }.map { it.place }.toSet())
        assertTrue(f.offDir(FontPlace.Reader).list()!!.none { it.endsWith(".part") })
        assertEquals(12L, f.delete("Lora"))
        assertFalse(f.hasOff("Lora"))
        assertEquals(listOf("Bitter-Regular.ttf"), f.fonts())
    }

    @Test fun movesAcrossFoldersByCopyingWhenItMust() {
        val src = File(tmp.newFolder("a"), "Lora-Regular.ttf").apply { writeText("abc") }
        val dst = FontFolders.move(src, File(tmp.root, "b"))
        assertFalse(src.exists())
        assertEquals("abc", dst.readText())
    }
}
