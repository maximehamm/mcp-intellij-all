package io.nimbly.mcpcompanion

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Real integration tests for the VCS tools (vcs_stage_files, vcs_commit, vcs_stash,
 * vcs_push, vcs_pull) and the git read helpers (log, branch, status).
 *
 * Each test uses a real temporary Git repository on disk.
 * git4idea classloader is loaded via the same reflection path as production code.
 * When PluginManagerCore has not loaded Git4Idea (headless environment), we fall back
 * to the test classpath where vcs-git.jar is added as testRuntimeOnly.
 *
 * The test project provided by BasePlatformTestCase is used as the IntelliJ Project
 * context (required by GitLineHandler), while the actual git root is the temp repo dir.
 *
 * Remote operations (push/pull) use a local bare repository as the remote.
 */
class VcsOperationsTest : BasePlatformTestCase() {

    private val toolset = McpCompanionVcsToolset()

    private lateinit var repoDir: File
    private lateinit var bareDir: File

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    override fun setUp() {
        super.setUp()

        repoDir = createTempDir("vcs-test-repo")
        bareDir = createTempDir("vcs-test-bare")

        // Init working repo and bare remote
        git(repoDir, "init", "-b", "main")
        git(repoDir, "config", "user.email", "test@test.com")
        git(repoDir, "config", "user.name", "Test User")
        git(repoDir, "config", "commit.gpgsign", "false")
        git(bareDir, "init", "--bare", "-b", "main")

        // Initial commit so the repo is not empty
        File(repoDir, "README.md").writeText("# Test\n")
        git(repoDir, "add", "README.md")
        git(repoDir, "commit", "-m", "initial commit")

        // Wire up remote
        git(repoDir, "remote", "add", "origin", "file://${bareDir.absolutePath}")
        git(repoDir, "push", "-u", "origin", "main")

        // Make VFS aware of the repo dir
        LocalFileSystem.getInstance().refreshAndFindFileByPath(repoDir.absolutePath)

        // Pre-configure the git executable path so git4idea doesn't run its
        // executable-detection subprocess (which can be flaky in headless mode).
        val cl = toolset.git4ideaLoader()
        if (cl != null) {
            runCatching {
                val gitPath = ProcessBuilder("which", "git").start()
                    .inputStream.readBytes().toString(Charsets.UTF_8).trim()
                val settings = cl.loadClass("git4idea.config.GitVcsApplicationSettings")
                    .getMethod("getInstance").invoke(null)
                settings.javaClass.getMethod("setPathToGit", String::class.java)
                    .invoke(settings, gitPath)
            }
        }
    }

    override fun tearDown() {
        runCatching { repoDir.deleteRecursively() }
        runCatching { bareDir.deleteRecursively() }
        super.tearDown()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Runs a git command in [dir] and returns stdout. Asserts exit code == 0. */
    private fun git(dir: File, vararg args: String, allowFailure: Boolean = false): String {
        val pb = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        val code = proc.waitFor()
        if (!allowFailure) assertEquals("git ${args.joinToString(" ")} failed ($code):\n$out", 0, code)
        return out
    }

    /** Returns the VirtualFile for [repoDir], refreshing VFS if needed. */
    private fun repoVFile() =
        LocalFileSystem.getInstance().refreshAndFindFileByPath(repoDir.absolutePath)
            ?: error("Cannot find VirtualFile for ${repoDir.absolutePath}")

    /**
     * Returns the git4idea classloader via PluginManagerCore.
     * Works when bundledPlugin("Git4Idea") is declared in build.gradle.kts,
     * which registers the git4idea plugin in the headless test environment.
     */
    private fun git4ideaLoader(): ClassLoader? = toolset.git4ideaLoader()

    /**
     * Runs gitExec via the toolset and returns (ok, output).
     * Fails the test if git4idea is not available at all.
     */
    private fun execPair(command: String, vararg params: String): Pair<Boolean, String> {
        val cl = git4ideaLoader() ?: error("git4idea classloader not available")
        val result = toolset.gitExec(cl, project, repoVFile(), command, *params)
        println("  git $command ${params.joinToString(" ")} → ok=${result.first} out='${result.second}'")
        return result
    }

    /** Asserts success and returns the output. */
    private fun exec(command: String, vararg params: String): String {
        val (ok, out) = execPair(command, *params)
        return if (ok) out else error("gitExec $command failed: $out")
    }

    // ── vcs_stage_files ───────────────────────────────────────────────────────

    fun `test stage a new file`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        File(repoDir, "Hello.java").writeText("class Hello {}")
        LocalFileSystem.getInstance().refreshAndFindFileByPath("${repoDir.absolutePath}/Hello.java")

        exec("ADD", "--", "${repoDir.absolutePath}/Hello.java")

        val status = git(repoDir, "status", "--short")
        println("  git status = '$status'")
        assertTrue("Hello.java should be staged (A )", status.contains("A"))
        assertTrue("Hello.java should appear in status", status.contains("Hello.java"))
    }

