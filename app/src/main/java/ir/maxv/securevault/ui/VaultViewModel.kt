package ir.maxv.securevault.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.maxv.securevault.data.LocalSettings
import ir.maxv.securevault.data.RecentItem
import ir.maxv.securevault.data.SearchOutcome
import ir.maxv.securevault.data.VaultRepository
import ir.maxv.securevault.data.VaultRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/** Where the unlocked app currently is. */
sealed class Route {
    data class Folder(val path: String) : Route()
    data class Note(val path: String, val highlight: String? = null) : Route()
    data object Search : Route()
}

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    val repository = VaultRepository(application)

    val state = repository.state

    private val _routes = MutableStateFlow<Route>(Route.Folder(""))
    val routes: StateFlow<Route> = _routes.asStateFlow()

    private val _search = MutableStateFlow<SearchOutcome?>(null)
    val search: StateFlow<SearchOutcome?> = _search.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _note = MutableStateFlow<NoteView?>(null)
    val note: StateFlow<NoteView?> = _note.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set when the user taps an image whose blob is not on the device yet. */
    private val _pendingImage = MutableStateFlow<String?>(null)
    val pendingImage: StateFlow<String?> = _pendingImage.asStateFlow()

    /** A pending fingerprint prompt the UI must run (the prompt needs an Activity). */
    private val _biometricRequest = MutableStateFlow<BiometricRequest?>(null)
    val biometricRequest: StateFlow<BiometricRequest?> = _biometricRequest.asStateFlow()

    /** Files opened on this device, newest first — the shortcut back to what he actually uses. */
    private val _recents = MutableStateFlow<List<RecentItem>>(emptyList())
    val recents: StateFlow<List<RecentItem>> = _recents.asStateFlow()

    private var lastOpenedPath: String? = null
    private var lastOpenedAt: Long = 0L

    /** What the prompt is for. */
    enum class BiometricMode { UNLOCK, ENABLE }

    data class BiometricRequest(
        val cipher: Cipher,
        val mode: BiometricMode,
        val title: String,
        val subtitle: String,
        val negative: String,
    )

    data class NoteView(
        val row: VaultRow,
        val text: String?,
        val note: String?,
        val tags: List<String>,
        val locked: Boolean,
        val error: String?,
    )

    init {
        repository.bootstrap()
    }

    fun consumeMessage() {
        _message.value = null
        repository.clearError()
    }

    fun onResume() = repository.onForegrounded()

    fun onPause() = repository.onBackgrounded()

    // ── setup / connection ────────────────────────────────────────────────────────
    fun saveSetup(settings: LocalSettings, access: String?, secret: String?) {
        repository.saveSetup(settings, access, secret)
    }

    fun testConnection() = viewModelScope.launch {
        _message.value = repository.testConnection()
    }

    fun sync() = viewModelScope.launch {
        val result = repository.sync()
        _message.value = result
        refreshOpenNote()
        refreshRecents()
    }

    fun downloadEverything() = viewModelScope.launch {
        _message.value = repository.downloadEverything()
        refreshOpenNote()
        refreshRecents()
    }

    fun unlock(password: String) = viewModelScope.launch {
        val ok = repository.unlock(password)
        if (ok) {
            _routes.value = Route.Folder("")
            _search.value = null
            refreshRecents()
        }
    }

    fun lock() {
        repository.lock()
        _note.value = null
        _search.value = null
        _recents.value = emptyList()
    }

    fun wipe() {
        repository.wipeLocalCopy()
        _note.value = null
        _search.value = null
        _routes.value = Route.Folder("")
        _recents.value = emptyList()
    }

    fun updateSettings(settings: LocalSettings) = repository.updateSettings(settings)

    // ── fingerprint ───────────────────────────────────────────────────────────────
    /** Ask for the sensor, then open the vault from the sealed record. */
    fun requestBiometricUnlock() = viewModelScope.launch {
        val cipher = withContext(Dispatchers.IO) { repository.biometricDecryptCipher() }
        if (cipher == null) {
            _message.value = "کلید اثر انگشت روی این دستگاه در دسترس نیست؛ با گذرواژه باز کن."
            repository.refreshBiometric()
            return@launch
        }
        _biometricRequest.value = BiometricRequest(
            cipher = cipher,
            mode = BiometricMode.UNLOCK,
            title = "گشودن گنجینه",
            subtitle = "اثر انگشتت را روی حسگر بگذار",
            negative = "گذرواژه",
        )
    }

    /** Ask for the sensor to seal the running key, so later unlocks need no password. */
    fun requestBiometricEnable() = viewModelScope.launch {
        repository.dismissBiometricOffer()
        val cipher = withContext(Dispatchers.IO) { repository.biometricEncryptCipher() }
        if (cipher == null) {
            _message.value = "حسگر اثر انگشت در دسترس نیست یا اثری ثبت نشده است."
            repository.refreshBiometric()
            return@launch
        }
        _biometricRequest.value = BiometricRequest(
            cipher = cipher,
            mode = BiometricMode.ENABLE,
            title = "گشودن با اثر انگشت",
            subtitle = "برای مهر کردن کلید گنجینه، اثر انگشتت را بگذار",
            negative = "بی‌خیال",
        )
    }

    /** The UI swallowed the request (e.g. no Activity); drop it. */
    fun consumeBiometricRequest() {
        _biometricRequest.value = null
    }

    fun onBiometricResult(outcome: BiometricOutcome, request: BiometricRequest) = viewModelScope.launch {
        _biometricRequest.value = null
        when (outcome) {
            is BiometricOutcome.Cancelled -> Unit

            is BiometricOutcome.Failed -> _message.value = outcome.message

            is BiometricOutcome.Unlocked -> when (request.mode) {
                BiometricMode.UNLOCK -> {
                    val ok = repository.unlockWithBiometric(outcome.cipher)
                    if (ok) {
                        _routes.value = Route.Folder("")
                        _search.value = null
                        refreshRecents()
                    }
                }

                BiometricMode.ENABLE -> {
                    val ok = repository.enableBiometric(outcome.cipher)
                    _message.value = if (ok) "از این پس می‌توانی گنجینه را با اثر انگشت باز کنی." else null
                }
            }
        }
    }

    fun dismissBiometricOffer() = repository.dismissBiometricOffer()

    /** Settings switch: on asks the sensor first, off drops the sealed record immediately. */
    fun setBiometricEnabled(enabled: Boolean) {
        if (enabled) requestBiometricEnable() else repository.disableBiometric()
    }

    // ── navigation ────────────────────────────────────────────────────────────────
    fun openFolder(path: String) {
        _routes.value = Route.Folder(path)
    }

    fun openSearch() {
        _routes.value = Route.Search
    }

    fun openNote(path: String, highlight: String? = null) {
        val row = repository.row(path)
        if (row == null) {
            _message.value = "یادداشت پیدا نشد: $path"
            return
        }
        rememberOpened(row.path)
        _routes.value = Route.Note(path, highlight)
        loadNote(row, reveal = !row.isSecret)
    }

    // ── recently opened ───────────────────────────────────────────────────────────
    /**
     * Count one open.
     *
     * The same note gets opened twice in a row by the UI (the tap, then the screen's own load
     * effect), so repeats inside a few seconds do not bump the counter again — otherwise "۹ بار"
     * would just mean "he scrolled past it".
     */
    private fun rememberOpened(path: String) {
        val now = System.currentTimeMillis()
        if (path == lastOpenedPath && now - lastOpenedAt < OPEN_DEDUPE_MS) return
        lastOpenedPath = path
        lastOpenedAt = now
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.recordOpened(path) }
            refreshRecents()
        }
    }

    /** Re-read the list (a disk read, so never on the main thread). */
    fun refreshRecents() = viewModelScope.launch {
        _recents.value = withContext(Dispatchers.IO) { repository.recentItems() }
    }

    fun clearRecents() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.clearRecent() }
            _recents.value = emptyList()
        }
    }

    fun back() {
        val current = _routes.value
        _routes.value = when (current) {
            is Route.Note -> Route.Folder(ir.maxv.securevault.core.VaultPaths.parentOf(current.path))
            is Route.Search -> Route.Folder("")
            is Route.Folder -> if (current.path.isEmpty()) current else Route.Folder(
                ir.maxv.securevault.core.VaultPaths.parentOf(current.path)
            )
        }
    }

    fun atRoot(): Boolean = (_routes.value as? Route.Folder)?.path?.isEmpty() ?: false

    // ── note reading ──────────────────────────────────────────────────────────────
    private fun loadNote(row: VaultRow, reveal: Boolean) = viewModelScope.launch {
        if (!reveal && row.isSecret) {
            _note.value = NoteView(row, null, null, emptyList(), locked = true, error = null)
            return@launch
        }
        if (!repository.isDownloaded(row)) {
            val ok = repository.downloadRow(row)
            if (!ok) {
                _note.value = NoteView(row, null, null, emptyList(), locked = false, error = "دریافت فایل ناموفق بود.")
                return@launch
            }
        }
        try {
            val content = repository.readNote(row)
            _note.value = NoteView(
                row = content.row,
                text = content.text,
                note = content.note,
                tags = content.tags,
                locked = false,
                error = null,
            )
        } catch (e: Exception) {
            _note.value = NoteView(row, null, null, emptyList(), locked = false, error = e.message ?: "خطای ناشناخته")
        }
    }

    fun revealSecret() {
        val current = _note.value ?: return
        loadNote(current.row, reveal = true)
    }

    private fun refreshOpenNote() {
        val current = _note.value ?: return
        val row = repository.row(current.row.path) ?: return
        loadNote(row, reveal = true)
    }

    // ── search ────────────────────────────────────────────────────────────────────
    fun runSearch(query: String, titlesOnly: Boolean) = viewModelScope.launch {
        if (query.isBlank()) {
            _search.value = null
            return@launch
        }
        _searching.value = true
        _search.value = repository.search(query, titlesOnly)
        _searching.value = false
    }

    fun clearSearch() {
        _search.value = null
    }

    // ── images ────────────────────────────────────────────────────────────────────
    fun imageBytes(url: String): ByteArray? = repository.imageFor(url)

    fun imageDownloaded(url: String): Boolean = repository.attachmentDownloaded(url)

    fun requestImage(url: String) = viewModelScope.launch {
        val row = repository.attachmentRow(url)
        if (row == null) {
            _message.value = "این تصویر در والت پیدا نشد."
            return@launch
        }
        val ok = repository.downloadRow(row)
        _message.value = if (ok) "تصویر دریافت شد." else "دریافت تصویر ناموفق بود."
        refreshOpenNote()
    }

    companion object {
        /** Two opens of the same note inside this window count as one (the UI loads a note twice). */
        private const val OPEN_DEDUPE_MS = 5_000L
    }
}
