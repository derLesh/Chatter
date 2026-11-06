package me.lesh.chatter.emotes

/**
 * Twitch's smileys. Helix names them by the pattern Twitch matches, such as `\:-?\)`, so nobody
 * could ever type that name; each gets the short form people type instead.
 */
object TwitchSmileys {
    private class Smiley(val name: String, val helixName: String, val typedAs: Regex)

    /** Most used first, the order autocomplete offers them in. */
    private val all = listOf(
        Smiley(":)", """\:-?\)""", Regex(""":-?\)""")),
        Smiley(":D", """\:-?D""", Regex(""":-?D""")),
        Smiley(":(", """\:-?\(""", Regex(""":-?\(""")),
        Smiley(":P", """\:-?(p|P)""", Regex(""":-?[pP]""")),
        Smiley(";)", """\;-?\)""", Regex(""";-?\)""")),
        Smiley(";P", """\;-?(p|P)""", Regex(""";-?[pP]""")),
        Smiley(":O", """\:-?(o|O)""", Regex(""":-?[oO]""")),
        Smiley(":/", """\:-?[\\/]""", Regex(""":-?[\\/]""")),
        Smiley(":|", """\:-?[z|Z|\|]""", Regex(""":-?[zZ|]""")),
        Smiley("<3", """\&lt\;3""", Regex("""<3""")),
        Smiley(">(", """\&gt\;\(""", Regex(""">\(""")),
        Smiley("O_o", """[oO](_|\.)[oO]""", Regex("""[oO][_.][oO]""")),
        Smiley("B)", """B-?\)""", Regex("""B-?\)""")),
        Smiley("R)", """R-?\)""", Regex("""R-?\)""")),
    )

    private val nameByHelixName = all.associate { it.helixName to it.name }
    private val orderByName = all.withIndex().associate { (index, smiley) -> smiley.name to index }

    /** The name to show and type for an emote Helix calls [helixName]; other emotes keep theirs. */
    fun nameOf(helixName: String): String = nameByHelixName[helixName] ?: helixName

    /** The smiley [word] types, such as ":)" for ":-)"; null if it types none. */
    fun typedBy(word: String): String? {
        if (word.length !in 2..3) return null
        return all.firstOrNull { it.typedAs.matches(word) }?.name
    }

    /** Where [name] stands among the smileys, most used first; after them if it is none. */
    fun order(name: String): Int = orderByName[name] ?: Int.MAX_VALUE
}
