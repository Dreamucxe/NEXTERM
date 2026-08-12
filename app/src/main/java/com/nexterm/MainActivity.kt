package com.nexterm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexterm.core.designsystem.NextermTheme
import com.nexterm.core.terminal.TerminalSessionManager
import com.nexterm.data.preferences.SettingsRepository
import com.nexterm.feature.sessions.WorkspaceViewModel
import com.nexterm.feature.shell.NextermApp
import com.nexterm.service.TerminalForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single activity.
 *
 * Besides hosting the UI it owns one piece of policy the composables cannot: whether
 * the foreground service should be running. That is a function of the user's setting
 * and of how many PTYs are actually alive, so it is decided here from both, and the
 * notification permission is only asked for at the moment it would be used.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sessionManager: TerminalSessionManager

    @Inject lateinit var settingsRepository: SettingsRepository

    /**
     * Android 13+ drops the service notification without this, and a foreground
     * service whose notification cannot be seen is exactly the kind of silent
     * background work the platform is entitled to kill.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Either answer is respected: the service still runs, the user just may
            // not see it. Nothing is retried and nothing is asked twice.
        }

    private var permissionAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    settingsRepository.settings.map { it.foregroundService }.distinctUntilChanged(),
                    sessionManager.states
                        .map { states -> states.values.count { it.isRunning } }
                        .distinctUntilChanged(),
                ) { keepAlive, running -> keepAlive to running }
                    .distinctUntilChanged()
                    .collect { (keepAlive, running) -> applyServiceState(keepAlive, running) }
            }
        }

        setContent {
            val viewModel: WorkspaceViewModel = hiltViewModel()
            val theme by viewModel.theme.collectAsStateWithLifecycle()

            NextermTheme(terminalTheme = theme) {
                NextermApp(workspaceViewModel = viewModel)
            }
        }
    }

    /** Starts the keeper only when there is something to keep, and stops it otherwise. */
    private fun applyServiceState(keepAlive: Boolean, running: Int) {
        if (keepAlive && running > 0) {
            requestNotificationPermissionOnce()
            TerminalForegroundService.ensureRunning(this)
        } else {
            TerminalForegroundService.stop(this)
        }
    }

    private fun requestNotificationPermissionOnce() {
        if (permissionAsked) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        permissionAsked = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