    fun `test unstage a staged file`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        // Stage a file first with raw git
        File(repoDir, "Staged.java").writeText("class Staged {}")
        git(repoDir, "add", "Staged.java")
        val statusBefore = git(repoDir, "status", "--short")
        assertTrue("File should be staged before unstage", statusBefore.contains("A"))

        exec("RESET", "HEAD", "--", "${repoDir.absolutePath}/Staged.java")

        val statusAfter = git(repoDir, "status", "--short")
        println("  status after unstage = '$statusAfter'")
        assertFalse("File should NOT be staged after unstage", statusAfter.startsWith("A"))
    }

    // ── vcs_commit ────────────────────────────────────────────────────────────

    fun `test commit staged changes`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        File(repoDir, "Commit.java").writeText("class Commit {}")
        git(repoDir, "add", "Commit.java")

        exec("COMMIT", "-m", "add Commit.java")

        val log = git(repoDir, "log", "--oneline", "-1")
        println("  git log = '$log'")
        assertTrue("Commit message should appear in log", log.contains("add Commit.java"))
    }

    fun `test amend last commit`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        // Create a commit to amend
        File(repoDir, "ToAmend.java").writeText("class ToAmend {}")
        git(repoDir, "add", "ToAmend.java")
        git(repoDir, "commit", "-m", "original message")

        // Amend with a new message
        exec("COMMIT", "--amend", "-m", "amended message")

        val log = git(repoDir, "log", "--oneline", "-1")
        println("  git log after amend = '$log'")
        assertTrue("Amended message should be in log", log.contains("amended message"))
        assertFalse("Original message should be gone", log.contains("original message"))
    }

    // ── vcs_stash ─────────────────────────────────────────────────────────────

    fun `test stash push saves working tree changes`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        File(repoDir, "Stash.java").writeText("class Stash { int x = 1; }")
        git(repoDir, "add", "Stash.java")
        git(repoDir, "commit", "-m", "add Stash.java")

        // Modify the file (working tree change)
        File(repoDir, "Stash.java").writeText("class Stash { int x = 99; }")

        exec("STASH", "push", "-m", "stash-test")

        val stashList = exec("STASH", "list")
        println("  stash list = '$stashList'")
        assertTrue("Stash should be listed", stashList.contains("stash-test"))

        val content = File(repoDir, "Stash.java").readText()
        println("  file content after stash = '$content'")
        assertFalse("Working tree should be clean after stash", content.contains("99"))
    }

    fun `test stash pop restores working tree changes`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        File(repoDir, "Pop.java").writeText("class Pop { int x = 1; }")
        git(repoDir, "add", "Pop.java")
        git(repoDir, "commit", "-m", "add Pop.java")

        File(repoDir, "Pop.java").writeText("class Pop { int x = 42; }")
        git(repoDir, "stash", "push", "-m", "pop-test")

        exec("STASH", "pop", "stash@{0}")

        val content = File(repoDir, "Pop.java").readText()
        println("  file content after stash pop = '$content'")
        assertTrue("Modified content should be restored after pop", content.contains("42"))
    }

    fun `test stash list is empty on clean repo`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        val result = exec("STASH", "list")
        println("  stash list on clean repo = '$result'")
        assertTrue("Stash list should be empty on clean repo", result.isBlank())
    }

    // ── vcs_push / vcs_pull ───────────────────────────────────────────────────

    fun `test push to local bare remote`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        File(repoDir, "PushMe.java").writeText("class PushMe {}")
        git(repoDir, "add", "PushMe.java")
        git(repoDir, "commit", "-m", "add PushMe.java")

        exec("PUSH", "origin", "main")

        // Verify the bare remote received the commit
        val bareLog = git(bareDir, "log", "--oneline", "-1")
        println("  bare repo log after push = '$bareLog'")
        assertTrue("Pushed commit should be in bare remote", bareLog.contains("add PushMe.java"))
    }

    fun `test pull from local bare remote`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        // Clone the bare repo into a third dir, make a commit, push it to bare
        val cloneDir = createTempDir("vcs-test-clone")
        try {
            git(cloneDir, "clone", "file://${bareDir.absolutePath}", ".")
            git(cloneDir, "config", "user.email", "test@test.com")
            git(cloneDir, "config", "user.name", "Test User")
            git(cloneDir, "config", "commit.gpgsign", "false")
            File(cloneDir, "FromRemote.java").writeText("class FromRemote {}")
            git(cloneDir, "add", "FromRemote.java")
            git(cloneDir, "commit", "-m", "add FromRemote.java")
            git(cloneDir, "push", "origin", "main")
        } finally {
            cloneDir.deleteRecursively()
        }

        // Now pull into our working repo
        exec("PULL", "origin", "main")

        val log = git(repoDir, "log", "--oneline", "-2")
        println("  log after pull = '$log'")
        assertTrue("Pulled commit should appear in log", log.contains("add FromRemote.java"))
        assertTrue("FromRemote.java should exist after pull",
            File(repoDir, "FromRemote.java").exists())
    }

    // ── get_vcs_log (via gitExec LOG) ─────────────────────────────────────────
    //
    // Tests the same git4idea reflection path used by the get_vcs_log tool.
    // In a headless test the GitRepositoryManager won't find our temp repo,
    // so we call gitExec directly with the repo root VirtualFile.

    fun `test get_vcs_log - log shows all commits in order`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Make 3 additional commits on top of the initial one
        File(repoDir, "Alpha.java").writeText("class Alpha {}")
        git(repoDir, "add", "Alpha.java")
        git(repoDir, "commit", "-m", "add Alpha.java")

        File(repoDir, "Beta.java").writeText("class Beta {}")
        git(repoDir, "add", "Beta.java")
        git(repoDir, "commit", "-m", "add Beta.java")

        File(repoDir, "Gamma.java").writeText("class Gamma {}")
        git(repoDir, "add", "Gamma.java")
        git(repoDir, "commit", "-m", "add Gamma.java")

        val (ok, out) = execPair("LOG", "--oneline", "-5")
        println("  git log (5) =\n$out")

        assertTrue("LOG command should succeed", ok)
        assertTrue("Log should contain 'add Alpha.java'", out.contains("add Alpha.java"))
        assertTrue("Log should contain 'add Beta.java'",  out.contains("add Beta.java"))
        assertTrue("Log should contain 'add Gamma.java'", out.contains("add Gamma.java"))
        assertTrue("Log should contain 'initial commit'", out.contains("initial commit"))

        // Most recent commit must appear first (log is reverse-chronological)
        val idxGamma = out.indexOf("add Gamma.java")
        val idxAlpha = out.indexOf("add Alpha.java")
        assertTrue("Gamma (newest) must appear before Alpha (oldest)", idxGamma < idxAlpha)
    }

    fun `test get_vcs_log - log filtered by file`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        File(repoDir, "Foo.java").writeText("class Foo {}")
        git(repoDir, "add", "Foo.java")
        git(repoDir, "commit", "-m", "add Foo.java")

        File(repoDir, "Bar.java").writeText("class Bar {}")
        git(repoDir, "add", "Bar.java")
        git(repoDir, "commit", "-m", "add Bar.java")

        val (ok, out) = execPair("LOG", "--oneline", "--", "Foo.java")
        println("  git log -- Foo.java = '$out'")
        assertTrue("LOG --  Foo.java should succeed", ok)
        assertTrue("Should contain Foo commit", out.contains("add Foo.java"))
        assertFalse("Should NOT contain Bar commit (different file)", out.contains("add Bar.java"))
    }

    // ── get_vcs_branch (via gitExec BRANCH) ───────────────────────────────────
    //
    // Tests the git4idea BRANCH command reflection, which is the core of get_vcs_branch.

    fun `test get_vcs_branch - current branch is main`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        val (ok, out) = execPair("BRANCH", "--show-current")
        println("  git branch --show-current = '$out'")
        assertTrue("BRANCH command should succeed", ok)
        assertEquals("Current branch should be 'main'", "main", out.trim())
    }

    fun `test get_vcs_branch - branch list after creating new branch`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Create a feature branch via raw git
        git(repoDir, "branch", "feature/test-branch")

        val (ok, out) = execPair("BRANCH")
        println("  git branch = '$out'")
        assertTrue("BRANCH command should succeed", ok)
        assertTrue("Branch list should contain 'main'",                out.contains("main"))
        assertTrue("Branch list should contain 'feature/test-branch'", out.contains("feature/test-branch"))
    }

    // ── get_vcs_changes (via gitExec STATUS) ──────────────────────────────────
    //
    // Tests the ability to detect working tree changes via the git4idea reflection path.
    // The production get_vcs_changes tool uses IntelliJ's ChangeListManager (IDE-level);
    // here we verify the underlying git STATUS command behaves as expected.

    fun `test get_vcs_changes - detect uncommitted new file`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Create a new untracked file
        File(repoDir, "NewFile.java").writeText("class NewFile {}")

        val (ok, out) = execPair("STATUS", "--short")
        println("  git status --short = '$out'")
        assertTrue("STATUS should succeed", ok)
        assertTrue("Status should show untracked NewFile.java", out.contains("NewFile.java"))
        assertTrue("Untracked file should have '??' marker", out.contains("??"))
    }

    fun `test get_vcs_changes - detect modification and staged file`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Modify README.md (already committed) — unstaged change
        File(repoDir, "README.md").appendText("## Section\n")

        // Create and stage a brand-new file
        File(repoDir, "Staged.java").writeText("class Staged {}")
        git(repoDir, "add", "Staged.java")

        val (ok, out) = execPair("STATUS", "--short")
        println("  git status --short = '$out'")
        assertTrue("STATUS should succeed", ok)
        assertTrue("Modified README.md should appear", out.contains("README.md"))
        assertTrue("Staged Staged.java should appear with 'A'", out.contains("A") && out.contains("Staged.java"))
    }

    fun `test get_vcs_changes - no changes on clean working tree`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        val (ok, out) = execPair("STATUS", "--short")
        println("  git status --short (clean) = '$out'")
        assertTrue("STATUS should succeed", ok)
        assertTrue("Status should be empty on clean working tree", out.isBlank())
    }

    // ── vcs_create_branch ─────────────────────────────────────────────────────

    fun `test vcs_create_branch - creates branch without checkout`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        exec("BRANCH", "feature/new-tool")

        val branches = git(repoDir, "branch")
        println("  git branch = '$branches'")
        assertTrue("New branch should exist", branches.contains("feature/new-tool"))

        val current = git(repoDir, "branch", "--show-current")
        println("  current branch = '$current'")
        assertEquals("Should still be on 'main' (no checkout)", "main", current.trim())
    }

    fun `test vcs_create_branch - creates and switches to new branch`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        exec("CHECKOUT", "-b", "feature/switched")

        val current = git(repoDir, "branch", "--show-current")
        println("  current branch after checkout -b = '$current'")
        assertEquals("Should be on the new branch", "feature/switched", current.trim())

        val branches = git(repoDir, "branch")
        assertTrue("New branch should appear in branch list", branches.contains("feature/switched"))
    }

    fun `test vcs_create_branch - creates branch from specific base`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Make a second commit so HEAD~1 is the initial commit
        File(repoDir, "Extra.java").writeText("class Extra {}")
        git(repoDir, "add", "Extra.java")
        git(repoDir, "commit", "-m", "add Extra.java")

        // Create a branch rooted at HEAD~1 (the initial commit), without switching
        exec("BRANCH", "based-on-initial", "HEAD~1")

        // Verify the new branch points to the initial commit (Extra.java shouldn't be there)
        val logOnBranch = git(repoDir, "log", "--oneline", "based-on-initial")
        println("  log on based-on-initial = '$logOnBranch'")
        assertFalse("Extra.java commit should NOT be in the based-on-initial branch",
            logOnBranch.contains("add Extra.java"))
        assertTrue("Initial commit should be in the based-on-initial branch",
            logOnBranch.contains("initial commit"))
    }

    // ── vcs_checkout_branch ───────────────────────────────────────────────────

    fun `test vcs_checkout_branch - switches to existing branch`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Create the target branch with raw git (no switch)
        git(repoDir, "branch", "target-branch")

        exec("CHECKOUT", "target-branch")

        val current = git(repoDir, "branch", "--show-current")
        println("  current branch after checkout = '$current'")
        assertEquals("Should now be on 'target-branch'", "target-branch", current.trim())
    }

    fun `test vcs_checkout_branch - switches back to main`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Start on a feature branch
        git(repoDir, "checkout", "-b", "feature/temp")
        val onFeature = git(repoDir, "branch", "--show-current")
        assertEquals("Should be on feature/temp", "feature/temp", onFeature.trim())

        // Switch back to main via the tool
        exec("CHECKOUT", "main")

        val current = git(repoDir, "branch", "--show-current")
        println("  current branch after checkout main = '$current'")
        assertEquals("Should be back on 'main'", "main", current.trim())
    }

    // ── vcs_fetch ─────────────────────────────────────────────────────────────

    fun `test vcs_fetch - updates remote tracking refs`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Push a new commit from a clone to the bare remote
        val cloneDir = createTempDir("vcs-test-fetch-clone")
        try {
            git(cloneDir, "clone", "file://${bareDir.absolutePath}", ".")
            git(cloneDir, "config", "user.email", "test@test.com")
            git(cloneDir, "config", "user.name", "Test User")
            git(cloneDir, "config", "commit.gpgsign", "false")
            File(cloneDir, "FetchMe.java").writeText("class FetchMe {}")
            git(cloneDir, "add", "FetchMe.java")
            git(cloneDir, "commit", "-m", "add FetchMe.java")
            git(cloneDir, "push", "origin", "main")
        } finally {
            cloneDir.deleteRecursively()
        }

        // Before fetch, origin/main is behind
        val logBefore = git(repoDir, "log", "--oneline", "origin/main", allowFailure = true)
        assertFalse("FetchMe commit should not be in origin/main yet", logBefore.contains("add FetchMe.java"))

        exec("FETCH", "origin")

        // After fetch, origin/main should know about the new commit
        val logAfter = git(repoDir, "log", "--oneline", "origin/main")
        println("  origin/main after fetch = '$logAfter'")
        assertTrue("FetchMe commit should appear in origin/main after fetch", logAfter.contains("add FetchMe.java"))
        // Working tree should be untouched (no merge)
        assertFalse("FetchMe.java should NOT exist in working tree (fetch only)", File(repoDir, "FetchMe.java").exists())
    }

    fun `test vcs_fetch with prune - removes deleted remote branch refs`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Create and push a branch to the bare remote
        git(repoDir, "checkout", "-b", "to-be-deleted")
        git(repoDir, "push", "origin", "to-be-deleted")
        exec("FETCH")
        val branchesBefore = exec("BRANCH", "-r")
        println("  remote branches before delete = '$branchesBefore'")
        assertTrue("Remote branch should exist before delete", branchesBefore.contains("to-be-deleted"))

        // Delete it on the remote (bare repo)
        git(bareDir, "branch", "-D", "to-be-deleted")
        git(repoDir, "checkout", "main")

        // Fetch with prune
        exec("FETCH", "--prune", "origin")

        val branchesAfter = exec("BRANCH", "-r")
        println("  remote branches after prune = '$branchesAfter'")
        assertFalse("Deleted remote branch should be pruned", branchesAfter.contains("to-be-deleted"))
    }

    // ── vcs_merge_branch ──────────────────────────────────────────────────────

    fun `test vcs_merge_branch - fast-forward merge`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Create feature branch with a new file
        git(repoDir, "checkout", "-b", "feature/merge-test")
        File(repoDir, "Merged.java").writeText("class Merged {}")
        git(repoDir, "add", "Merged.java")
        git(repoDir, "commit", "-m", "add Merged.java")
        git(repoDir, "checkout", "main")

        exec("MERGE", "feature/merge-test")

        val log = git(repoDir, "log", "--oneline", "-2")
        println("  log after merge = '$log'")
        assertTrue("Merge commit should appear in log", log.contains("add Merged.java"))
        assertTrue("Merged.java should exist after merge", File(repoDir, "Merged.java").exists())
    }

    fun `test vcs_merge_branch - no-ff creates a merge commit`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        git(repoDir, "checkout", "-b", "feature/no-ff")
        File(repoDir, "NoFf.java").writeText("class NoFf {}")
        git(repoDir, "add", "NoFf.java")
        git(repoDir, "commit", "-m", "add NoFf.java")
        git(repoDir, "checkout", "main")

        exec("MERGE", "--no-ff", "-m", "Merge feature/no-ff", "feature/no-ff")

        val log = git(repoDir, "log", "--oneline", "-3")
        println("  log after no-ff merge = '$log'")
        assertTrue("Merge commit message should appear", log.contains("Merge feature/no-ff"))
        assertTrue("Feature commit should also appear", log.contains("add NoFf.java"))
    }

    // ── vcs_rebase ────────────────────────────────────────────────────────────

    fun `test vcs_rebase - rebases feature branch onto main`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Add a commit on main after the feature branch diverged
        git(repoDir, "checkout", "-b", "feature/rebase-test")
        File(repoDir, "Feature.java").writeText("class Feature {}")
        git(repoDir, "add", "Feature.java")
        git(repoDir, "commit", "-m", "add Feature.java")

        // Add a commit on main too (so rebase has something to do)
        git(repoDir, "checkout", "main")
        File(repoDir, "Main2.java").writeText("class Main2 {}")
        git(repoDir, "add", "Main2.java")
        git(repoDir, "commit", "-m", "add Main2.java")

        // Switch back to feature and rebase
        git(repoDir, "checkout", "feature/rebase-test")

        exec("REBASE", "main")

        val log = git(repoDir, "log", "--oneline", "-3")
        println("  log after rebase = '$log'")
        // After rebase, feature commit should be on top of main's new commit
        assertTrue("Feature commit should be in log", log.contains("add Feature.java"))
        assertTrue("Main2 commit should be in log (rebase base)", log.contains("add Main2.java"))
        // Feature commit should appear BEFORE Main2 in the log (it's on top)
        assertTrue("Feature commit should be more recent than Main2",
            log.indexOf("add Feature.java") < log.indexOf("add Main2.java"))
    }

    // ── get_vcs_conflicts ─────────────────────────────────────────────────────

    fun `test get_vcs_conflicts - detects conflicts after failed merge`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Create conflicting changes on two branches
        File(repoDir, "Conflict.java").writeText("class Conflict { int x = 1; }")
        git(repoDir, "add", "Conflict.java")
        git(repoDir, "commit", "-m", "add Conflict.java")

        git(repoDir, "checkout", "-b", "feature/conflict")
        File(repoDir, "Conflict.java").writeText("class Conflict { int x = 99; }")
        git(repoDir, "add", "Conflict.java")
        git(repoDir, "commit", "-m", "change x to 99")

        git(repoDir, "checkout", "main")
        File(repoDir, "Conflict.java").writeText("class Conflict { int x = 42; }")
        git(repoDir, "add", "Conflict.java")
        git(repoDir, "commit", "-m", "change x to 42")

        // Attempt merge — will fail with conflict
        git(repoDir, "merge", "feature/conflict", allowFailure = true)

        val (ok, out) = execPair("STATUS", "--porcelain")
        println("  git status after conflict = '$out'")
        assertTrue("STATUS should succeed", ok)
        assertTrue("Conflict.java should appear as UU", out.contains("UU") && out.contains("Conflict.java"))
    }

    fun `test get_vcs_conflicts - no conflicts on clean working tree`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        val (ok, out) = execPair("STATUS", "--porcelain")
        println("  git status (clean) = '$out'")
        assertTrue("STATUS should succeed", ok)
        // No UU/AA/DD lines on clean tree
        val conflictCodes = setOf("UU", "AA", "DD", "AU", "UA", "DU", "UD")
        val hasConflicts = out.lines().any { it.length >= 2 && it.substring(0, 2) in conflictCodes }
        assertFalse("Should be no conflicts on clean tree", hasConflicts)
    }

    // ── get_vcs_file_history ──────────────────────────────────────────────────

    fun `test get_vcs_file_history - lists commits that touched a file`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Two commits touching MyClass.java, one commit touching another file
        File(repoDir, "MyClass.java").writeText("class MyClass {}")
        git(repoDir, "add", "MyClass.java")
        git(repoDir, "commit", "-m", "create MyClass")

        File(repoDir, "Other.java").writeText("class Other {}")
        git(repoDir, "add", "Other.java")
        git(repoDir, "commit", "-m", "add Other (unrelated)")

        File(repoDir, "MyClass.java").writeText("class MyClass { int x; }")
        git(repoDir, "add", "MyClass.java")
        git(repoDir, "commit", "-m", "add field x to MyClass")

        // Use the same args the get_vcs_file_history tool sends to LOG
        val (ok, out) = execPair("LOG",
            "--max-count=20",
            "--follow",
            "--pretty=format:%H\u0001%an\u0001%ae\u0001%aI\u0001%s",
            "--",
            "${repoDir.absolutePath}/MyClass.java")
        println("  log --follow MyClass.java =\n$out")
        assertTrue("LOG should succeed", ok)
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals("Should be exactly 2 commits touching MyClass.java", 2, lines.size)
        assertTrue("Most recent should mention 'add field x'", lines[0].contains("add field x to MyClass"))
        assertTrue("Oldest should mention 'create MyClass'",   lines[1].contains("create MyClass"))
        // Other.java should NOT show up
        assertFalse("Unrelated commit should not appear", out.contains("add Other"))
    }

    // ── get_vcs_diff_between_branches ─────────────────────────────────────────

    fun `test get_vcs_diff_between_branches - diff between main and feature branch`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Create a feature branch with a unique change
        git(repoDir, "checkout", "-b", "feature/diff-test")
        File(repoDir, "DiffMe.java").writeText("class DiffMe { int x = 1; }")
        git(repoDir, "add", "DiffMe.java")
        git(repoDir, "commit", "-m", "add DiffMe")

        val (ok, out) = execPair("DIFF", "--no-color", "main..feature/diff-test")
        println("  diff main..feature =\n$out")
        assertTrue("DIFF should succeed", ok)
        assertTrue("Diff should show new file DiffMe.java", out.contains("DiffMe.java"))
        assertTrue("Diff should show added line 'class DiffMe'", out.contains("+class DiffMe"))
    }

    fun `test get_vcs_diff_between_branches - stat only summary`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }
        git(repoDir, "checkout", "-b", "feature/stat-test")
        File(repoDir, "Stat.java").writeText("class Stat {}\n")
        git(repoDir, "add", "Stat.java")
        git(repoDir, "commit", "-m", "add Stat")

        val (ok, out) = execPair("DIFF", "--stat", "main..feature/stat-test")
        println("  diff --stat = '$out'")
        assertTrue("DIFF --stat should succeed", ok)
        assertTrue("Stat output should mention Stat.java", out.contains("Stat.java"))
        // --stat does NOT contain a real diff body
        assertFalse("Stat output should not contain a unified diff", out.contains("+class Stat"))
    }

    // ── vcs_show_commit ───────────────────────────────────────────────────────

    fun `test vcs_show_commit - returns commit message and diff`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        File(repoDir, "Showme.java").writeText("class Showme { int v = 7; }\n")
        git(repoDir, "add", "Showme.java")
        git(repoDir, "commit", "-m", "Add Showme demo")
        val hash = git(repoDir, "rev-parse", "HEAD").trim()

        val (ok, out) = execPair("SHOW", "--no-color", hash)
        println("  show $hash =\n$out")
        assertTrue("SHOW should succeed", ok)
        assertTrue("Output should contain the commit message", out.contains("Add Showme demo"))
        assertTrue("Output should contain the file name", out.contains("Showme.java"))
        assertTrue("Output should contain the added line", out.contains("+class Showme"))
    }

    // ── vcs_reset ─────────────────────────────────────────────────────────────

    fun `test vcs_reset - soft keeps working tree and index`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        File(repoDir, "ToReset.java").writeText("class ToReset {}")
        git(repoDir, "add", "ToReset.java")
        git(repoDir, "commit", "-m", "to be reset")
        val before = git(repoDir, "log", "--oneline", "-1")
        assertTrue("Commit must exist before reset", before.contains("to be reset"))

        exec("RESET", "--soft", "HEAD~1")

        // After --soft, ToReset.java should still exist on disk and still be staged
        assertTrue("Working tree file should still exist after --soft", File(repoDir, "ToReset.java").exists())
        val status = git(repoDir, "status", "--short")
        println("  status after soft reset = '$status'")
        assertTrue("File should still be staged (A) after --soft", status.contains("A") && status.contains("ToReset.java"))

        // The commit itself should be gone
        val log = git(repoDir, "log", "--oneline", "-1")
        assertFalse("Latest commit should no longer be 'to be reset'", log.contains("to be reset"))
    }

    fun `test vcs_reset - hard discards changes`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        File(repoDir, "Hard.java").writeText("class Hard {}")
        git(repoDir, "add", "Hard.java")
        git(repoDir, "commit", "-m", "hard reset target")

        exec("RESET", "--hard", "HEAD~1")

        // After --hard, the file should be gone from the working tree AND not in any index
        assertFalse("Working tree file should NOT exist after --hard reset", File(repoDir, "Hard.java").exists())
        val status = git(repoDir, "status", "--short")
        println("  status after hard reset = '$status'")
        assertTrue("Working tree should be clean after --hard reset", status.isBlank())
    }

    // ── vcs_revert ────────────────────────────────────────────────────────────

    fun `test vcs_revert - creates a commit that undoes the target commit`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        File(repoDir, "Revertable.java").writeText("class Revertable { int v = 1; }")
        git(repoDir, "add", "Revertable.java")
        git(repoDir, "commit", "-m", "feature to revert")
        val toRevert = git(repoDir, "rev-parse", "HEAD").trim()

        val (ok, out) = execPair("REVERT", "--no-edit", toRevert)
        println("  revert result ok=$ok out='$out'")
        assertTrue("REVERT should succeed", ok)

        // The file should be gone (since the commit added it, reverting deletes it)
        assertFalse("Reverted file should be gone from working tree", File(repoDir, "Revertable.java").exists())

        // A new revert commit should appear at the top of the log
        val log = git(repoDir, "log", "--oneline", "-2")
        println("  log after revert =\n$log")
        assertTrue("Top commit should be a Revert", log.lines().first().contains("Revert"))
    }

    // ── vcs_cherry_pick ───────────────────────────────────────────────────────

    fun `test vcs_cherry_pick - applies a commit from another branch`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Make a commit on a feature branch
        git(repoDir, "checkout", "-b", "feature/cp-source")
        File(repoDir, "Picked.java").writeText("class Picked {}")
        git(repoDir, "add", "Picked.java")
        git(repoDir, "commit", "-m", "add Picked.java on feature")
        val pickHash = git(repoDir, "rev-parse", "HEAD").trim()

        // Switch back to main — Picked.java does not exist here yet
        git(repoDir, "checkout", "main")
        assertFalse("Picked.java should not exist on main yet", File(repoDir, "Picked.java").exists())

        val (ok, out) = execPair("CHERRY_PICK", pickHash)
        println("  cherry-pick result ok=$ok out='$out'")
        assertTrue("CHERRY_PICK should succeed", ok)

        // Picked.java should now exist on main
        assertTrue("Picked.java should exist on main after cherry-pick", File(repoDir, "Picked.java").exists())
        val log = git(repoDir, "log", "--oneline", "-1")
        assertTrue("Top commit on main should mention Picked.java", log.contains("add Picked.java on feature"))
    }

    // ── vcs_delete_branch ─────────────────────────────────────────────────────

    fun `test vcs_delete_branch - deletes a local branch`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Create a branch, commit on it, then merge it back so it can be deleted with -d
        git(repoDir, "checkout", "-b", "to-delete")
        File(repoDir, "Del.java").writeText("class Del {}")
        git(repoDir, "add", "Del.java")
        git(repoDir, "commit", "-m", "add Del.java")
        git(repoDir, "checkout", "main")
        git(repoDir, "merge", "--ff-only", "to-delete")

        val branchesBefore = git(repoDir, "branch")
        assertTrue("Branch should exist before delete", branchesBefore.contains("to-delete"))

        exec("BRANCH", "-d", "to-delete")

        val branchesAfter = git(repoDir, "branch")
        println("  branches after delete = '$branchesAfter'")
        assertFalse("Deleted branch should be gone", branchesAfter.contains("to-delete"))
    }

    fun `test vcs_delete_branch - force-deletes an unmerged branch`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        git(repoDir, "checkout", "-b", "force-delete")
        File(repoDir, "Force.java").writeText("class Force {}")
        git(repoDir, "add", "Force.java")
        git(repoDir, "commit", "-m", "unmerged commit")
        git(repoDir, "checkout", "main")

        // -d would refuse (unmerged), -D should succeed
        val (ok, _) = execPair("BRANCH", "-D", "force-delete")
        assertTrue("Force delete should succeed", ok)
        val branches = git(repoDir, "branch")
        assertFalse("Branch should be gone after -D", branches.contains("force-delete"))
    }

    fun `test vcs_delete_branch - deletes a remote branch via push --delete`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Push a branch to the bare remote, then delete it via PUSH --delete
        git(repoDir, "checkout", "-b", "remote-to-delete")
        git(repoDir, "push", "origin", "remote-to-delete")
        git(repoDir, "checkout", "main")

        val refsBefore = git(bareDir, "branch")
        assertTrue("Bare repo should have the branch before delete", refsBefore.contains("remote-to-delete"))

        exec("PUSH", "origin", "--delete", "remote-to-delete")

        val refsAfter = git(bareDir, "branch")
        println("  bare branches after delete = '$refsAfter'")
        assertFalse("Bare repo should NOT have the branch after delete", refsAfter.contains("remote-to-delete"))
    }

    fun `test get_vcs_changes - after multiple commits only unstaged changes appear`() {
        if (git4ideaLoader() == null) { System.err.println("SKIP: git4idea not available"); return }

        // Three commits
        for (name in listOf("One", "Two", "Three")) {
            File(repoDir, "$name.java").writeText("class $name {}")
            git(repoDir, "add", "$name.java")
            git(repoDir, "commit", "-m", "add $name.java")
        }

        // Verify working tree is clean after the commits
        val (ok, out) = execPair("STATUS", "--short")
        println("  status after 3 commits = '$out'")
        assertTrue("STATUS should succeed", ok)
        assertTrue("Working tree should be clean after commits", out.isBlank())

        // Now make an uncommitted change
        File(repoDir, "One.java").writeText("class One { /* modified */ }")
        val (ok2, out2) = execPair("STATUS", "--short")
        println("  status after modification = '$out2'")
        assertTrue("Modified One.java should appear", out2.contains("One.java"))
        assertFalse("Two.java should NOT appear", out2.contains("Two.java"))
        assertFalse("Three.java should NOT appear", out2.contains("Three.java"))
    }
}
