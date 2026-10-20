package com.fourKHDHub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FourKHDHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FourKHDHub())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(Hubstream())
        registerExtractorAPI(Hubstreamdad())
        registerExtractorAPI(Hubcdnn())
        registerExtractorAPI(PixelDrainDev())
        registerExtractorAPI(HUBCDN())
    }
}
