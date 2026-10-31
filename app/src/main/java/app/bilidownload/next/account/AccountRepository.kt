package app.bilidownload.next.account

/**
 * Session boundary. The QR login flow and encrypted cookie vault are implemented behind
 * this interface so credentials never leak into UI state or logs.
 */
interface AccountRepository {
    suspend fun isLoggedIn(): Boolean
    suspend fun clearSession()
}