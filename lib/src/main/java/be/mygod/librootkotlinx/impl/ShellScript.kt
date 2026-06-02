package be.mygod.librootkotlinx.impl

internal object ShellScript {
    /**
     * Single-quote escaping compatible with libsu ShellUtils.escapedString, kept local because libsu is no longer a
     * dependency.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/core/src/main/java/com/topjohnwu/superuser/ShellUtils.java#L124-L137
     */
    fun quote(value: String): String {
        val result = StringBuilder("'")
        value.forEach { if (it == '\'') result.append("'\\''") else result.append(it) }
        return result.append('\'').toString()
    }
}
