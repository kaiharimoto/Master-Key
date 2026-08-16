package dev.kaiharimoto.masterkey

import android.app.Application
import dev.kaiharimoto.masterkey.di.AppGraph

class MasterKeyApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        instance = this
    }

    companion object {
        lateinit var instance: MasterKeyApp
            private set
    }
}
