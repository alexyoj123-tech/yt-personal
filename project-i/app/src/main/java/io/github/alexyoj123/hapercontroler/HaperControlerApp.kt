package io.github.alexyoj123.hapercontroler

import android.app.Application
import io.github.alexyoj123.hapercontroler.core.DiagLog

class HaperControlerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
    }
}
