package dev.chatter.app.net

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.IOException
import okio.buffer

/**
 * Fails a picture larger than [maxBytes], which then falls back like any picture that did not
 * load. Pictures load on their own as chat scrolls by, so one file must not fill the disk cache or
 * the memory. Checked while reading as well, since a server need not say the size up front.
 */
class ImageSizeLimit(private val maxBytes: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.body.contentLength() > maxBytes) {
            response.close()
            throw IOException("Picture larger than $maxBytes bytes: ${chain.request().url}")
        }
        return response.newBuilder().body(limited(response.body)).build()
    }

    private fun limited(body: ResponseBody): ResponseBody {
        val source = object : ForwardingSource(body.source()) {
            private var read = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count > 0) read += count
                if (read > maxBytes) throw IOException("Picture larger than $maxBytes bytes")
                return count
            }
        }
        return source.buffer().asResponseBody(body.contentType(), body.contentLength())
    }
}
