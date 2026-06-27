package com.portal.kasa.data

/**
 * Domain model for a smart plug — what the data, UI, and voice layers work with. Mapped from the net-layer
 * wire type ([KasaLocal.Device][com.portal.kasa.net.KasaLocal.Device]) at the [KasaRepository] boundary, so
 * transport details stay in `net` and don't leak into the UI. Structurally the same as the wire type today,
 * but decoupled so the two can diverge (e.g. a future `room`/`lastSeen`) without touching the protocol code.
 */
data class Plug(
    val ip: String,
    val alias: String,
    val on: Boolean,
)
