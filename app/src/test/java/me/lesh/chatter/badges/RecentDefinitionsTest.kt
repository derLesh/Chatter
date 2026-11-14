package me.lesh.chatter.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentDefinitionsTest {
    @Test
    fun wornDefinitionsStayWhileUnreferencedDefinitionsMakeRoom() {
        val wearers = RecentWearers(max = 2)
        val definitions = RecentDefinitions<String>(max = 3, referenced = wearers::cosmeticIds)
        definitions["a"] = "worn a"
        definitions["b"] = "worn b"
        wearers["1"] = "a"
        wearers["2"] = "b"
        repeat(100) { definitions["pending-$it"] = "$it" }
        assertEquals(3, definitions.values.size)
        assertEquals("worn a", definitions["a"])
        assertEquals("worn b", definitions["b"])
        assertEquals("99", definitions["pending-99"])
        assertNull(definitions["pending-98"])
    }

    @Test
    fun aDefinitionCanArriveBeforeItsWearer() {
        val wearers = RecentWearers(max = 2)
        val definitions = RecentDefinitions<String>(max = 3, referenced = wearers::cosmeticIds)
        definitions["a"] = "badge"
        wearers["1"] = "a"
        assertEquals("badge", definitions[wearers["1"]!!])
        wearers.remove("1", "a")
        repeat(3) { definitions["b$it"] = "$it" }
        assertNull(definitions["a"])
    }

    @Test
    fun wearerEvictionLetsOldDefinitionsMakeRoomToo() {
        val wearers = RecentWearers(max = 1)
        val definitions = RecentDefinitions<String>(max = 2, referenced = wearers::cosmeticIds)
        definitions["a"] = "old"
        wearers["1"] = "a"
        definitions["b"] = "current"
        wearers["2"] = "b"
        definitions["c"] = "pending"
        assertNull(definitions["a"])
        assertEquals(listOf("current", "pending"), definitions.values)
    }
}
