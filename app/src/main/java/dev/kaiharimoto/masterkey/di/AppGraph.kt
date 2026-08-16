package dev.kaiharimoto.masterkey.di

import android.content.Context
import dev.kaiharimoto.masterkey.audio.PlaybackEngine
import dev.kaiharimoto.masterkey.data.MasterKeyDatabase
import dev.kaiharimoto.masterkey.data.SongRepository
import dev.kaiharimoto.masterkey.update.UpdateRepository

/**
 * Hand-rolled dependency graph.
 *
 * A DI framework would earn its keep on a large team; here it would add a
 * compiler plugin and a layer of indirection to wire up four objects. These are
 * all application-scoped singletons — in particular the [PlaybackEngine], which
 * owns a native audio stream and must outlive any single screen so that audio
 * keeps running while you navigate.
 */
class AppGraph(private val context: Context) {

    val database: MasterKeyDatabase by lazy { MasterKeyDatabase.create(context) }

    val songRepository: SongRepository by lazy {
        SongRepository(context, database.songDao())
    }

    val practiceDao by lazy { database.practiceDao() }

    val playbackEngine: PlaybackEngine by lazy { PlaybackEngine(context) }

    val updateRepository: UpdateRepository by lazy { UpdateRepository(context) }
}
