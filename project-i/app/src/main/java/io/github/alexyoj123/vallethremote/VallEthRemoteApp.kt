package io.github.alexyoj123.vallethremote

import android.app.Application
import io.github.alexyoj123.vallethremote.core.DiagLog

class VallEthRemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
    }
}
