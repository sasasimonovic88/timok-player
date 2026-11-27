package rs.timok.player.data.parental

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import rs.timok.player.data.db.ChannelDao
import rs.timok.player.data.db.LockedCategoryEntity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** A pending "enter the PIN" prompt; shown above every screen by the app shell. */
class PinRequest(val onUnlocked: () -> Unit, val onCancel: () -> Unit)

/** Outcome of a typed PIN. */
sealed interface PinCheck {
    data object Ok : PinCheck
    data object Wrong : PinCheck
    /** Too many wrong tries; no more until [seconds] have passed. */
    data class Wait(val seconds: Int) : PinCheck
}

/**
 * Parental control: a 4-digit PIN (app-wide, stored only as a salted hash, never in the backup) and categories
 * locked behind it (per account, live ids and VOD keys like the hidden categories).
 *
 * One correct PIN unlocks everything until the app leaves the screen ([relock]), so a parent is not asked on every
 * zap. While locked, channels of locked categories are left out of every list and their playback asks for the PIN.
 */
@Singleton
class ParentalControl @Inject constructor(
    @ApplicationContext context: Context,
    private val channelDao: ChannelDao,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getString(KEY_HASH, null) != null)
    /** A PIN is set. */
    val enabled: StateFlow<Boolean> = _enabled

    // Updated synchronously (not derived with stateIn), so an action run right after unlocking sees it.
    private val _active = MutableStateFlow(_enabled.value)
    /** Locks are in force: a PIN is set and has not been entered since the app came to the screen. */
    val active: StateFlow<Boolean> = _active

    private fun setState(enabled: Boolean, unlocked: Boolean) {
        _enabled.value = enabled
        _active.value = enabled && !unlocked
    }

    private val _request = MutableStateFlow<PinRequest?>(null)
    val request: StateFlow<PinRequest?> = _request

    private var failures = 0
    private var blockedUntil = 0L

    /** Locked category keys of an account, whether or not the locks are in force (settings). */
    fun lockedKeys(playlistId: Long): Flow<Set<String>> =
        channelDao.observeLocked(playlistId).map { it.toSet() }.distinctUntilChanged()

    /** Locked category keys while the locks are in force, else empty: what lists must leave out. */
    fun activeLockedKeys(playlistId: Long): Flow<Set<String>> =
        combine(active, lockedKeys(playlistId)) { on, keys -> if (on) keys else emptySet() }.distinctUntilChanged()

    /** True when [streamId] belongs to a locked category and the locks are in force. */
    suspend fun isChannelLocked(playlistId: Long, streamId: Long): Boolean {
        if (!active.value) return false
        val category = channelDao.categoryOf(playlistId, streamId) ?: return false
        return category in lockedKeys(playlistId).first()
    }

    suspend fun setLocked(playlistId: Long, keys: Collection<String>, locked: Boolean) {
        if (keys.isEmpty()) return
        if (locked) channelDao.lockAll(keys.map { LockedCategoryEntity(playlistId, it) })
        else keys.chunked(500).forEach { channelDao.unlockAll(playlistId, it) }
    }

    /**
     * Runs [onUnlocked] at once when nothing is locked, else after the PIN is entered. [onCancel] runs when the
     * viewer backs out of the prompt. Requests made while the prompt is up wait for the same PIN.
     */
    fun requestUnlock(onCancel: () -> Unit = {}, onUnlocked: () -> Unit) {
        if (!active.value) {
            onUnlocked()
            return
        }
        // One prompt serves everything waiting for it (e.g. the player asking on start and again on resume):
        // replacing the pending one would cancel it and close the screen that asked.
        val pending = _request.value
        _request.value = if (pending == null) PinRequest(onUnlocked, onCancel)
        else PinRequest(
            onUnlocked = { pending.onUnlocked(); onUnlocked() },
            onCancel = { pending.onCancel(); onCancel() },
        )
    }

    /** Checks [pin] for the pending prompt; a correct one unlocks and runs its action. */
    fun submit(pin: String): PinCheck {
        val result = check(pin)
        if (result == PinCheck.Ok) {
            setState(enabled = true, unlocked = true)
            val req = _request.value
            _request.value = null
            req?.onUnlocked?.invoke()
        }
        return result
    }

    fun cancelRequest() {
        val req = _request.value ?: return
        _request.value = null
        req.onCancel()
    }

    /** Compares [pin] with the stored one, counting wrong tries (5 in a row block tries for 30 s). */
    fun check(pin: String): PinCheck {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return PinCheck.Wait(((blockedUntil - now + 999) / 1000).toInt())
        val salt = prefs.getString(KEY_SALT, null) ?: return PinCheck.Ok
        val hash = prefs.getString(KEY_HASH, null) ?: return PinCheck.Ok
        if (MessageDigest.isEqual(hash(salt, pin).toByteArray(), hash.toByteArray())) {
            failures = 0
            return PinCheck.Ok
        }
        failures++
        if (failures >= MAX_FAILURES) {
            failures = 0
            blockedUntil = now + BLOCK_MS
            return PinCheck.Wait((BLOCK_MS / 1000).toInt())
        }
        return PinCheck.Wrong
    }

    /** Sets or changes the PIN; the session stays unlocked (the parent is setting things up). */
    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SALT, salt).putString(KEY_HASH, hash(salt, pin)).apply()
        setState(enabled = true, unlocked = true)
    }

    /** Turns parental control off; the locked categories are kept for when a PIN is set again. */
    fun clearPin() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()
        setState(enabled = false, unlocked = false)
    }

    /** Locks again; called when the app leaves the screen (TV off, home button, another app). */
    fun relock() {
        setState(_enabled.value, unlocked = false)
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val PIN_LENGTH = 4
        private const val FILE = "parental"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val MAX_FAILURES = 5
        private const val BLOCK_MS = 30_000L

        /** Category names that are adult content on typical panels ("XXX", "Adult", "18+", "Za odrasle"...). */
        val ADULT = Regex("(?i)xxx|adult|18\\s*\\+|porn|erotic|odrasl|erwachsen")
    }
}
