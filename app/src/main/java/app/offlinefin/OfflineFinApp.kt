package app.offlinefin

import android.app.Application
import android.content.Context

class OfflineFinApp : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }

    companion object {
        fun graph(context: Context): AppGraph =
            (context.applicationContext as OfflineFinApp).graph
    }
}
