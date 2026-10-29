package com.yaprak.reader

import android.app.Application
import com.yaprak.reader.core.AppContainer

class YaprakApplication : Application() {
    val container by lazy { AppContainer(applicationContext) }
}
