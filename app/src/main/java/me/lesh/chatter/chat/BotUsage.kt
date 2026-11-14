package me.lesh.chatter.chat

/**
 * One argument as a command's usage writes it: "<dauer>", "[user]", "<join|leave>" or a fixed word
 * like "list". [choices] are the words it can be, empty when anything goes.
 */
data class BotArgument(val shown: String, val choices: List<String> = emptyList(), val namesSomeone: Boolean = false) {
    fun accepts(word: String) = choices.isEmpty() || choices.any { it.equals(word, ignoreCase = true) }
}

/** What follows a bot command in the input. [current] is the word the cursor is in, "" between words. */
data class TypedArguments(val command: String, val before: List<String>, val current: String)

/** One way of writing a command, with the argument the cursor is in, if it is in one. */
data class UsageForm(val arguments: List<BotArgument>, val current: Int?)

/**
 * The hint above the input while a bot command's arguments are typed: [forms] are the ways of
 * writing it that still fit what was typed. [choices] and [namesSomeone] say what to offer for the
 * argument the cursor is in, [parameters] the named ones not used yet, which can go anywhere.
 */
data class ArgumentHint(
    val command: String,
    val forms: List<UsageForm>,
    val description: String,
    val choices: List<String> = emptyList(),
    val namesSomeone: Boolean = false,
    val parameters: List<String> = emptyList(),
)

/**
 * Reads the usage the bots write for their commands, like ChronophylosBot's
 * "!p <join|leave> <name> | !p list" or PotatBot's "#badge @<user> or <badge>".
 */
object BotUsage {
    /** The usage without the command it starts with, each way of writing it split off by " | ". */
    fun withoutCommand(usage: String): String = split(usage)
        .map { form -> if (form.firstOrNull()?.let(::isCommand) == true) form.drop(1) else form }
        .filter { it.isNotEmpty() }
        .joinToString(" | ") { it.joinToString(" ") }

    /**
     * A usage read from examples like Supibot's "$cookie donate (user)", where "(user)" stands for
     * an argument. Lower-case words before the first one are subcommands: "$cookie eat". Words
     * after it stand in for what the user writes: "$remind (person) test" is "<person> <text>".
     * Examples with real values only, like "$weather Berlin", are left out. [names] start with the
     * command's own, then its aliases, each with the prefix; [parameters] are the named ones.
     * Without [subcommands], an example with a word before the first argument is left out.
     */
    fun fromExamples(
        examples: List<String>,
        names: List<String>,
        parameters: Collection<String>,
        subcommands: Boolean = true,
    ): String {
        val forms = LinkedHashSet<String>()
        for (example in examples) {
            val words = split(example.replace(PLACEHOLDER, "<$1>")).singleOrNull() ?: continue
            val name = words.firstOrNull()
            if (names.none { it.equals(name, ignoreCase = true) }) continue
            val arguments = words.drop(1).filterNot { word -> parameters.any { word.startsWith("$it:", ignoreCase = true) } }
            val firstPlaceholder = arguments.indexOfFirst(::isPlaceholder).takeIf { it >= 0 } ?: arguments.size
            val leading = arguments.take(firstPlaceholder)
            if (leading.any { !SUBCOMMAND.matches(it) }) continue
            // "$remindme test (time)": an alias can mean other arguments than the command itself.
            if (leading.isNotEmpty() && (!subcommands || !names.first().equals(name, ignoreCase = true))) continue
            if (leading.size > 1 && firstPlaceholder == arguments.size) continue
            // The user's text takes the rest of the message, so nothing follows it.
            val rest = arguments.drop(firstPlaceholder).fold(emptyList<String>()) { form, word ->
                when {
                    TEXT in form -> form
                    isPlaceholder(word) -> form + word
                    else -> form + TEXT
                }
            }
            if (arguments.isNotEmpty()) forms += (leading + rest).joinToString(" ")
        }
        // "<place> <text>" says no more than "<place>", whose last argument takes the rest as well.
        return forms.filterNot { it.endsWith(" $TEXT") && it.removeSuffix(" $TEXT") in forms }.joinToString(" | ")
    }

    private fun isPlaceholder(word: String) = word.startsWith('<') && word.endsWith('>')

    /** Each way of writing a command, from a usage without the command, as [withoutCommand] gives it. */
    fun forms(arguments: String): List<List<BotArgument>> = split(arguments).filter { it.isNotEmpty() }.map { form -> form.map(::argument) }

