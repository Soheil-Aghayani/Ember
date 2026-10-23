package com.soheil.ember.data

enum class TlsSplitMode(
    val label: String,
    val description: String,
) {
    OFF(
        label = "Disabled",
        description = "Standard TLS handshake without fragmentation",
    ),
    FIRST_BYTE(
        label = "First byte",
        description = "Splits TLS record header at byte 0 (0x16)",
    ),
    SNI(
        label = "SNI hostname",
        description = "Fragments the SNI extension across packets (defeats DPI)",
    ),
}
