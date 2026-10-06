package com.rebahin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class RebahinProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(RebahinProvider())
        registerExtractorAPI(Daisy())
        registerExtractorAPI(Datura())
    }
}
