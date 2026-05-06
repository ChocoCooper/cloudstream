package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KissasianPlugin: Plugin() {
    override fun load(context: Context) {
        // Register your scraper
        registerMainAPI(Kissasian())
        
        // Register your custom extractors here!
        registerExtractorAPI(LuluvidExtractor())
        registerExtractorAPI(StrcloudExtractor())
    }
}
