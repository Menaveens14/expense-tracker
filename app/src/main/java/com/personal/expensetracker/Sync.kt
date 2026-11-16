package com.personal.expensetracker

import android.accounts.Account
import android.content.Context
import android.util.Base64
import androidx.work.*
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
const val FILE_NAME = "expenses.xlsx"

fun gClient(c: Context) = GoogleSignIn.getClient(
    c, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(Scope(DRIVE_SCOPE)).build()
)

object Cloud {
    class R(val code: Int, val body: ByteArray) { val text get() = String(body) }

    private fun http(m: String, url: String, h: Map<String, String>, body: ByteArray? = null): R {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = m; c.connectTimeout = 20000; c.readTimeout = 30000
        h.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val b = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return R(code, b)
    }

    // ----- GitHub -----
    private fun ghApi(p: Prefs) = "https://api.github.com/repos/${p.ghRepo.trim()}/contents/${p.ghPath.trim()}"
    private fun ghHeaders(p: Prefs, accept: String = "application/vnd.github+json") =
        mapOf("Authorization" to "Bearer ${p.ghToken.trim()}", "Accept" to accept)

    fun github(p: Prefs, data: ByteArray) {
        val api = ghApi(p)
        val get = http("GET", "$api?ref=${p.ghBranch}", ghHeaders(p))
        val sha = if (get.code == 200) JSONObject(get.text).getString("sha") else null
        val body = JSONObject()
            .put("message", "Expense sync ${java.time.Instant.now()}")
            .put("content", Base64.encodeToString(data, Base64.NO_WRAP))
            .put("branch", p.ghBranch)
        if (sha != null) body.put("sha", sha)
        val r = http("PUT", api, ghHeaders(p) + ("Content-Type" to "application/json"), body.toString().toByteArray())
        check(r.code in 200..201) { "GitHub error ${r.code}" }
    }

    fun githubPull(p: Prefs): ByteArray {
        val r = http("GET", "${ghApi(p)}?ref=${p.ghBranch}", ghHeaders(p, "application/vnd.github.raw+json"))
        check(r.code == 200) { "GitHub error ${r.code}" }
        return r.body
    }

    // ----- Google Drive -----
    private class AuthExpired : Exception("Drive token expired")

    private fun driveHeaders(ctx: Context, p: Prefs, refresh: Boolean = false): Map<String, String> {
        val acct = Account(p.driveEmail, "com.google"); val scope = "oauth2:$DRIVE_SCOPE"
        try {
            var t = GoogleAuthUtil.getToken(ctx, acct, scope)
            if (refresh) { GoogleAuthUtil.clearToken(ctx, t); t = GoogleAuthUtil.getToken(ctx, acct, scope) }
            return mapOf("Authorization" to "Bearer $t")
        } catch (e: UserRecoverableAuthException) {
            error("Google needs you to reconnect: Settings > Sync > Google Drive > Change")
        } catch (e: GoogleAuthException) {
            error("Google auth failed: ${e.message}")
        }
    }

    // Runs op with a cached token; if Google says it expired (401), refreshes the token and retries once.
    private fun <T> authed(ctx: Context, p: Prefs, op: (Map<String, String>) -> T): T =
        try { op(driveHeaders(ctx, p)) } catch (e: AuthExpired) { op(driveHeaders(ctx, p, refresh = true)) }

    private fun chk(r: R, ok: IntRange) {
        if (r.code == 401) throw AuthExpired()
        check(r.code in ok) { "Drive error ${r.code}" }
    }

    private fun driveFileId(h: Map<String, String>): String? {
        val q = URLEncoder.encode("name='$FILE_NAME' and trashed=false", "UTF-8")
        val r = http("GET", "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)", h)
        chk(r, 200..200)
        return JSONObject(r.text).getJSONArray("files").optJSONObject(0)?.getString("id")
    }

    fun drive(ctx: Context, p: Prefs, data: ByteArray) = authed(ctx, p) { h ->
        val id = driveFileId(h)
        val r = if (id != null) {
            http("POST", "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media",
                h + mapOf("X-HTTP-Method-Override" to "PATCH", "Content-Type" to XLSX), data)
        } else {
            val b = "xlsxboundary"; val o = ByteArrayOutputStream()
            o.write("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"name\":\"$FILE_NAME\"}\r\n--$b\r\nContent-Type: $XLSX\r\n\r\n".toByteArray())
            o.write(data); o.write("\r\n--$b--".toByteArray())
            http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
                h + ("Content-Type" to "multipart/related; boundary=$b"), o.toByteArray())
        }
        chk(r, 200..201)
    }

    fun drivePull(ctx: Context, p: Prefs): ByteArray = authed(ctx, p) { h ->
        val id = driveFileId(h) ?: error("No $FILE_NAME found in Drive")
        val r = http("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media", h)
        chk(r, 200..200)
        r.body
    }
}

object SyncEngine {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private fun ghReady(p: Prefs) = p.ghRepo.isNotBlank() && p.ghToken.isNotBlank()

    suspend fun syncNow(ctx: Context) = withContext(Dispatchers.IO) {
        val p = Prefs.get(ctx)
        try {
            check(p.driveEmail.isNotBlank() || ghReady(p)) { "Connect Google Drive or GitHub first" }
            val data = Xlsx.write(Db.get(ctx).dao().snapshot())
            if (p.driveEmail.isNotBlank()) Cloud.drive(ctx, p, data)
            if (ghReady(p)) Cloud.github(p, data)
            p.lastSync = System.currentTimeMillis().toString(); p.status = "OK"
        } catch (e: Exception) { p.status = "Failed: ${e.message}"; throw e }
    }

    suspend fun importBytes(ctx: Context, b: ByteArray): Int = withContext(Dispatchers.IO) {
        val l = Xlsx.read(b); Db.get(ctx).dao().upsertAll(l); l.size
    }

    suspend fun restore(ctx: Context, src: String): Int = withContext(Dispatchers.IO) {
        val p = Prefs.get(ctx)
        importBytes(ctx, if (src == "drive") Cloud.drivePull(ctx, p) else Cloud.githubPull(p))
    }

    fun afterChange(ctx: Context) {
        val p = Prefs.get(ctx)
        if (p.autoOn != "1" || !(p.driveEmail.isNotBlank() || ghReady(p))) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "change", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(10, TimeUnit.SECONDS).setConstraints(net).build()
        )
    }

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx); val p = Prefs.get(ctx)
        if (p.schedule == "Off") { wm.cancelUniqueWork("periodic"); return }
        val days = if (p.schedule == "Daily") 1L else 7L
        wm.enqueueUniquePeriodicWork(
            "periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(days, TimeUnit.DAYS).setConstraints(net).build()
        )
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = try {
        SyncEngine.syncNow(applicationContext); Result.success()
    } catch (e: Exception) { if (runAttemptCount >= 3) Result.failure() else Result.retry() }
}
