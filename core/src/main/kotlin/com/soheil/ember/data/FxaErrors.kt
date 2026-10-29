package com.soheil.ember.data

const val FXA_RESET_PASSWORD_URL = "https://accounts.firefox.com/reset_password"
const val FXA_SIGN_UP_URL = "https://accounts.firefox.com/signup"

/** Mozilla's CDN answered 406 before the account server saw the request: the network is blocked, not the password. */
fun Throwable.isBlockedByMozilla(): Boolean = this is FxaApiError && statusCode == 406
