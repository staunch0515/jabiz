package com.jabiz.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Splitting test classes into CI shards (ROADMAP 17zz13). */
class TestShardsTest {

    @Test
    fun parsesTheProperty() {
        assertNull(TestShards.parse(null))
        assertNull(TestShards.parse(" "))
        assertEquals(TestShards.Shard(2, 3), TestShards.parse("2/3"))
        assertEquals(TestShards.Shard(1, 1), TestShards.parse(" 1/1 "))
        for (wrong in listOf("3", "0/3", "4/3", "1/0", "1/21", "a/b", "1/3/5")) {
            assertThrows<GradleException>(wrong) { TestShards.parse(wrong) }
        }
    }

    @Test
    fun readsTimingsSkippingCommentsAndRefusesMalformedLines(@TempDir dir: File) {
        assertTrue(TestShards.readTimings(File(dir, "missing.txt")).isEmpty())
        val file = File(dir, "test-timings.txt")
        file.writeText("# times\n\na.B 12.5\na.C 0.2 # quick\n")
        assertEquals(mapOf("a.B" to 12.5, "a.C" to 0.2), TestShards.readTimings(file))
        file.writeText("a.B twelve\n")
        assertThrows<GradleException> { TestShards.readTimings(file) }
        file.writeText("a.B 1 2\n")
        assertThrows<GradleException> { TestShards.readTimings(file) }
    }

    @Test
    fun dealsOutLongestFirstToTheLeastLoadedShard() {
        val timings = mapOf("A" to 100.0, "B" to 60.0, "C" to 50.0, "D" to 40.0, "E" to 0.1, "F" to 0.1)
        val assigned = TestShards.assign(timings, 2)
        // With the overhead: A 105 | B 65; C to shard 2 (120); D to shard 1 (150); E and F to shard 2.
        assertEquals(mapOf("A" to 1, "B" to 2, "C" to 2, "D" to 1, "E" to 2, "F" to 2), assigned)
        // The same input, the same assignment, whatever the order of the file.
        assertEquals(assigned, TestShards.assign(timings.entries.reversed().associate { it.toPair() }, 2))
        assertEquals(setOf(1), TestShards.assign(timings, 1).values.toSet())
    }

    @Test
    fun everyClassIsInExactlyOneShard() {
        val timings = (1..300).associate { "c.T$it" to (it % 37).toDouble() }
        val assigned = TestShards.assign(timings, 3)
        val names = timings.keys + (1..50).map { "c.New$it" }
        for (name in names) {
            val shards = (1..3).filter { TestShards.shardOf(name, assigned, 3) == it }
            assertEquals(1, shards.size, name)
        }
        // Roughly even by time.
        val loads = (1..3).map { shard ->
            timings.filterKeys { assigned[it] == shard }.values.sumOf { if (it > 1.0) it + TestShards.OVERHEAD else it }
        }
        assertTrue(loads.max() - loads.min() <= 41, loads.toString())
    }

    @Test
    fun nestedClassesGoWithTheirOuterClass() {
        assertEquals("a.b.C", TestShards.outerClass("a/b/C.class"))
        assertEquals("a.b.C", TestShards.outerClass("a/b/C\$Inner.class"))
        assertEquals("a.b.C", TestShards.outerClass("a/b/C\$1.class"))
        assertNull(TestShards.outerClass("a/b/data.json"))
    }
}
