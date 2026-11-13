package rs.timok.player.ui.components

import android.content.Context
import rs.timok.player.R
import rs.timok.player.domain.SourceException

/** Maps a failure to a user-facing, localized message. */
fun Throwable.userMessage(context: Context): String = when (this) {
    is SourceException.InvalidCredentials -> context.getString(R.string.error_invalid_credentials)
    is SourceException.AccountExpired -> context.getString(R.string.error_account_expired)
    is SourceException.AccountDisabled -> context.getString(R.string.error_account_disabled, status)
    is SourceException.NoNetwork -> context.getString(R.string.error_no_network)
    is SourceException.ServerUnreachable -> context.getString(R.string.error_server_unreachable)
    is SourceException.BadResponse -> context.getString(R.string.error_bad_response)
    is SourceException.EmptyList -> context.getString(R.string.error_empty_list)
    else -> context.getString(R.string.error_unknown, message ?: javaClass.simpleName)
}
