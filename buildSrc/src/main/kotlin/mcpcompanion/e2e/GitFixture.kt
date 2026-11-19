package mcpcompanion.e2e

import org.gradle.api.GradleException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Turns the throwaway copy of the JPS fixture into a Git repository for `mc_vcs_history` (`-PmcpE2eVcs=git`): an
 * initial commit of everything, then two commits that change `src/history/Ledger.java`, all by the fictitious
 * [AUTHOR] with fixed dates, and the IDE's VCS mapping in `.idea/vcs.xml`. Only the copy under `build/` is touched;
 * the user's Git configuration is not read (no global or system config), so nothing personal ends up in the commits.
 */
object GitFixture {

    const val AUTHOR = "Test Author"
    const val EMAIL = "test@example.invalid"

    fun initialise(project: File) {
        git(project, DATES[0], "init", "-q", "-b", "main")
        git(project, DATES[0], "add", "-A")
        git(project, DATES[0], "commit", "-q", "-m", "Initial fixture")
        val ledger = project.resolve("src/history/Ledger.java")
        ledger.writeText(ledger.readText().replace(DEPOSIT, DEPOSIT_CHECKED))
        git(project, DATES[1], "commit", "-q", "-a", "-m", "Refuse negative deposits\n\nA deposit below zero is a bug.")
        ledger.writeText(ledger.readText().replace(BALANCE, BALANCE + WITHDRAW))
        git(project, DATES[2], "commit", "-q", "-a", "-m", "Add withdraw")
        project.resolve(".idea/vcs.xml").writeText(VCS_MAPPING)
    }

    private fun git(directory: File, date: String, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("GIT_CONFIG_GLOBAL", "/dev/null")
                    put("GIT_CONFIG_NOSYSTEM", "1")
                    put("GIT_AUTHOR_NAME", AUTHOR)
                    put("GIT_AUTHOR_EMAIL", EMAIL)
                    put("GIT_COMMITTER_NAME", AUTHOR)
                    put("GIT_COMMITTER_EMAIL", EMAIL)
                    put("GIT_AUTHOR_DATE", date)
                    put("GIT_COMMITTER_DATE", date)
                }
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw GradleException("git ${arguments.joinToString(" ")} failed in $directory: $output")
        }
    }

    private val DATES = listOf("2026-01-05T10:00:00+00:00", "2026-02-10T11:30:00+00:00", "2026-03-15T09:15:00+00:00")

    private const val DEPOSIT = "        balance += amount;\n"
    private const val DEPOSIT_CHECKED = "        if (amount < 0) {\n" +
        "            throw new IllegalArgumentException(\"negative\");\n" +
        "        }\n" + DEPOSIT
    private const val BALANCE = "    public int balance() {\n        return balance;\n    }\n"
    private const val WITHDRAW = "\n    public void withdraw(int amount) {\n        balance -= amount;\n    }\n"

    private val VCS_MAPPING = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="4">
          <component name="VcsDirectoryMappings">
            <mapping directory="" vcs="Git" />
          </component>
        </project>
    """.trimIndent() + "\n"
}
