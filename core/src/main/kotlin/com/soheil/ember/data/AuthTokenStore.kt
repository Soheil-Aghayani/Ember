package com.soheil.ember.data

import com.soheil.ember.data.model.RuntimeAuth

/** Where the Firefox account session is persisted. Android uses encrypted prefs; desktop uses the OS credential store. */
interface AuthTokenStore {
    var pendingSessionToken: String?
    fun saveAuth(auth: RuntimeAuth)
    fun loadAuth(): RuntimeAuth?
    fun hasValidAccessToken(): Boolean
    fun clear()
}
