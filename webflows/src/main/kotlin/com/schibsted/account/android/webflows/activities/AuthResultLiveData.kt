package com.schibsted.account.android.webflows.activities

import android.content.Intent
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import com.schibsted.account.android.webflows.client.Client
import com.schibsted.account.android.webflows.client.LoginError
import com.schibsted.account.android.webflows.user.User
import com.schibsted.account.android.webflows.util.Either
import com.schibsted.account.android.webflows.util.Either.Left
import com.schibsted.account.android.webflows.util.Either.Right

sealed class NotAuthed {
    object CancelledByUser : NotAuthed()
    object NoLoggedInUser : NotAuthed()
    object AuthInProgress : NotAuthed()
    data class LoginFailed(val error: LoginError) : NotAuthed()
}


typealias AuthResult = Either<NotAuthed, User>
/**
 * Holds current logged-in state of user.
 *
 * When initiated it will try to resume last logged-in user from persisted session data.
 * It may go through the following states:
 *   0. After init: Success(User) or Failure(NotAuthed.NoLoggedInUser)
 *   1. Auth flow started: Failure(NotAuthed.AuthInProgress)
 *   2. Auth flow completed: Success(User) or Failure(NotAuthed.LoginFailed)
 *   3. Auth flow cancelled: Failure(NotAuthed.CancelledByUser)
 *   4. User logs out: Failure(NotAuthed.NoLoggedInUser)
 */
class AuthResultLiveData private constructor(private val client: Client) :
    LiveData<AuthResult>() {

    internal fun update(result: AuthResult) {
        value = result
    }

    init {
        val resumedUser = client.resumeLastLoggedInUser()
        value = if (resumedUser != null) {
            Right(resumedUser)
        } else {
            Left(NotAuthed.NoLoggedInUser)
        }
    }

    internal fun update(intent: Intent) {
        client.handleAuthenticationResponse(intent) { result ->
            when (result) {
                is Right -> update(result)
                is Left -> update(Left(NotAuthed.LoginFailed(result.value)))
            }
        }
    }

    internal fun logout() {
        value = Left(NotAuthed.NoLoggedInUser)
    }

    companion object {
        private lateinit var instance: AuthResultLiveData

        internal fun getIfInitialised(): AuthResultLiveData? =
            if (::instance.isInitialized) instance else null

        @JvmStatic
        fun get(): AuthResultLiveData = instance

        @JvmStatic
        @MainThread
        internal fun create(client: Client): AuthResultLiveData {
            if (::instance.isInitialized) {
                throw IllegalStateException("Already initialized")
            }

            instance = AuthResultLiveData(client)
            return instance
        }
    }
}
