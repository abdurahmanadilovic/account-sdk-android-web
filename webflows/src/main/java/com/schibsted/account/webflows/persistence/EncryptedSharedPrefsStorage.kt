package com.schibsted.account.webflows.persistence

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.GsonBuilder
import com.schibsted.account.webflows.user.StoredUserSession
import com.schibsted.account.webflows.util.Either
import com.schibsted.account.webflows.util.Util.getStoredUserSession
import timber.log.Timber
import java.security.GeneralSecurityException


