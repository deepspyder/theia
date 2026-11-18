package com.spektralab.android

import com.spektralab.android.engine.Rgba16Source
import com.spektralab.android.engine.host.RenderAppearance

internal object RenderAppearanceAudit {
    fun run(): String {
        val reference = DoubleArray(48 * 32) { i -> .1 + .4 * (i % 48) / 48 + .4 * (i / 48) / 32 }
        fun signature(values: DoubleArray, opaque: Boolean = true) = RenderAppearance.Signature(values, opaque)
        check(RenderAppearance.compatible(signature(reference), signature(reference.copyOf())))
        val noisy = DoubleArray(reference.size) { reference[it] + (it % 7 - 3) * .002 }
        check(RenderAppearance.compatible(signature(reference), signature(noisy)))
        val broken = DoubleArray(reference.size) { i -> if (i / 48 in 10..21) .85 else 0.0 }
        check(!RenderAppearance.compatible(signature(reference), signature(broken))) { "Large black bands accepted" }
        check(!RenderAppearance.compatible(signature(reference), signature(DoubleArray(reference.size) { .9 })))
        check(!RenderAppearance.compatible(signature(reference), signature(reference, false)))
        // Bright sprocket holes can keep global correlation high even when
        // most of the photograph disappeared, as in the reported RAW exports.
        val film = DoubleArray(48 * 32) { i ->
            if (i / 48 < 4 || i / 48 >= 28) { if (i % 8 < 4) .95 else .02 }
            else .18 + .12 * (i % 48) / 48
        }
        val missingFilm = film.copyOf().apply { for (y in 10..25) for (x in 0 until 48) this[y * 48 + x] = 0.0 }
        check(!RenderAppearance.compatible(signature(film), signature(missingFilm))) { "Intact sprockets hid missing photograph rows" }
        check(RenderAppearance.compatible(signature(DoubleArray(reference.size) { .001 }),
            signature(DoubleArray(reference.size) { .002 }))) { "Flat reference was treated as a reliable oracle" }
        val source = object : Rgba16Source {
            override val width = 8
            override val height = 8
            override fun readRows(y: Int, rows: Int, destination: ShortArray) {
                repeat(width * rows) { i ->
                    destination[4 * i] = 12000
                    destination[4 * i + 1] = 24000
                    destination[4 * i + 2] = 6000
                    destination[4 * i + 3] = (-1).toShort()
                }
            }
        }
        check(RenderAppearance.capture(source) {}.opaque)
        val sentinel = IllegalStateException("appearance-cancel-sentinel")
        check(runCatching { RenderAppearance.capture(source) { throw sentinel } }.exceptionOrNull() === sentinel)
        return "Valid/noisy/flat frames accepted; large black bands, lost structure and invalid alpha rejected; row sampling and cancellation verified"
    }
}
