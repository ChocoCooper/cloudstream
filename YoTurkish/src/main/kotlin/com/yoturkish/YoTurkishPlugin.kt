package com.yoturkish

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoTurkishPlugin: Plugin() {
    override fun load() {
        registerMainAPI(YoTurkish())
        registerExtractorAPI(EngifuosiExtractor())
        registerExtractorAPI(TukiPastiExtractor())
    }
}
