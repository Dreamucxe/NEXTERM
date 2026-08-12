package com.nexterm.feature.ssh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexterm.data.model.SshAuthMethod
import com.nexterm.data.model.SshProfile
import java.text.DateFormat
import java.util.Date

/**
 * Saved SSH hosts.
 *
 * The editor never displays a stored secret, because it cannot: the repository files
 * passwords and keys in the Keystore and hands back only a boolean saying one exists.
 * The screen therefore offers "replace" and "forget", which are the two things that
 * can honestly be done to a secret you are not allowed to read.
 */
@Composable
fun SshScreen(
    onConnect: (profileId: Long) -> Unit,
    viewModel: SshViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SshProfile?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = viewModel.blank() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add host") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            if (!viewModel.canStoreSecrets) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = "This device's keystore is not usable, so NEXTERM will " +
                                "not store passwords or keys. Hosts can still be saved and " +
                                "you will be asked for credentials each time you connect.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            message?.let { text ->
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(text, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                        }
                    }
                }
            }

            if (profiles.isEmpty()) {
                item {
                    Text(
                        text = "No saved hosts yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 28.dp),
                    )
                }
            }

            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    onConnect = { onConnect(profile.id) },
                    onEdit = { editing = profile },
                    onForget = { viewModel.forgetSecret(profile) },
                    onDelete = { viewModel.requestDelete(profile) },
                )
            }

            item { Box(Modifier.height(88.dp)) }
        }
    }

    editing?.let { profile ->
        ProfileEditor(
            profile = profile,
            canStoreSecrets = viewModel.canStoreSecrets,
            onDismiss = { editing = null },
            onSave = { updated, password, key, passphrase ->
                viewModel.save(updated, password, key, passphrase)
                editing = null
            },
        )
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Remove ${pending.profile.label}?") },
            text = {
                Text(
                    "The host and any password or private key stored for it are " +
                        "erased from this device. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: SshProfile,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onForget: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onConnect),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(profile.label.ifBlank { profile.host }, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${profile.username}@${profile.host}:${profile.port}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = buildString {
                    append(if (profile.authMethod == SshAuthMethod.KEY) "Key" else "Password")
                    append(if (profile.hasStoredSecret) " · stored on this device" else " · asked each time")
                    profile.lastConnectedAt?.let {
                        append(" · last connected ")
                        append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onConnect) { Text("Connect") }
                TextButton(onClick = onEdit) { Text("Edit") }
                if (profile.hasStoredSecret) {
                    TextButton(onClick = onForget) { Text("Forget secret") }
                }
                TextButton(onClick = onDelete) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * The add/edit dialog.
 *
 * Secrets are collected into a local String only because Compose's text field has no
 * char-array API; they are converted to a [CharArray]/[ByteArray] at the moment of
 * saving and the repository wipes those. Leaving the field blank on an existing host
 * keeps whatever is already stored rather than clearing it.
 */
@Composable
private fun ProfileEditor(
    profile: SshProfile,
    canStoreSecrets: Boolean,
    onDismiss: () -> Unit,
    onSave: (SshProfile, CharArray?, ByteArray?, CharArray?) -> Unit,
) {
    var label by remember { mutableStateOf(profile.label) }
    var host by remember { mutableStateOf(profile.host) }
    var port by remember { mutableStateOf(profile.port.toString()) }
    var username by remember { mutableStateOf(profile.username) }
    var method by remember { mutableStateOf(profile.authMethod) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    val valid = host.isNotBlank() && username.isNotBlank() &&
        port.toIntOrNull()?.let { it in 1..65535 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile.id == 0L) "Add host" else "Edit host") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = method == SshAuthMethod.PASSWORD,
                        onClick = { method = SshAuthMethod.PASSWORD },
                        label = { Text("Password") },
                    )
                    FilterChip(
                        selected = method == SshAuthMethod.KEY,
                        onClick = { method = SshAuthMethod.KEY },
                        label = { Text("Private key") },
                    )
                }

                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = {
                        Text(
                            when {
                                method == SshAuthMethod.KEY -> "Private key (PEM)"
                                profile.hasStoredSecret -> "New password"
                                else -> "Password"
                            },
                        )
                    },
                    singleLine = method != SshAuthMethod.KEY,
                    visualTransformation = if (method == SshAuthMethod.KEY) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                if (method == SshAuthMethod.KEY) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Key passphrase (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }

                Text(
                    text = when {
                        !canStoreSecrets ->
                            "This device's keystore is unavailable, so nothing entered " +
                                "here will be saved. You will be asked when connecting."
                        profile.hasStoredSecret ->
                            "A secret is already stored for this host. Leave the field " +
                                "empty to keep it; anything you type replaces it."
                        else ->
                            "Stored encrypted under an Android Keystore key. NEXTERM " +
                                "cannot read it back and never writes it to a log."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val updated = profile.copy(
                        label = label.trim().ifEmpty { host.trim() },
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        username = username.trim(),
                        authMethod = method,
                    )
                    val entered = secret.takeIf { it.isNotEmpty() && canStoreSecrets }
                    onSave(
                        updated,
                        entered?.takeIf { method == SshAuthMethod.PASSWORD }?.toCharArray(),
                        entered?.takeIf { method == SshAuthMethod.KEY }?.toByteArray(),
                        passphrase.takeIf { it.isNotEmpty() && method == SshAuthMethod.KEY }?.toCharArray(),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
