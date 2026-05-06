package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KissasianPlugin: Plugin() {
    override fun load(context: Context) {
        // Register the main scraper
        registerMainAPI(Kissasian())
    }
}
