/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.terminal.ui.TerminalBuffer
import me.zhanghai.android.files.terminal.ui.TerminalView
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast

/**
 * Hosts a single proot terminal session. On launch it walks the user through the setup chain —
 * Shizuku present and authorised → distro selected and installed → session created — and then
 * binds the [TerminalView] to the running session for the lifetime of the activity.
 *
 * The whole chain is linear and surfaced through a status overlay, so any failure (Shizuku
 * missing, download interrupted, exec failure) tells the user exactly what went wrong rather than
 * landing them in a blank screen.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var statusText: android.widget.TextView

    private var session: TerminalSession? = null
    private var buffer: TerminalBuffer? = null
    private var currentDistro: TerminalDistro? = null

    // Non-null when launched to run an executable (from the file list tap action) instead of an
    // interactive shell. Describes the whole run: what was detected, how it stages, and what
    // argv ultimately executes.
    private var scriptRun: ScriptRun? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.terminal_activity)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.terminal_title)

        terminalView = findViewById(R.id.terminalView)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)

        // A script run skips distro selection: /system/bin/sh needs no rootfs, and the path has
        // already been staged for shell-uid readability by the caller.
        if (intent.getBooleanExtra(EXTRA_RUN_SCRIPT, false)) {
            val scriptPath = intent.extraPath
            if (scriptPath == null) {
                showToast(R.string.script_run_failed)
                finish()
                return
            }
            val useRoot = intent.getBooleanExtra(EXTRA_RUN_AS_ROOT, false)
            val kind = intent.getSerializableExtra(EXTRA_RUN_KIND) as? ScriptRunner.ScriptKind
                ?: ScriptRunner.ScriptKind.SHELL_SCRIPT
            scriptRun = ScriptRun(scriptPath, useRoot, kind)
        }

        // Size the buffer to the view once it has a known size.
        terminalView.post { beginSessionChain() }
    }

    /** A pending scripted execution: detected kind, root escalation, and staging state. */
    private data class ScriptRun(
        val path: java8.nio.file.Path,
        val useRoot: Boolean,
        val kind: ScriptRunner.ScriptKind,
        // For ELF runs: the /data/local/tmp path once the stage pass has placed the binary.
        val stagedElfPath: String? = null
    )

    private fun beginSessionChain() {
        if (!TerminalService.isShizukuInstalled) {
            showSetup(getString(R.string.terminal_shizuku_missing), openShizuku = true)
            return
        }
        if (!TerminalService.isAvailable) {
            // Shizuku is there but we lack permission — request it.
            statusText.text = getString(R.string.terminal_requesting_permission)
            statusText.isVisible = true
            lifecycleScope.launch {
                val granted = TerminalService.ensurePermission()
                if (granted) {
                    statusText.isVisible = false
                    chooseDistroAndProceed()
                } else {
                    showSetup(getString(R.string.terminal_permission_denied), openShizuku = true)
                }
            }
            return
        }
        chooseDistroAndProceed()
    }

    private fun chooseDistroAndProceed() {
        // Script runs bypass distro selection entirely.
        val run = scriptRun
        if (run != null) {
            beginScriptRun(run)
            return
        }
        // If we already have an Alpine or Debian installed, prefer it silently. Otherwise prompt.
        val installed = TerminalDistro.values().filter { RootfsManager.isInstalled(it) }
        when {
            installed.size == 1 -> startWithDistro(installed.first())
            installed.size > 1 -> showDistroChooser { startWithDistro(it) }
            else -> showDistroChooser { startWithDistro(it) }
        }
    }

    /**
     * Dispatches a scripted run by kind. Shell scripts go straight to a PTY; ELF binaries need a
     * first PTY pass to copy them onto /data/local/tmp (the only shell-uid writable+executable
     * location) and only then a second PTY to exec them.
     */
    private fun beginScriptRun(run: ScriptRun) {
        when (run.kind) {
            ScriptRunner.ScriptKind.SHELL_SCRIPT ->
                launchScriptSession(ScriptRunner.buildShellArgv(run.path, run.useRoot))
            ScriptRunner.ScriptKind.ELF_BINARY ->
                launchElfStagePass(run)
        }
    }

    /**
     * First pass for an ELF run: place the binary on /data/local/tmp and chmod it. Runs silently
     * (no terminal view shown for this leg — output stays in the session until the real run
     * replaces the buffer), then chains into [launchElfRunPass] on success.
     */
    private fun launchElfStagePass(run: ScriptRun) {
        val rows = terminalView.visibleRows()
        val cols = terminalView.visibleCols()
        val config = TerminalConfig(
            argv = ScriptRunner.buildElfStageArgv(run.path), envp = null, rows = rows, cols = cols
        )
        val localBuffer = TerminalBuffer(rows, cols)
        buffer = localBuffer
        terminalView.buffer = localBuffer
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val s = TerminalService.createSession(config)
                session = s
                s.output.collect { }
            } catch (e: TerminalServiceUnavailableException) {
                progress.isVisible = false
                showSetup(e.message ?: getString(R.string.terminal_unavailable), openShizuku = true)
                return@launch
            } catch (e: Exception) {
                progress.isVisible = false
                showToast(getString(R.string.script_stage_failed))
                return@launch
            }
            val status = session?.waitForExit()
            session = null
            progress.isVisible = false
            if (exitCode(status) != 0) {
                showToast(getString(R.string.script_stage_failed))
                return@launch
            }
            scriptRun = run.copy(stagedElfPath = ScriptRunner.elfTmpPath())
            launchElfRunPass()
        }
    }

    /** Second pass for an ELF run: exec the staged binary in an interactive PTY. */
    private fun launchElfRunPass() {
        val run = scriptRun ?: return
        val stagedPath = run.stagedElfPath ?: return
        val rows = terminalView.visibleRows()
        val cols = terminalView.visibleCols()
        val localBuffer = TerminalBuffer(rows, cols)
        buffer = localBuffer
        terminalView.buffer = localBuffer
        terminalView.onInput = { bytes -> session?.write(bytes) }
        launchScriptSession(ScriptRunner.buildElfArgv(), ScriptRunner.buildElfEnvp())
    }

    /**
     * Runs a script through the same PTY pipeline as an interactive shell, only with a fixed
     * argv. The terminal view stays fully interactive, so scripts can prompt on stdin and the
     * user can interrupt with Ctrl+C; the exit status is surfaced when the process ends.
     */
    private fun launchScriptSession(
        argv: List<String>,
        envp: List<String>? = null
    ) {
        val rows = terminalView.visibleRows()
        val cols = terminalView.visibleCols()
        val config = TerminalConfig(argv = argv, envp = envp, rows = rows, cols = cols)
        // The ELF stage pass keeps its own buffer; an interactive run (shell script or staged
        // binary) rebinds the view here.
        if (terminalView.buffer == null || buffer?.let { it !== terminalView.buffer } == false) {
            val localBuffer = TerminalBuffer(rows, cols)
            buffer = localBuffer
            terminalView.buffer = localBuffer
            terminalView.onInput = { bytes -> session?.write(bytes) }
        }

        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val s = TerminalService.createSession(config)
                session = s
                progress.isVisible = false
                s.output.collect { chunk -> terminalView.feed(chunk, 0, chunk.size) }
            } catch (e: TerminalServiceUnavailableException) {
                progress.isVisible = false
                showSetup(e.message ?: getString(R.string.terminal_unavailable), openShizuku = true)
                return@launch
            } catch (e: Exception) {
                progress.isVisible = false
                showToast(e.message ?: getString(R.string.script_run_failed))
                return@launch
            }
            // The output flow has ended (pump closed = process gone); collect the wait status.
            val status = session?.waitForExit()
            progress.isVisible = false
            statusText.isVisible = true
            statusText.text = getString(R.string.script_exit_code_format, exitCode(status))
            // Queue a cleanup of our /data/local/tmp artifacts once the ELF run has ended.
            if (scriptRun?.kind == ScriptRunner.ScriptKind.ELF_BINARY) {
                runElfCleanup()
            }
        }
    }

    /** Best-effort silent removal of staged ELF files in /data/local/tmp. */
    private fun runElfCleanup() {
        lifecycleScope.launch {
            try {
                val config = TerminalConfig(
                    argv = ScriptRunner.buildElfCleanupArgv(), envp = null, rows = 24, cols = 80
                )
                val s = TerminalService.createSession(config)
                s.output.collect { }
                s.waitForExit()
            } catch (e: Exception) {
                // Cleanup is best effort; the tmp files are tiny and /data/local/tmp is
                // periodically wiped by the system anyway.
                e.printStackTrace()
            }
        }
    }

    private fun exitCode(waitStatus: Int?): Int =
        // Mirror the native side: a raw waitpid status, exit code in the high byte.
        if (waitStatus == null) -1 else (waitStatus shr 8) and 0xff

    private fun showDistroChooser(onPick: (TerminalDistro) -> Unit) {
        val distros = TerminalDistro.values()
        val labels = distros.map { getString(it.displayNameRes) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_choose_distro)
            .setItems(labels) { _, which -> onPick(distros[which]) }
            .setCancelable(false)
            .show()
    }

    private fun startWithDistro(distro: TerminalDistro) {
        currentDistro = distro
        if (!RootfsManager.isInstalled(distro)) {
            installDistro(distro) { launchSession(distro) }
            return
        }
        launchSession(distro)
    }

    private fun installDistro(distro: TerminalDistro, onDone: () -> Unit) {
        progress.isVisible = true
        statusText.isVisible = true
        lifecycleScope.launch {
            try {
                RootfsManager.install(distro).collect { p ->
                    when (p) {
                        RootfsManager.InstallProgress.Connecting ->
                            statusText.text = getString(R.string.terminal_connecting)
                        is RootfsManager.InstallProgress.Downloading -> {
                            val percent = if (p.total > 0) (p.downloaded * 100 / p.total).toInt() else -1
                            statusText.text = if (percent >= 0) {
                                getString(R.string.terminal_downloading_percent, percent)
                            } else {
                                getString(R.string.terminal_downloading)
                            }
                        }
                        RootfsManager.InstallProgress.Extracting ->
                            statusText.text = getString(R.string.terminal_extracting)
                        is RootfsManager.InstallProgress.Done -> {
                            progress.isVisible = false
                            statusText.isVisible = false
                        }
                    }
                }
                onDone()
            } catch (e: Exception) {
                progress.isVisible = false
                statusText.text = getString(R.string.terminal_install_failed_format, e.message ?: "")
            }
        }
    }

    private fun launchSession(distro: TerminalDistro) {
        val prootPath = RootfsManager.prootBinaryPath()
        if (prootPath == null) {
            showSetup(getString(R.string.terminal_proot_missing), openShizuku = false)
            return
        }
        val rows = terminalView.visibleRows()
        val cols = terminalView.visibleCols()
        // Build the proot argv and a matching TerminalConfig; then ask the service to fork+exec.
        val argv = RootfsManager.prootArgv(distro, prootPath, rows, cols)
        val config = TerminalConfig(argv = argv, envp = null, rows = rows, cols = cols)
        val localBuffer = TerminalBuffer(rows, cols)
        buffer = localBuffer
        terminalView.buffer = localBuffer
        terminalView.onInput = { bytes -> session?.write(bytes) }

        progress.isVisible = true
        statusText.isVisible = false
        lifecycleScope.launch {
            try {
                val s = TerminalService.createSession(config)
                session = s
                progress.isVisible = false
                // Pipe session output into the emulator. collect on the main thread so UI updates
                // are safe; feed() is cheap.
                s.output.collect { chunk -> terminalView.feed(chunk, 0, chunk.size) }
            } catch (e: TerminalServiceUnavailableException) {
                progress.isVisible = false
                showSetup(e.message ?: getString(R.string.terminal_unavailable), openShizuku = true)
            } catch (e: Exception) {
                progress.isVisible = false
                showToast(e.message ?: getString(R.string.error))
            }
        }
    }

    private fun showSetup(message: String, openShizuku: Boolean) {
        progress.isVisible = false
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.terminal_open_shizuku) { _, _ ->
                openShizukuApp()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.terminal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when {
        item.itemId == android.R.id.home -> { finish(); true }
        item.itemId == R.id.action_toggle_keyboard -> {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            if (imm.isAcceptingText) imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
            else terminalView.requestFocus()
            true
        }
        item.itemId == R.id.action_switch_distro -> {
            showDistroChooser { startWithDistro(it) }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Notify the PTY of any size change that happened while we were unfocused.
            session?.resize(terminalView.visibleRows(), terminalView.visibleCols())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
        // Remove scripts staged for shell-uid readability; originals stay untouched.
        ScriptRunner.cleanupStagedScripts(this)
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        private const val EXTRA_RUN_SCRIPT =
            "me.zhanghai.android.files.terminal.extra.RUN_SCRIPT"
        private const val EXTRA_RUN_AS_ROOT =
            "me.zhanghai.android.files.terminal.extra.RUN_AS_ROOT"
        private const val EXTRA_RUN_KIND =
            "me.zhanghai.android.files.terminal.extra.RUN_KIND"

        fun start(context: Context) {
            context.startActivity(Intent(context, TerminalActivity::class.java))
        }

        /**
         * Opens the terminal to run an already-detected executable: a shell script (optionally
         * escalated with `su -c`) or an ELF binary (staged onto /data/local/tmp first).
         */
        fun startScript(
            context: Context,
            path: java8.nio.file.Path,
            useRoot: Boolean,
            kind: ScriptRunner.ScriptKind
        ) {
            val prepared = ScriptRunner.prepareRunnableScript(context, path, kind)
            if (prepared == null) {
                context.showToast(R.string.script_run_failed)
                return
            }
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_RUN_SCRIPT, true)
                    putExtra(EXTRA_RUN_AS_ROOT, useRoot)
                    putExtra(EXTRA_RUN_KIND, kind)
                    extraPath = prepared
                }
            )
        }
    }
}
