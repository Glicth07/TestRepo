package com.idlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver

class IdlixExtractor : ExtractorApi() {
    override var name = "Idlix"
    override var mainUrl = "https://z3.idlixku.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val regex = Regex("""majorplay\.net/v/[^"'\s]+config[^"'\s]*\.json""")
        val script = """var __idlixClicked=false; var __idlixIv=setInterval(function(){ if(__idlixClicked){clearInterval(__idlixIv);return;} var b=Array.from(document.querySelectorAll('button')).find(function(x){return (x.outerHTML||'').indexOf('absolute inset-0')>=0;}); if(b){b.click();__idlixClicked=true;clearInterval(__idlixIv);} },1000);"""
        val resolver = WebViewResolver(
            regex,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            script = script,
            timeout = 45_000L
        )

        val res = app.get(url, interceptor = resolver)
        val videoUrl = res.url
        if (videoUrl.contains("majorplay")) {
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    videoUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                }
            )
        }
    }
}