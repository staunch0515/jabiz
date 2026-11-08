package com.jabiz.gradle

import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * Splitting a module's test classes into shards that run on separate CI machines (ROADMAP 17zz13): with the project
 * property `jabiz.test.shard=<i>/<n>` every test task runs the classes of shard i of n only, and the n shards together
 * run every class exactly once.
 *
 * Classes listed in the module's `test-timings.txt` (one `<class> <seconds>` per line, written by
 * `tools/ci/test-timings.py` from a run's results) are dealt out longest first to the shard with the least time so far;
 * each class counts [OVERHEAD] seconds more when it takes over a second (a Spring context started and closed outside
 * the class's own time). Classes not listed (new ones) go by a hash of their name. The assignment depends only on the
 * file and the class names, so every shard computes the same one. Nested classes go with their outer class.
 */
object TestShards {

    const val PROPERTY = "jabiz.test.shard"
    const val TIMINGS = "test-timings.txt"

    /** Seconds a class that starts a Spring context spends outside its reported time. */
    const val OVERHEAD = 5.0

    private const val MAX_SHARDS = 20
    private val FORM = Regex("""(\d+)/(\d+)""")

    /** Shard [index] (1-based) of [count]. */
    data class Shard(val index: Int, val count: Int)

    /** The shard a property value names; null for none (every class runs). */
    fun parse(value: String?): Shard? {
        if (value.isNullOrBlank()) {
            return null
        }
        val match = FORM.matchEntire(value.trim())
            ?: throw GradleException("$PROPERTY must be written <i>/<n>, such as 1/3: $value")
        val index = match.groupValues[1].toInt()
        val count = match.groupValues[2].toInt()
        if (count < 1 || count > MAX_SHARDS || index < 1 || index > count) {
            throw GradleException("$PROPERTY $value: <n> from 1 to $MAX_SHARDS and <i> from 1 to <n>")
        }
        return Shard(index, count)
    }

    /** The timings of [file] by class name; empty when there is no file. Blank lines and `#` comments are skipped. */
    fun readTimings(file: File): Map<String, Double> {
        if (!file.isFile) {
            return emptyMap()
        }
        val timings = LinkedHashMap<String, Double>()
        file.readLines().forEachIndexed { number, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) {
                return@forEachIndexed
            }
            val parts = line.split(Regex("""\s+"""))
            val seconds = parts.getOrNull(1)?.toDoubleOrNull()
            if (parts.size != 2 || seconds == null || seconds < 0) {
                throw GradleException("$file line ${number + 1}: expected '<class> <seconds>': $raw")
            }
            timings[parts[0]] = seconds
        }
        return timings
    }

    /** Shard (1-based) of every timed class: longest first, each to the shard with the least time so far. */
    fun assign(timings: Map<String, Double>, count: Int): Map<String, Int> {
        val load = DoubleArray(count)
        val assigned = LinkedHashMap<String, Int>()
        timings.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { weight(it.value) }.thenBy { it.key })
            .forEach { (name, seconds) ->
                val shard = load.indices.minByOrNull { load[it] }!!
                load[shard] += weight(seconds)
                assigned[name] = shard + 1
            }
        return assigned
    }

    private fun weight(seconds: Double) = if (seconds > 1.0) seconds + OVERHEAD else seconds

    /** The shard of [className]: its assigned one, or by its name's hash when it has none. */
    fun shardOf(className: String, assigned: Map<String, Int>, count: Int): Int =
        assigned[className] ?: (Math.floorMod(className.hashCode(), count) + 1)

    /** The class a compiled file belongs to (`a/b/C$D.class` → `a.b.C`); null for anything but a class file. */
    fun outerClass(relativePath: String): String? {
        if (!relativePath.endsWith(".class")) {
            return null
        }
        return relativePath.removeSuffix(".class").substringBefore('$').replace('/', '.').replace('\\', '.')
    }

    /** Limits [test] to the classes of [shard], dealt out by the timings in [timings]. */
    fun configure(test: Test, shard: Shard, timings: File) {
        test.inputs.property(PROPERTY, "${shard.index}/${shard.count}")
        test.inputs.files(timings).optional().withPropertyName("testTimings")
        val assigned by lazy { assign(readTimings(timings), shard.count) }
        test.exclude { element ->
            val name = if (element.isDirectory) null else outerClass(element.relativePath.pathString)
            name != null && shardOf(name, assigned, shard.count) != shard.index
        }
    }
}
