package me.lesh.chatter.emotes

import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The emote lists of the last start, so messages get their emotes right away instead of turning
 * from words into emotes once the providers answer, which made the chat jump.
 *
 * One small gzip file per scope: "global" or a Twitch channel id. A channel with a few hundred
 * emotes takes a few kilobytes. The lists can be downloaded again, so they live in the cache folder.
 */
internal class EmoteListFiles(private val folder: File) {
    fun read(scope: String): ProviderEmotes? {
        val file = file(scope) ?: return null
        return runCatching {
            if (!file.isFile) return null
            decode(GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() })
        }.getOrNull()
    }

    fun write(scope: String, emotes: ProviderEmotes) {
        val file = file(scope) ?: return
        runCatching {
            folder.mkdirs()
            // Renamed into place, so a write cut short by the app ending leaves the old list.
            val partial = File(folder, "${file.name}.part")
            GZIPOutputStream(partial.outputStream()).bufferedWriter().use { it.write(encode(emotes)) }
            if (!partial.renameTo(file)) partial.delete()
        }
    }

    /** Drops the lists of channels that are no longer open; the global one stays. */
    fun keepOnly(channelIds: Set<String>) {
        folder.listFiles()?.forEach { file ->
            val scope = file.name.substringBefore('.')
            if (scope != GLOBAL && scope !in channelIds) file.delete()
        }
    }

    fun clear() {
        folder.deleteRecursively()
    }

    private fun file(scope: String): File? = if (SCOPE.matches(scope)) File(folder, "$scope.tsv.gz") else null

    companion object {
        const val GLOBAL = "global"

        /** Goes up when the format changes; a file in another format is ignored. */
        private const val FORMAT = "1"

        private val SCOPE = Regex("global|[0-9]{1,20}")

        private const val SIZE_KNOWN = 1
        private const val ZERO_WIDTH = 2
        private const val CHANNEL = 4
        private const val UNLISTED = 8

        /** One emote per line, tab-separated: provider, name, id, url, aspect ratio, flags, owner, author. */
        fun encode(emotes: ProviderEmotes): String = buildString {
            append(FORMAT).append('\n')
            for (map in listOf(emotes.ffz, emotes.bttv, emotes.sevenTv)) for (e in map.values) {
                val flags = (if (e.sizeKnown) SIZE_KNOWN else 0) or (if (e.zeroWidth) ZERO_WIDTH else 0) or
                    (if (e.isChannel) CHANNEL else 0) or (if (e.unlisted) UNLISTED else 0)
                listOf(e.provider.name, e.name, e.id, e.url, e.aspectRatio.toString(), flags.toString(), e.ownerId.orEmpty(), e.author.orEmpty())
                    .joinTo(this, "\t") { it.replace('\t', ' ').replace('\n', ' ') }
                append('\n')
            }
        }

        fun decode(text: String): ProviderEmotes? {
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next() != FORMAT) return null
            val byProvider = HashMap<EmoteProvider, HashMap<String, Emote>>()
            for (line in lines) {
                val f = line.split('\t')
                if (f.size != 8) continue
                val provider = EmoteProvider.entries.firstOrNull { it.name == f[0] } ?: continue
                val flags = f[5].toIntOrNull() ?: continue
                val emote = Emote(
                    name = f[1],
                    id = f[2],
                    url = f[3],
                    provider = provider,
                    aspectRatio = f[4].toFloatOrNull() ?: 1f,
                    sizeKnown = flags and SIZE_KNOWN != 0,
                    zeroWidth = flags and ZERO_WIDTH != 0,
                    isChannel = flags and CHANNEL != 0,
                    ownerId = f[6].ifEmpty { null },
                    unlisted = flags and UNLISTED != 0,
                    author = f[7].ifEmpty { null },
                )
                byProvider.getOrPut(provider) { HashMap() }[emote.name] = emote
            }
            return ProviderEmotes(
                ffz = byProvider[EmoteProvider.Ffz].orEmpty(),
                bttv = byProvider[EmoteProvider.Bttv].orEmpty(),
                sevenTv = byProvider[EmoteProvider.SevenTv].orEmpty(),
            )
        }
    }
}
