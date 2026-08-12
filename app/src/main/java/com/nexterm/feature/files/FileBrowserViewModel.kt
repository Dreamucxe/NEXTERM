package com.nexterm.feature.files

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexterm.data.model.Bookmark
import com.nexterm.data.preferences.FileSortOrder
import com.nexterm.data.preferences.FileViewMode
import com.nexterm.data.preferences.Settings
import com.nexterm.data.preferences.SettingsRepository
import com.nexterm.data.repository.ContentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** A pending destructive action awaiting explicit confirmation. */
data class DeleteRequest(
    val nodes: List<FileNode>,
    val recursive: Boolean,
)

/** Clipboard for cut/copy inside the browser. */
data class FileClipboard(
    val providerId: String,
    val nodes: List<FileNode>,
    val move: Boolean,
)

data class BrowserState(
    val providerId: String = "local",
    val providerName: String = "Device storage",
    val path: String = "",
    val entries: List<FileNode> = emptyList(),
    val capabilities: ProviderCapabilities = ProviderCapabilities.NONE,
    val isLoading: Boolean = false,
    /** Non-null when the current directory could not be read. Never swallowed. */
    val error: String? = null,
    val errorDetail: String? = null,
    val selection: Set<String> = emptySet(),
    val providers: List<ProviderSummary> = emptyList(),
    val shortcuts: List<Pair<String, String>> = emptyList(),
    val grantedTrees: List<Pair<String, String>> = emptyList(),
    val clipboard: FileClipboard? = null,
    val pendingDelete: DeleteRequest? = null,
    /** Progress note for a running copy/move, so long operations are not silent. */
    val busyNote: String? = null,
    val searchQuery: String = "",
) {
    val isSelecting: Boolean get() = selection.isNotEmpty()
}

/** A provider as the picker shows it, including why it may be unusable. */
data class ProviderSummary(
    val id: String,
    val displayName: String,
    val usable: Boolean,
    val limitation: String?,
)

/**
 * Drives the file browser.
 *
 * Every listing is a real directory read through a [FileSystemProvider]; there is no
 * sample data anywhere in this class. When a read fails, the failure is put on screen
 * with the reason the provider gave rather than being replaced by an empty list.
 */
