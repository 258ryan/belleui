package com.belleui.tube

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/** NewPipe's extractor asks this for every web page and API call. Plain HttpURLConnection, no extra libraries. */
class TubeDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        conn.requestMethod = request.httpMethod()
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        for ((k, values) in request.headers()) {
            // let the connection decode the body itself: it only does so for the encoding it asked for
            if (k.equals("Accept-Encoding", ignoreCase = true)) continue
            values.forEachIndexed { i, v -> if (i == 0) conn.setRequestProperty(k, v) else conn.addRequestProperty(k, v) }
        }
        request.dataToSend()?.let { body ->
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (code == 429) {
            conn.disconnect()
            throw ReCaptchaException("YouTube asked for a captcha or is rate limiting", request.url())
        }
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val headers = HashMap<String, List<String>>()
        for ((k, v) in conn.headerFields) if (k != null) headers[k] = v
        val response = Response(code, conn.responseMessage ?: "", headers, body, conn.url.toString())
        conn.disconnect()
        return response
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