    /**
     * Null unless the input starts with something like a command and the cursor is past it. A
     * space between quotes stays in its word: on:"tomorrow 10am" is one.
     */
    fun typed(text: String, cursor: Int): TypedArguments? {
        val end = text.indexOf(' ')
        if (end <= 0 || cursor <= end || cursor > text.length) return null
        val command = text.substring(0, end)
        if (!BotCommands.startsLikeCommand(command)) return null
        val words = ArrayList<String>()
        val word = StringBuilder()
        var quoted = false
        for (char in text.substring(end, cursor)) {
            if (char == '"') quoted = !quoted
            if (char == ' ' && !quoted) {
                if (word.isNotEmpty()) words += word.toString()
                word.clear()
            } else {
                word.append(char)
            }
        }
        return if (word.isEmpty()) TypedArguments(command, words, "") else TypedArguments(command, words, word.toString())
    }

    /**
     * Where [typed] is in [command]'s arguments. The last argument of a form takes the rest of the
     * message when anything goes there: "!fb <nachricht>" is followed by a whole sentence.
     */
    fun hint(command: BotSuggestion, typed: TypedArguments): ArgumentHint? {
        val forms = forms(command.arguments)
        if (forms.isEmpty() && command.parameters.isEmpty()) return null
        fun named(word: String, parameter: String) = word.startsWith(parameter.substringBefore(':') + ":", ignoreCase = true)
        fun typing(option: String) = option.startsWith(typed.current, ignoreCase = true)
        val parameters = command.parameters.filter { p ->
            typed.before.none { named(it, p) } && typing(p) && !p.equals(typed.current, ignoreCase = true)
        }
        val before = typed.before.filterNot { word -> command.parameters.any { named(word, it) } }
        val position = before.size
        fun at(form: List<BotArgument>, index: Int) =
            form.getOrNull(index) ?: form.last().takeIf { it.choices.isEmpty() }
        val fitting = forms.filter { form -> before.indices.all { i -> at(form, i)?.accepts(before[i]) == true } }
        if (fitting.isEmpty()) {
            return ArgumentHint(typed.command, forms.map { UsageForm(it, null) }, command.description, parameters = parameters)
        }
        val current = fitting.mapNotNull { at(it, position) }
        return ArgumentHint(
            command = typed.command,
            forms = fitting.map { form -> UsageForm(form, at(form, position)?.let { minOf(position, form.lastIndex) }) },
            description = command.description,
            choices = current.flatMap { it.choices }.distinctBy { it.lowercase() }.filter(::typing),
            namesSomeone = current.any { it.namesSomeone },
            parameters = parameters,
        )
    }

    /** Words, with "<…>" and "[…]" kept whole even with spaces inside, grouped by "|" and "or". */
    private fun split(usage: String): List<List<String>> {
        val forms = mutableListOf(mutableListOf<String>())
        val word = StringBuilder()
        var depth = 0
        fun endWord() {
            val text = word.toString()
            word.clear()
            when {
                text.isEmpty() -> Unit
                text == "|" || text == "or" -> forms += mutableListOf<String>()
                else -> forms.last() += text
            }
        }
        for (char in usage.trim()) {
            when {
                char == '<' || char == '[' -> depth++
                (char == '>' || char == ']') && depth > 0 -> depth--
            }
            if (char.isWhitespace() && depth == 0) endWord() else word.append(char)
        }
        endWord()
        return forms
    }

    /** "!p", "#mymostused" or PotatBot's "#pinemote/unpinemote"; not "@<user>" or "<x>". */
    private fun isCommand(word: String) = BotCommands.startsLikeCommand(word) && word.none { it == '<' || it == '[' }

    private fun argument(word: String): BotArgument {
        val bracketed = (word.startsWith('<') && word.endsWith('>')) || (word.startsWith('[') && word.endsWith(']'))
        val inner = if (bracketed) word.substring(1, word.length - 1) else word
        val choices = when {
            // "<PLZ/ICAO/IATA/Ort>" names kinds of things; "<join|leave>" the words themselves.
            bracketed -> inner.split('|').takeIf { it.size > 1 }
            word.any { it == '<' || it == '[' } -> null
            else -> inner.split('/', '|')
        }?.takeIf { options -> options.all { WORD.matches(it) } }.orEmpty()
        return BotArgument(word, choices, namesSomeone = choices.isEmpty() && SOMEONE.containsMatchIn(inner.lowercase()))
    }

    private val WORD = Regex("[\\p{L}\\p{N}_-]+")
    private val SOMEONE = Regex("(^|[^a-z])(user|username|person|nutzer|chatter)([^a-z]|$)")
    private val PLACEHOLDER = Regex("""\(([^()]+)\)""")
    private val SUBCOMMAND = Regex("[a-z0-9][a-z0-9-]{0,19}")
    private const val TEXT = "<text>"
}