@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val registry: FileSystemRegistry,
    private val settingsRepository: SettingsRepository,
    private val contentRepository: ContentRepository,
    private val saf: SafFileSystemProvider,
    private val local: LocalFileSystemProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state

    val bookmarks: StateFlow<List<Bookmark>> = contentRepository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private var provider: FileSystemProvider = local
    private var listJob: Job? = null

    /** Back stack of visited directories, so Back walks history not just parents. */
    private val history = ArrayDeque<Pair<String, String>>()

    init {
        viewModelScope.launch {
            refreshProviders()
            open(local.rootPath)
        }
    }

    // ---- Navigation ----

    fun open(path: String, pushHistory: Boolean = true) {
        listJob?.cancel()
        if (pushHistory && _state.value.path.isNotEmpty()) {
            history.addLast(provider.id to _state.value.path)
            if (history.size > 64) history.removeFirst()
        }
        listJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, errorDetail = null, selection = emptySet()) }
            val capabilities = runCatching { provider.capabilities() }
                .getOrDefault(ProviderCapabilities.NONE)
            try {
                val prefs = settingsRepository.settings.first()
                val entries = provider.list(path).let { sortAndFilter(it, prefs.showHiddenFiles, prefs.fileSortOrder, prefs.foldersFirst) }
                _state.update {
                    it.copy(
                        providerId = provider.id,
                        providerName = provider.displayName,
                        path = path,
                        entries = entries,
                        capabilities = capabilities,
                        isLoading = false,
                    )
                }
            } catch (e: FileOperationException) {
                _state.update {
                    it.copy(
                        providerId = provider.id, providerName = provider.displayName, path = path,
                        entries = emptyList(), capabilities = capabilities, isLoading = false,
                        error = e.reason, errorDetail = e.detail,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        path = path, entries = emptyList(), capabilities = capabilities,
                        isLoading = false, error = "That folder could not be read.", errorDetail = e.message,
                    )
                }
            }
        }
    }

    fun refresh() = open(_state.value.path, pushHistory = false)

    /** @return true if the event was handled, so the activity knows not to finish. */
    fun goBack(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        viewModelScope.launch {
            if (previous.first != provider.id) {
                provider = registry.byId(previous.first) ?: provider
            }
            open(previous.second, pushHistory = false)
        }
        return true
    }

    fun goUp() {
        val current = _state.value.path
        if (provider.id == "saf") {
            // SAF paths are opaque URIs with no parent arithmetic; history is the
            // only honest way up, so fall back to it rather than guessing.
            goBack()
            return
        }
        val parent = File(current).parent ?: return
        if (current == provider.rootPath && provider.id.startsWith("proot:")) return
        open(parent)
    }

    fun switchProvider(id: String) {
        viewModelScope.launch {
            val next = registry.byId(id) ?: return@launch
            provider = next
            refreshProviders()
            open(next.rootPath, pushHistory = false)
        }
    }

    private suspend fun refreshProviders() {
        val options = registry.options()
        _state.update { current ->
            current.copy(
                providers = options.map {
                    ProviderSummary(
                        id = it.provider.id,
                        displayName = it.provider.displayName,
                        usable = it.isUsable,
                        limitation = it.capabilities.limitation,
                    )
                },
                shortcuts = local.shortcuts(),
                grantedTrees = saf.grantedTrees().map { saf.treeLabel(it) to it.toString() },
            )
        }
    }

    // ---- Selection ----

    fun toggleSelection(path: String) {
        _state.update { current ->
            current.copy(
                selection = if (path in current.selection) current.selection - path else current.selection + path,
            )
        }
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun selectAll() = _state.update { current -> current.copy(selection = current.entries.map { it.path }.toSet()) }

    private fun selectedNodes(): List<FileNode> {
        val selection = _state.value.selection
        return _state.value.entries.filter { it.path in selection }
    }

    // ---- Operations ----

    fun createDirectory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        operate("Creating $trimmed") {
            provider.createDirectory(childPath(_state.value.path, trimmed))
        }
    }

    fun createFile(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        operate("Creating $trimmed") {
            provider.openOutput(childPath(_state.value.path, trimmed)).use { }
        }
    }

    fun rename(node: FileNode, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == node.name) return
        operate("Renaming ${node.name}") {
            provider.rename(node.path, childPath(_state.value.path, trimmed))
        }
    }

    /**
     * Stages a delete. Nothing is removed until [confirmDelete], because the spec
     * forbids silently deleting user files and a swipe is too easy to trigger.
     */
    fun requestDelete(nodes: List<FileNode> = selectedNodes()) {
        if (nodes.isEmpty()) return
        _state.update {
            it.copy(pendingDelete = DeleteRequest(nodes, recursive = nodes.any { node -> node.isDirectory }))
        }
    }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val request = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        operate("Deleting ${request.nodes.size} item${if (request.nodes.size == 1) "" else "s"}") {
            for (node in request.nodes) {
                provider.delete(node.path, request.recursive)
            }
        }
    }

    fun setPermissions(node: FileNode, mode: Int) {
        operate("Changing permissions on ${node.name}") { provider.setPermissions(node.path, mode) }
    }

    fun copySelection(move: Boolean) {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        _state.update {
            it.copy(clipboard = FileClipboard(provider.id, nodes, move), selection = emptySet())
        }
    }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    /**
     * Pastes across providers by streaming bytes.
     *
     * Source and destination may be entirely different backends — a Shizuku shell and
     * a SAF tree, say — so there is no rename shortcut to take; the content is copied
     * through, and only removed from the source afterwards when this was a move.
     */
    fun paste() {
        val clipboard = _state.value.clipboard ?: return
        val destination = _state.value.path
        viewModelScope.launch {
            val source = registry.byId(clipboard.providerId)
            if (source == null) {
                fail("The source of that copy is no longer available.")
                return@launch
            }
            _state.update { it.copy(busyNote = "Copying ${clipboard.nodes.size} item(s)") }
            try {
                for (node in clipboard.nodes) {
                    copyNode(source, node, destination)
                }
                if (clipboard.move) {
                    for (node in clipboard.nodes) {
                        source.delete(node.path, recursive = node.isDirectory)
                    }
                }
                _state.update { it.copy(clipboard = null, busyNote = null) }
                refresh()
            } catch (e: FileOperationException) {
                _state.update { it.copy(busyNote = null, error = e.reason, errorDetail = e.detail) }
            } catch (e: Exception) {
                _state.update { it.copy(busyNote = null, error = "The copy did not finish.", errorDetail = e.message) }
            }
        }
    }

    private suspend fun copyNode(source: FileSystemProvider, node: FileNode, intoPath: String) {
        val target = childPath(intoPath, node.name)
        if (node.isDirectory) {
            provider.createDirectory(target)
            for (child in source.list(node.path)) {
                copyNode(source, child, target)
            }
            return
        }
        source.openInput(node.path).use { input ->
            provider.openOutput(target).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
    }

    // ---- Bookmarks and SAF grants ----

    fun bookmarkCurrent() {
        val path = _state.value.path
        viewModelScope.launch {
            contentRepository.addBookmark(File(path).name.ifEmpty { path }, "${provider.id}:$path")
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { contentRepository.deleteBookmark(id) }
    }

    fun openBookmark(bookmark: Bookmark) {
        val providerId = bookmark.path.substringBefore(':', "local")
        val path = bookmark.path.substringAfter(':', bookmark.path)
        viewModelScope.launch {
            registry.byId(providerId)?.let { provider = it }
            refreshProviders()
            open(path)
        }
    }

    fun folderPickerIntent(): Intent = saf.pickerIntent()

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            saf.persistGrant(uri)
            provider = saf
            refreshProviders()
            open(uri.toString(), pushHistory = false)
        }
    }

    fun releaseGrant(uriString: String) {
        viewModelScope.launch {
            saf.releaseGrant(Uri.parse(uriString))
            refreshProviders()
            if (provider.id == "saf") open(saf.rootPath, pushHistory = false)
        }
    }

    // ---- Preferences that affect the listing ----

    fun setShowHidden(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowHiddenFiles(value)
            refresh()
        }
    }

    fun setSortOrder(order: FileSortOrder) {
        viewModelScope.launch {
            settingsRepository.setFileSortOrder(order)
            refresh()
        }
    }

    fun setViewMode(mode: FileViewMode) {
        viewModelScope.launch { settingsRepository.setFileViewMode(mode) }
    }

    fun setSearchQuery(query: String) = _state.update { it.copy(searchQuery = query) }

    fun dismissError() = _state.update { it.copy(error = null, errorDetail = null) }

    /** Reads a text file for the built-in editor, refusing anything that is not text. */
    suspend fun readText(node: FileNode): Result<String> = runCatching {
        if (node.sizeBytes > MAX_EDIT_BYTES) {
            throw FileOperationException(
                "${node.name} is too large to open in the editor.",
                "The editor holds the whole file in memory and stops at ${MAX_EDIT_BYTES / 1024} KiB.",
            )
        }
        val bytes = provider.openInput(node.path).use { it.readBytes() }
        if (bytes.any { it == 0.toByte() }) {
            throw FileOperationException(
                "${node.name} looks like a binary file.",
                "It contains NUL bytes, so editing it as text would corrupt it.",
            )
        }
        String(bytes, Charsets.UTF_8)
    }

    suspend fun writeText(node: FileNode, content: String): Result<Unit> = runCatching {
        provider.openOutput(node.path, append = false).use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    /** Path of the current directory in a form the terminal can `cd` into, if any. */
    fun terminalPath(): String? = _state.value.path.takeIf { provider.id != "saf" && it.startsWith("/") }

    // ---- Helpers ----

    private fun operate(note: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busyNote = note) }
            try {
                block()
                _state.update { it.copy(busyNote = null) }
                refresh()
            } catch (e: FileOperationException) {
                _state.update { it.copy(busyNote = null, error = e.reason, errorDetail = e.detail) }
            } catch (e: Exception) {
                _state.update { it.copy(busyNote = null, error = "$note failed.", errorDetail = e.message) }
            }
        }
    }

    private fun fail(reason: String, detail: String? = null) =
        _state.update { it.copy(busyNote = null, error = reason, errorDetail = detail) }

    private fun childPath(parent: String, name: String): String =
        if (provider.id == "saf") name else "${parent.trimEnd('/')}/$name"

    private fun sortAndFilter(
        entries: List<FileNode>,
        showHidden: Boolean,
        order: FileSortOrder,
        foldersFirst: Boolean,
    ): List<FileNode> {
        val visible = entries.filter { showHidden || !it.isHidden }
        val comparator = when (order) {
            FileSortOrder.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { node: FileNode -> node.name }
            FileSortOrder.SIZE -> compareByDescending { node: FileNode -> node.sizeBytes }
            FileSortOrder.MODIFIED -> compareByDescending { node: FileNode -> node.lastModified }
            FileSortOrder.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { node: FileNode -> node.extension }
        }
        return if (foldersFirst) {
            visible.sortedWith(compareByDescending<FileNode> { it.isDirectory }.then(comparator))
        } else {
            visible.sortedWith(comparator)
        }
    }

    private companion object {
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024
    }
}
