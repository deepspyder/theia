package com.spektralab.android.core.model

/** Frozen at submission, including decode-side choices and metadata access. */
internal data class ExportOptions(
    val format: String,
    val resolution: Int,
    val colorSpace: String,
    val jpegQuality: Int,
    val keepCapture: Boolean,
    val keepLocation: Boolean,
    val correctLens: Boolean,
    val customSize: ExportSize? = null,
    val naming: ExportNaming = ExportNaming(),
) {
    init {
        require(format in listOf("JPEG", "PNG", "TIFF"))
        require(resolution >= 0 && jpegQuality in 1..100)
        require(colorSpace in listOf("sRGB", "Display P3"))
    }

    // Legacy presets retain their decode-side sizing. Custom limits use the original
    // rendered raster so that crop, turn and border dimensions are accounted for once.
    val renderResolution: Int get() = if (customSize == null) resolution else 0
    val outputColorSpace: String
        get() = if (format == "TIFF") colorSpace else "sRGB"
}
