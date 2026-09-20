@file:JsModule("dropbox")
@file:JsNonModule
package tdz.sync

import kotlin.js.Promise

/**
 * Minimal typed surface of the `dropbox` npm SDK (v10). Only the calls we use are declared.
 * Construction takes an options object (build with `kotlin.js.json(...)`). Response shapes are
 * `dynamic` and are mapped to typed values in [DropboxStorage]/[DropboxAuth] — nowhere else.
 */

@JsName("DropboxAuth")
external class DropboxAuthSdk(options: dynamic) {
    fun getAuthenticationUrl(
        redirectUri: String,
        state: String?,
        authType: String,
        tokenAccessType: String,
        scope: Array<String>?,
        includeGrantedScopes: String?,
        usePKCE: Boolean,
    ): Promise<String>

    fun getCodeVerifier(): String
    fun setCodeVerifier(verifier: String)
    fun getAccessTokenFromCode(redirectUri: String, code: String): Promise<dynamic>
}

@JsName("Dropbox")
external class DropboxSdk(options: dynamic) {
    fun filesUpload(arg: dynamic): Promise<dynamic>
    fun filesDownload(arg: dynamic): Promise<dynamic>
    fun filesGetTemporaryLink(arg: dynamic): Promise<dynamic>
    fun filesGetTemporaryUploadLink(arg: dynamic): Promise<dynamic>
    fun filesGetMetadata(arg: dynamic): Promise<dynamic>
    fun filesListFolder(arg: dynamic): Promise<dynamic>
    fun filesListFolderContinue(arg: dynamic): Promise<dynamic>
    fun filesDeleteV2(arg: dynamic): Promise<dynamic>
    fun usersGetCurrentAccount(): Promise<dynamic>
    fun authTokenRevoke(): Promise<dynamic>
}
