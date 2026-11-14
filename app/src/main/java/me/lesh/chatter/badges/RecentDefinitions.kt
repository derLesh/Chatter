package me.lesh.chatter.badges

/** Keeps worn cosmetics and a bounded margin for definitions sent before their wearer event. */
internal class RecentDefinitions<T>(private val max: Int, private val referenced: () -> Set<String>) {
    private val definitions = LinkedHashMap<String, T>(16, 0.75f, true)

    operator fun get(id: String): T? = synchronized(definitions) { definitions[id] }

    operator fun set(id: String, value: T) = synchronized(definitions) {
        definitions[id] = value
        if (definitions.size > max) {
            val worn = referenced()
            val iterator = definitions.keys.iterator()
            while (definitions.size > max && iterator.hasNext()) {
                if (iterator.next() !in worn) iterator.remove()
            }
        }
    }

    val values: List<T> get() = synchronized(definitions) { definitions.values.toList() }
}
