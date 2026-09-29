package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.RecordingSink
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.TiltResponse
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.index.HitTest
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.PressureCurve
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.render.StrokeRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Tilt through the file format, and drawings saved by earlier versions. */
class FormatCompatTest {
    private fun read(bytes: ByteArray): NibFileContents = NibFile.read(ByteArrayInputStream(bytes))

    private fun write(doc: Document): ByteArray = ByteArrayOutputStream().also { NibFile.write(doc, it) }.toByteArray()

    /** A sink call with its arrays as lists, so equal calls compare equal. */
    private fun comparable(c: RecordingSink.Call): Any = when (c) {
        is RecordingSink.Call.Path -> listOf("path", c.xy.toList(), c.color, c.blend)
        is RecordingSink.Call.Polyline -> listOf("polyline", c.xy.toList(), c.width, c.color, c.cap, c.dash?.toList(), c.blend)
        else -> c
    }

    private fun manifestVersion(bytes: ByteArray): Pair<Int, Int> {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "manifest.bin") {
                    val r = ByteReader(z.readBytes(), 4)
                    return r.count() to r.count()
                }
            }
        }
        error("no manifest")
    }

    @Test
    fun aDrawingFromFormat10OpensWithItsTiltAndLooksAsItDid() {
        val bytes = Base64.getDecoder().decode(GOLDEN_1_0)
        assertEquals(1 to 0, manifestVersion(bytes))
        val contents = read(bytes)
        assertEquals(7L, contents.journalToken)
        val doc = contents.document
        assertEquals("golden-1.0", doc.id)
        val strokes = doc.layers.single().strokes
        val kinds = listOf(BrushKind.Fineliner, BrushKind.Fountain, BrushKind.Pencil, BrushKind.Charcoal, BrushKind.CharcoalV2, BrushKind.Marker, BrushKind.Calligraphy)
        assertEquals(kinds, strokes.map { it.brush.kind })
        for ((k, s) in strokes.withIndex()) {
            assertTrue(!s.brush.usesTilt, "${s.brush.kind}: brushes from before 1.1 ignore tilt")
            assertEquals(12, s.points.size)
            for (i in 0 until 12) {
                val tilt = if (k == 0) 0f else 0.1f * (i % 8)
                val orientation = if (k == 0) 0f else -1.5f + 0.25f * i
                assertEquals(tilt, s.points.tilt(i), 0.004f, "tilt was saved")
                assertEquals(orientation, s.points.orientation(i), 0.013f, "orientation was saved")
                assertEquals(0.3f + 0.05f * i, s.points.pressure(i), 1e-4f)
                assertEquals(if (i == 0) 0 else 4, s.points.deltaMillis(i))
            }
        }
        // The brushes keep the settings they were drawn with, not today's defaults.
        assertEquals(PressureCurve(0.75f, 0.25f, 1f), strokes[1].brush.curve, "the fountain pen as 0.2 drew it")
        assertEquals(0.5f, strokes[5].brush.opacity, "the marker as 0.2 drew it")
        // Charcoal drawn tilted renders exactly as it did: its tilt is kept but not used.
        for (s in strokes) {
            val untilted = PackedPoints.Builder().apply {
                for (i in 0 until s.points.size) add(s.points.x(i), s.points.y(i), s.points.pressure(i), 0f, s.points.orientation(i), s.points.deltaMillis(i).toLong())
            }.build()
            val a = RecordingSink().also { StrokeRenderer.render(s, it) }.calls.map(::comparable)
            val b = RecordingSink().also { StrokeRenderer.render(s.copy(points = untilted), it) }.calls.map(::comparable)
            assertEquals(b, a, "${s.brush.kind} renders as before")
        }
        // Saved again, it becomes a file of today's format and reads back the same.
        val again = write(doc)
        assertEquals(1 to 2, manifestVersion(again))
        assertEquals(doc, read(again).document)
    }

    /** What a stroke's rendering and hit test come to, for comparing with how an earlier version drew it. */
    private fun digest(s: Stroke): DoubleArray {
        val calls = RecordingSink().also { StrokeRenderer.render(s, it) }.calls
        val line = StrokeRenderer.centreline(s)
        var sumR = 0.0
        for (i in 0 until line.size / 3) sumR += line[3 * i + 2]
        val dabs = calls.filterIsInstance<RecordingSink.Call.Dab>()
        val paths = calls.filterIsInstance<RecordingSink.Call.Path>()
        val bounds = StrokeRenderer.bounds(s)
        var hit = 0.0
        for (i in 0 until s.points.size) hit += HitTest.radiusAt(s, i)
        return doubleArrayOf(
            calls.size.toDouble(), (line.size / 3).toDouble(), sumR,
            dabs.size.toDouble(), dabs.sumOf { it.alpha.toDouble() }, dabs.sumOf { it.radius.toDouble() },
            paths.sumOf { it.xy.size / 2 }.toDouble(), paths.sumOf { p -> p.xy.sumOf { it.toDouble() } },
            bounds.width.toDouble(), bounds.height.toDouble(), hit,
        )
    }

    @Test
    fun aDrawingFromFormat11OpensAndLooksExactlyAsItDid() {
        val bytes = Base64.getDecoder().decode(GOLDEN_1_1)
        assertEquals(1 to 1, manifestVersion(bytes))
        val contents = read(bytes)
        assertEquals(9L, contents.journalToken)
        val doc = contents.document
        val strokes = doc.layers.single().strokes
        assertEquals(GOLDEN_1_1_DRAWN.keys.toList(), strokes.map { it.brush.kind.id })
        for (s in strokes) {
            val b = s.brush
            assertEquals(TiltResponse.Eased, b.tiltResponse, "${b.kind}: the only response there was")
            assertEquals(0f, b.speedDamping, "${b.kind}: no speed damping")
            assertEquals(0f, b.minWidth, "${b.kind}: no floor")
            val expected = GOLDEN_1_1_DRAWN.getValue(b.kind.id)
            val actual = digest(s)
            for (i in expected.indices) assertEquals(expected[i], actual[i], maxOf(1e-3, abs(expected[i]) * 1e-5), "${b.kind} renders as 0.3.0-test drew it (value $i)")
        }
        // The brushes keep the settings they were drawn with, not today's defaults.
        val byKind = strokes.associateBy { it.brush.kind }
        assertEquals(PressureCurve(1f, 0.7f, 1f), byKind.getValue(BrushKind.Fountain).brush.curve, "the fountain pen as 0.3.0-test drew it")
        assertEquals(BrushSpec.NATIVE_TILT_SCALE, byKind.getValue(BrushKind.Pencil).brush.tiltScale, "the pencil with its tilt")
        assertEquals(BrushSpec.NATIVE_TILT_SCALE, byKind.getValue(BrushKind.Charcoal).brush.tiltScale)
        assertEquals(0.9f, byKind.getValue(BrushKind.Charcoal).brush.pressureFlow)
        val again = write(doc)
        assertEquals(1 to 2, manifestVersion(again))
        assertEquals(doc, read(again).document)
    }

    @Test
    fun theCalibratedFieldsAreBrushFieldsThatOlderBrushesLack() {
        fun roundTrip(b: BrushSpec): BrushSpec = Codecs.readBrush(ByteReader(ByteWriter().also { Codecs.writeBrush(it, b) }.toByteArray()))
        for (kind in BrushKind.entries) assertEquals(BrushSpec.defaults(kind), roundTrip(BrushSpec.defaults(kind)), kind.id)
        val fountain = BrushSpec.defaults(BrushKind.Fountain)
        val tuned = fountain.copy(speedDamping = 0.2f, minWidth = 3.5f, tiltScale = 2f, tiltResponse = TiltResponse.Native)
        assertEquals(tuned, roundTrip(tuned))
        val plain = fountain.copy(speedDamping = 0f, minWidth = 0f)
        assertEquals(plain, roundTrip(plain))
        val plainSize = ByteWriter().also { Codecs.writeBrush(it, plain) }.size
        assertTrue(plainSize < ByteWriter().also { Codecs.writeBrush(it, fountain) }.size, "none, no field")
        // 1.1 brush records: a charcoal with its tilt scale but no response, and a fountain pen.
        val charcoal = Codecs.readBrush(ByteReader(ByteWriter().apply {
            fieldString(1, "charcoal")
            fieldFloat(20, 3f)
        }.toByteArray()))
        assertEquals(3f, charcoal.tiltScale)
        assertEquals(TiltResponse.Eased, charcoal.tiltResponse, "tilting as it did")
        val oldFountain = Codecs.readBrush(ByteReader(ByteWriter().apply {
            fieldString(1, "fountain")
            fieldFloat(2, 4f)
        }.toByteArray()))
        assertEquals(0f, oldFountain.speedDamping, "no speed damping")
        assertEquals(0f, oldFountain.minWidth, "no floor")
        // Damaged or unknown values draw as brushes did before.
        val damaged = Codecs.readBrush(ByteReader(ByteWriter().apply {
            fieldString(1, "fountain")
            fieldVarint(21, 7)
            fieldFloat(22, Float.NaN)
            fieldFloat(23, -2f)
        }.toByteArray()))
        assertEquals(TiltResponse.Eased, damaged.tiltResponse)
        assertEquals(0f, damaged.speedDamping)
        assertEquals(0f, damaged.minWidth)
    }

    @Test
    fun tiltAndOrientationSurviveAFileAsTheyWereSampled() {
        val builder = StrokeBuilder(BrushSpec.defaults(BrushKind.Charcoal), BLACK, 3)
        for (i in 0 until 30) builder.add(InputSample(10f + 5f * i, 50f, 0.6f, 0.02f * i, -1f + 0.05f * i, i * 4_000_000L))
        val tiltedStroke = builder.finish()
        val flat = StrokeBuilder(BrushSpec.defaults(BrushKind.Fineliner), BLACK, 4).apply {
            for (i in 0 until 30) add(InputSample(10f + 5f * i, 90f, 1f, timeNanos = i * 4_000_000L))
        }.finish()
        val doc = Document("t", 400, 200, layers = listOf(Layer(1, strokes = listOf(tiltedStroke, flat))), nextId = 5)
        val back = read(write(doc)).document.layers.single().strokes
        assertEquals(tiltedStroke, back[0], "tilt and orientation are packed losslessly")
        for (i in 0 until 30) assertEquals(0.02f * i, back[0].points.tilt(i), 0.004f)
        assertEquals(flat, back[1])
        assertTrue((0 until 30).all { back[1].points.tilt(it) == 0f && back[1].points.orientation(it) == 0f })
        assertEquals(BrushSpec.NATIVE_TILT_SCALE, back[0].brush.tiltScale)
        // A stroke without tilt spends no bytes on it.
        val w1 = ByteWriter().also { Codecs.writePoints(it, flat.points) }
        val w2 = ByteWriter().also { Codecs.writePoints(it, tiltedStroke.points) }
        assertEquals(0, w1.toByteArray()[1].toInt() and 3, "no tilt or orientation channel")
        assertEquals(3, w2.toByteArray()[1].toInt() and 3)
    }

    @Test
    fun theTiltScaleIsABrushFieldThatOldBrushesLackAndNewOnesKeep() {
        val charcoal = BrushSpec.defaults(BrushKind.Charcoal)
        val w = ByteWriter()
        Codecs.writeBrush(w, charcoal)
        assertEquals(charcoal, Codecs.readBrush(ByteReader(w.toByteArray())))
        val tuned = charcoal.copy(tiltScale = 2.25f)
        assertEquals(tuned, Codecs.readBrush(ByteReader(ByteWriter().also { Codecs.writeBrush(it, tuned) }.toByteArray())))
        val off = charcoal.copy(tiltScale = BrushSpec.NO_TILT)
        val offBytes = ByteWriter().also { Codecs.writeBrush(it, off) }
        assertEquals(off, Codecs.readBrush(ByteReader(offBytes.toByteArray())))
        assertTrue(offBytes.size < w.size, "no tilt, no field")
        // A 1.0 brush record: only the kind and width here; everything else takes the kind's defaults but tilt.
        val old = ByteWriter().apply {
            fieldString(1, "charcoal")
            fieldFloat(2, 9f)
        }
        val read = Codecs.readBrush(ByteReader(old.toByteArray()))
        assertEquals(BrushSpec.NO_TILT, read.tiltScale)
        assertEquals(TiltResponse.Eased, read.tiltResponse)
        assertEquals(0f, read.speedDamping)
        assertEquals(0f, read.minWidth)
        assertEquals(charcoal.grain, read.grain)
        assertEquals(9f, read.width)
        val nan = ByteWriter().apply {
            fieldString(1, "pencil")
            fieldFloat(20, Float.NaN)
        }
        assertEquals(BrushSpec.NO_TILT, Codecs.readBrush(ByteReader(nan.toByteArray())).tiltScale)
    }

    @Test
    fun theAsianCalligraphyKindRoundTrips() {
        val s = Stroke(1, BrushSpec.defaults(BrushKind.CalligraphyAsian), BLACK, PackedPoints.Builder().add(1f, 2f, 0.5f).add(30f, 40f, 0.9f).build())
        val doc = Document("a", 100, 100, layers = listOf(Layer(1, strokes = listOf(s))), nextId = 2)
        assertEquals(doc, read(write(doc)).document)
    }

    private companion object {
        /** A drawing written by Nib 0.2's engine (format 1.0), with tilted strokes, before brushes had a tilt scale. */
        const val GOLDEN_1_0 =
            "UEsDBBQACAgIAGusPF0AAAAAAAAAAAAAAAAMAAAAbWFuaWZlc3QuYmlu8/N0MmRkYOTmSs/PSUnN0zXUM2BiWsPEDMQsLP+BgJWR" +
            "k82UkZGRiYPdJ7EytUjBkJmRkYWRgZWFgaHBno2RgZ2RgYORnVNQIAckXaxvqFdcUpSfnVrMzsjOwcgAAFBLBwgDD0YEXgAAAGEA" +
            "AABQSwMEFAAICAgAa6w8XQAAAAAAAAAAAAAAABAAAABsYXllcnMvMS5zdHJva2VzvdQ/T8JAFADwe9eD0j+KojFxktVRu7hYwM3E" +
            "GI2Do2kIVSIW0qiJg6YfgIRoHISP4Mo30MUBZgcnFydXXfHutaci+C+atum7ppTrvV/eu9XlpQ2AMwBdc8teqcIvnzJCSF7JAB+D" +
            "HMWoYGTMstp2Au+TQNQpAOj7HSBBe5Bi3c6KreEjXUxGDIwmxhF2l17OjQJJs1Zz2h7Dh+MYMzzm8xP4fVric+NSegoQtmmyYJKf" +
            "ekDDq6d8d99q0wurhWf/SNjbAacAWsqtHnj7TtmjuIAw9XyYui1T73Y6caV+DKDI1IFVTZUnPv8/qY9l5wrrji/G6+7t/eMz0Sdn" +
            "ZvtJGgBqslbyiuWKWEUWQbqdOgdx3YdXkFaz2Q9ChoI8ri8gCAlBcoaQXDSFQO4XIEyC0AhkLT4QrJHijuMXqw6SkML79iCftwcb" +
            "SiJy1fBlHRkMrBqT67q/IUlIEiUi8eIjOQcwDUmydTiPnfNORaT1qUpyqErj6mlRw6rSsf0M/KOJL/1U5SSaXKgwVkOVOsTcOnuO" +
            "vxtuo0Fh+DYq9xIiReiASPBPewkXUaVIIhK5jFEkLBSnUilv+05t5+hj+1iWNcDyWijqX7ZYUU9fsaQkSzJiuYmP5QVQSwcI18gT" +
            "gtYBAAB4BwAAUEsBAhQAFAAICAgAa6w8XQMPRgReAAAAYQAAAAwAAAAAAAAAAAAAAAAAAAAAAG1hbmlmZXN0LmJpblBLAQIUABQA" +
            "CAgIAGusPF3XyBOC1gEAAHgHAAAQAAAAAAAAAAAAAAAAAJgAAABsYXllcnMvMS5zdHJva2VzUEsFBgAAAAACAAIAeAAAAKwCAAAA" +
            "AA=="

        /**
         * A drawing written by Nib 0.3.0-test's engine (format 1.1): one stroke of every inking brush at the defaults
         * of the time, with pressure, tilt from 0 to 80 degrees, orientation and timing.
         */
        const val GOLDEN_1_1 =
            "UEsDBBQACAgIACc0PV0AAAAAAAAAAAAAAAAMAAAAbWFuaWZlc3QuYmlu8/N0MmRkZOTmSs/PSUnN0zXUM2RimsDMzHSDhYXlPxCw" +
            "Mqaw6QFVMDEyMDMysjAysLIwMDTYszEysDMycDAKcAoK5CRWphYV6xvqFZcU5WenFrMzcnIwMgAAUEsHCM31m21aAAAAWgAAAFBL" +
            "AwQUAAgICAAnND1dAAAAAAAAAAAAAAAAEAAAAGxheWVycy8xLnN0cm9rZXPdmE1oE1EQx/dtNt18NWnSWFtQiQdBD14Mgh6apBWU" +
            "gggigrewpkmzGJM0TQRvq16t9RNN8VD0JtJLxaNIQS9N8WClQks9VIpIBfEDpFTqvNlOmzRJTVrjYQnzsrvZbPb9/pP/zL5TPd1n" +
            "GLvNmM0aU5PRBERGlARBCJk8DN61oIijCUdJ8vvHAmbcbmKC3MaYUPI5Y2ZxhVmkycLJgBUP2fjFBDuODhybpRlXT9DJBJc0nO8I" +
            "tOBBN44eGEOhVvx9cZ7BXeG9rJiYIE2xdlnzavs1eZlp8hzEU4heTfZocpcmD8LOBMR3iN8QXyBeQVyDOKzJPk1Oa/Iz2Flg+hWW" +
            "IN5DPII4t1Bgs3s2vubKjqy/hF1Hz/bfePJ6fsPGm7fTMx/mPy1+/fFrWZAsjhZv++69+w4cFEySWawQ7BZjVksslUtmFTUp4vzX" +
            "yfv9/jXywCpYQp41lLydyDMif9xg5DHpzyuJRDqlJrN8ui+D1dDnIelHTyj/B72D0IuEvtdg6O8xJjelo8mImuBz9WHKTxauA/dY" +
            "bCFQyv2vZrN4+ghyF3TuQTtcqtDpwH9Mjdy9uC1+BPrNRN8kvdPpX2UGw3+fe05fRknH1WxU5JhLEn+sXgH0xOfK2RC6XbJNPwYB" +
            "4Hg9Avh0AZwkgEQCjBhNgCGe/xeVzAW92GpdlYstpDHgv6IJhF8sw6/9I9/h4F0E3kzgXxgN/F1wV3tc7YsnILI6/ZEq9LmVUPKz" +
            "rdJ3HntI9POb028h+k1Ef9Zo9PWKm8kNxMNg/lhx0fnBcoA99wxiz72kxHhM26+4/JpurPJl7N3EXib2S0Zjj5kfgW5HReO/jDPu" +
            "qtzv6L5TRF/eDv2h8Z+dm2a+h+hbiP4D0WD0h2GW7iL6YWVAVZIN1+DjRFuoNg1aSQMrafDcaBqg+ySjqTA60Dp7IA3sAWRD3Yef" +
            "7MYmp4y9l9jbiP2U0dhjxxmJK5lISklsTHteaKuuL0gV0XOmVjyZOk7O1wHKxbbQ8u8gAewkwDejCTCM9r8qQPjSIVxpKNKAQ6yq" +
            "QVNFDbinWLGv4ekfAg34Fx14Uv0atJEGDtLgpslgGgwCOalXWfWeWhfYzI1Ya5gsOHTsOwl7M2EfNRr2O4zZbQP9OSUTpbZTC9WY" +
            "+dtqfABycNOi207wnQS/YDT4uLypqJm1muvrLkafD1R62qJn3SJp6GlLN52h8VwnoeeFuy7TQfQdhN5F6D8bDP0fUEsHCFcE+N+a" +
            "AwAA2hcAAFBLAQIUABQACAgIACc0PV3N9ZttWgAAAFoAAAAMAAAAAAAAAAAAAAAAAAAAAABtYW5pZmVzdC5iaW5QSwECFAAUAAgI" +
            "CAAnND1dVwT435oDAADaFwAAEAAAAAAAAAAAAAAAAACUAAAAbGF5ZXJzLzEuc3Ryb2tlc1BLBQYAAAAAAgACAHgAAABsBAAAAAA="

        /** How 0.3.0-test's engine drew [GOLDEN_1_1]'s strokes, as [digest] sums it up. */
        val GOLDEN_1_1_DRAWN: Map<String, DoubleArray> = linkedMapOf(
            "fineliner" to doubleArrayOf(1.00000000, 70.0000000, 70.0000000, 0.00000000, 0.00000000, 0.00000000, 146.000000, 20623.7522, 165.019989, 16.0140076, 24.0000000),
            "fountain" to doubleArrayOf(1.00000000, 70.0000000, 90.6675042, 0.00000000, 0.00000000, 0.00000000, 147.000000, 25262.4490, 165.616394, 16.6979218, 31.0860010),
            "ballpoint" to doubleArrayOf(3.00000000, 70.0000000, 45.3337521, 0.00000000, 0.00000000, 0.00000000, 144.000000, 28977.3687, 164.308197, 15.2942657, 15.5430005),
            "pencil" to doubleArrayOf(362.000000, 70.0000000, 112.894534, 362.000000, 97.3794685, 458.768628, 0.00000000, 0.00000000, 166.986633, 20.1737213, 38.8276193),
            "graphite" to doubleArrayOf(567.000000, 70.0000000, 46.4231580, 567.000000, 115.037655, 342.265898, 0.00000000, 0.00000000, 164.601791, 16.2851715, 15.9638709),
            "marker" to doubleArrayOf(3.00000000, 70.0000000, 560.000000, 0.00000000, 0.00000000, 0.00000000, 158.000000, 46037.8351, 179.160004, 30.1540070, 192.000000),
            "highlighter" to doubleArrayOf(1.00000000, 70.0000000, 653.521300, 0.00000000, 0.00000000, 0.00000000, 161.000000, 51651.6228, 182.831192, 33.2051086, 240.000000),
            "brush_pen" to doubleArrayOf(1.00000000, 70.0000000, 61.7683398, 0.00000000, 0.00000000, 0.00000000, 142.000000, 49870.5217, 163.345276, 15.7814484, 35.2568969),
            "calligraphy" to doubleArrayOf(1.00000000, 70.0000000, 174.549271, 0.00000000, 0.00000000, 0.00000000, 151.000000, 57666.6398, 168.679764, 19.4679565, 82.8960028),
            "calligraphy_asian" to doubleArrayOf(1.00000000, 70.0000000, 160.574719, 0.00000000, 0.00000000, 0.00000000, 149.000000, 61372.3229, 166.999237, 18.9625854, 82.8960028),
            "neo_brush" to doubleArrayOf(1.00000000, 70.0000000, 92.1642148, 0.00000000, 0.00000000, 0.00000000, 143.000000, 63177.9448, 163.509216, 16.6267395, 41.7714393),
            "charcoal" to doubleArrayOf(129.000000, 70.0000000, 379.946147, 129.000000, 18.2977477, 556.085869, 0.00000000, 0.00000000, 177.325119, 37.0557861, 130.588516),
            "charcoal_v2" to doubleArrayOf(100.000000, 70.0000000, 602.945097, 100.000000, 10.9108101, 685.319737, 0.00000000, 0.00000000, 188.052368, 54.2369385, 207.106222),
            "dash" to doubleArrayOf(1.00000000, 70.0000000, 70.0000000, 0.00000000, 0.00000000, 0.00000000, 0.00000000, 0.00000000, 165.019989, 16.0140686, 24.0000000),
            "square_pen" to doubleArrayOf(1.00000000, 70.0000000, 83.7061809, 0.00000000, 0.00000000, 0.00000000, 144.000000, 80817.4708, 166.755081, 17.6930542, 39.2640030),
            "airbrush" to doubleArrayOf(53.0000000, 70.0000000, 954.100155, 53.0000000, 1.58097532, 686.894249, 0.00000000, 0.00000000, 191.909195, 49.2367249, 327.120041),
        )
    }
}
