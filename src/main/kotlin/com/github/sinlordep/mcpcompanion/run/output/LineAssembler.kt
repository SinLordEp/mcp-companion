package com.github.sinlordep.mcpcompanion.run.output

/**
 * Turns the chunks one stream delivers into complete lines, the way a terminal shows them: `\n` and `\r\n` end a
 * line; a lone `\r` returns to the start of the line, so the text after it replaces the line (progress bars); a line
 * longer than [maxLineChars] is split. ANSI escape sequences are removed when a line is complete, so a sequence split
 * across chunks is still recognised. Not thread-safe; [ConsoleBuffer] serialises access.
 */
internal class LineAssembler(private val maxLineChars: Int) {

    private val pending = StringBuilder()
    private var carriageReturn = false

    /** Feeds [chunk]; returns the lines it completed, in order. */
    fun feed(chunk: CharSequence): List<String> {
        val lines = ArrayList<String>(0)
        for (char in chunk) {
            when {
                char == '\n' -> {
                    carriageReturn = false
                    lines += take()
                }

                char == '\r' -> carriageReturn = true

                else -> {
                    if (carriageReturn) {
                        carriageReturn = false
                        pending.setLength(0)
                    }
                    pending.append(char)
                    if (pending.length >= maxLineChars) lines += take()
                }
            }
        }
        return lines
    }

    /** The unfinished line, ANSI removed, or null when there is none. */
    fun partial(): String? = if (pending.isEmpty()) null else AnsiCodes.strip(pending)

    /** Completes the unfinished line, if any (the stream ended). */
    fun flush(): String? = if (pending.isEmpty()) null else take()

    private fun take(): String = AnsiCodes.strip(pending).also { pending.setLength(0) }
}

/** ANSI/VT escape sequences as terminals and coloured process output use them. */
internal object AnsiCodes {
    // CSI (ESC [ ... final byte), OSC (ESC ] ... BEL or ESC \), and the remaining two-character escapes.
    private val SEQUENCE =
        Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]")

    fun strip(text: CharSequence): String =
        if (text.indexOf('\u001B') < 0) text.toString() else SEQUENCE.replace(text, "")
}
