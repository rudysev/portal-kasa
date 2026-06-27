package com.portal.kasa

import android.content.Context
import com.portal.kasa.data.KasaRepository
import com.portal.kasa.net.KasaLocalClient
import kotlinx.coroutines.Dispatchers

/**
 * Minimal, dependency-free object graph — the one place [KasaRepository] is constructed and held, so the
 * activity and the [KasaToolProvider][com.portal.kasa.provider.KasaToolProvider] share a single instance (the
 * app relies on one in-process cache). This is the seam a real DI framework (Hilt/Koin) would slot into; until
 * then it's a lazy, thread-safe service locator.
 *
 * Discovery runs on the unbounded `Dispatchers.IO` injected here; the repository derives its bounded
 * send-dispatcher from it (`limitedParallelism(MAX_PARALLEL_SENDS)`) so a mass-toggle can't saturate IO. The
 * dispatcher is injected rather than hardcoded in the data layer, keeping it dispatcher-agnostic and testable.
 */
object Graph {
    @Volatile private var instance: KasaRepository? = null

    fun repository(context: Context): KasaRepository =
        instance ?: synchronized(this) {
            instance ?: KasaRepository(
                client = KasaLocalClient(context.applicationContext),
                io = Dispatchers.IO, // relayDispatcher defaults to io.limitedParallelism(MAX_PARALLEL_SENDS)
            ).also { instance = it }
        }
}
