package moe.antimony.hoshi.features.reader

internal object ReaderLayoutDefaults {
    // Mirrors the first-run iOS ReaderWebView defaults:
    // verticalWriting=true, fontSize=22, horizontalPadding=5, verticalPadding=0.
    const val fontSizePx: Int = 22
    const val bottomOverlapPx: Int = fontSizePx
    const val imageWidthViewportRatio: Double = 0.95

    const val columnGapCss: String = "calc(0vh + 22px)"
    const val pagePaddingCss: String = "0vh 2.5vw"
    const val bottomPaddingCss: String = "calc(0vh + 22px)"
    const val imageMaxWidthFallbackCss: String = "95vw"
    const val imageMaxHeightFallbackCss: String = "calc(var(--page-height, 100vh) - 22px)"
    const val trailingSpacerHeightCss: String = "calc(0vh + 22px)"
    // The spacer only needs inline height; physical width would allocate a new column in Android WebView.
    const val trailingSpacerWidthCss: String = "0"
}

internal object ReaderContentStyles {
    fun styleTag(): String = """
        <style>
        @media (prefers-color-scheme: light) { :root { --hoshi-text-color: #000; } }
        @media (prefers-color-scheme: dark) { :root { --hoshi-text-color: #fff; } }
        html, body {
            overflow: hidden !important;
            height: var(--page-height, 100vh) !important;
            width: var(--page-width, 100vw) !important;
            margin: 0 !important;
            padding: 0 !important;
            background: #f7f3ea !important;
            color: var(--hoshi-text-color) !important;
            writing-mode: vertical-rl !important;
        }
        body {
            font-family: serif !important;
            font-size: ${ReaderLayoutDefaults.fontSizePx}px !important;
            box-sizing: border-box !important;
            column-width: var(--page-width, 100vw) !important;
            column-gap: ${ReaderLayoutDefaults.columnGapCss};
            padding: ${ReaderLayoutDefaults.pagePaddingCss} !important;
            padding-bottom: ${ReaderLayoutDefaults.bottomPaddingCss} !important;
            text-align: start !important;
            hanging-punctuation: allow-end !important;
            line-break: strict !important;
            text-orientation: mixed;
        }
        img.block-img {
            max-width: var(--hoshi-image-max-width, ${ReaderLayoutDefaults.imageMaxWidthFallbackCss}) !important;
            max-height: var(--hoshi-image-max-height, ${ReaderLayoutDefaults.imageMaxHeightFallbackCss}) !important;
            width: var(--hoshi-image-max-width, ${ReaderLayoutDefaults.imageMaxWidthFallbackCss}) !important;
            height: var(--hoshi-image-max-height, ${ReaderLayoutDefaults.imageMaxHeightFallbackCss}) !important;
            display: block !important;
            margin: auto !important;
            break-inside: avoid !important;
            -webkit-column-break-inside: avoid !important;
            object-fit: contain !important;
        }
        svg {
            max-width: var(--hoshi-image-max-width, ${ReaderLayoutDefaults.imageMaxWidthFallbackCss}) !important;
            max-height: var(--hoshi-image-max-height, ${ReaderLayoutDefaults.imageMaxHeightFallbackCss}) !important;
            width: 100% !important;
            height: 100% !important;
            display: block !important;
            margin: auto !important;
            break-inside: avoid !important;
            -webkit-column-break-inside: avoid !important;
        }
        rt {
            font-size: 0.45em;
        }
        a {
            color: rgba(66, 108, 245, 1) !important;
        }
        </style>
    """.trimIndent()
}
