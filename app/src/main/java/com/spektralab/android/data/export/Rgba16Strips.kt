package com.spektralab.android.data.export

import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source

/** The existing final quantizer, with bounded storage shared by Bitmap and row encoders. */
internal class Rgba16Strips(private val source: Rgba16Source, private val grade: Grade) {
    private val rows = minOf(32, source.height)
    private val strip = ShortArray(source.width * rows * 4)
    private val argb = IntArray(source.width * rows)
    private val pixel = FloatArray(3)
    private val native = if (grade.usesNativeGrade) FloatArray(source.width * rows * 3) else null

    fun forEach(check: () -> Unit, consume: (y: Int, rows: Int, argb: IntArray) -> Unit) {
        var y = 0
        while (y < source.height) {
            check()
            val count = minOf(rows, source.height - y)
            source.readRows(y, count, strip)
            if (native != null) grade.nativeRows(strip, 0, source.width, source.height, y, count, native)
            if (grade.isIdentity) {
                for (i in 0 until source.width * count) {
                    val r = ((strip[i * 4].toInt() and 65535) * 255 + 32767) / 65535
                    val g = ((strip[i * 4 + 1].toInt() and 65535) * 255 + 32767) / 65535
                    val b = ((strip[i * 4 + 2].toInt() and 65535) * 255 + 32767) / 65535
                    argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            } else {
                for (i in 0 until source.width * count) {
                    if (native != null) {
                        pixel[0] = native[i * 3]; pixel[1] = native[i * 3 + 1]; pixel[2] = native[i * 3 + 2]
                    } else grade.gradeAt(strip[i * 4], strip[i * 4 + 1], strip[i * 4 + 2], pixel,
                        i % source.width, y + i / source.width, source.width, source.height)
                    val r = (pixel[0] * 255f + .5f).toInt().coerceIn(0, 255)
                    val g = (pixel[1] * 255f + .5f).toInt().coerceIn(0, 255)
                    val b = (pixel[2] * 255f + .5f).toInt().coerceIn(0, 255)
                    argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            consume(y, count, argb)
            y += count
        }
    }
}
