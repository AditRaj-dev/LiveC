package com.livec.app.ui.theme

import androidx.compose.ui.graphics.Color

// Mirrors the Windows app palette (desktop/src/index.css).
object LiveCColors {
    val BgBase     = Color(0xFF09090B) // zinc-950
    val BgSurface  = Color(0xFF18181B) // zinc-900
    val BgElevated = Color(0xFF27272A) // zinc-800
    val Border     = Color(0xFF2A2A2E)
    val BorderSoft = Color(0xFF1F1F22)

    val TextPrimary   = Color(0xFFF4F4F5) // zinc-100
    val TextSecondary = Color(0xFFA1A1AA) // zinc-400
    val TextTertiary  = Color(0xFF71717A) // zinc-500

    val Accent     = Color(0xFFFBBF24) // amber-400
    val AccentDim  = Color(0x33FBBF24)

    val SevLow      = Color(0xFF34D399) // green
    val SevMed      = Color(0xFF38BDF8) // sky
    val SevHigh     = Color(0xFFFBBF24) // amber
    val SevCritical = Color(0xFFFB7185) // rose
}
