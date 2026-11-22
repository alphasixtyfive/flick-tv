package app.flick.tv.apps

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.content.pm.ApplicationInfo
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidCatalogue(context: Context) {
    private val context = context.applicationContext
    private val cache = AppCatalogueCache(::readApps)
    val revision = cache.revision

    init {
        // The catalogue and receiver share the process lifetime; Activities do not own either.
        val changes = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { cache.invalidate() }
        }
        fun observe(filter: IntentFilter) {
            if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                this.context.registerReceiver(changes, filter)
            }
        }
        observe(IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        observe(IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        })
    }

    suspend fun apps(): List<AppEntry> = cache.apps()

    private fun homePackages() =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }

    private suspend fun readApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val homes = homePackages()
        manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != context.packageName }
            .map { item ->
                currentCoroutineContext().ensureActive()
                val info = item.activityInfo.applicationInfo
                val system = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val banner = runCatching { item.activityInfo.loadBanner(manager) }.getOrNull()
                    ?: runCatching { manager.getApplicationBanner(info) }.getOrNull()
                AppEntry(info.packageName, runCatching { item.loadLabel(manager).toString() }.getOrDefault(info.packageName),
                    banner ?: runCatching { item.loadIcon(manager) }.getOrNull(),
                    system, artworkIsBanner = banner != null,
                    canUninstall = !system && info.packageName !in homes)
            }.sortedBy { it.name.lowercase() }
    }

    suspend fun continueItems(): List<ContinueItem> = withContext(Dispatchers.IO) {
        val readContext = currentCoroutineContext()
        readContext.ensureActive()
        val columns = arrayOf("_id", "package_name", "title", "poster_art_uri", "thumbnail_uri", "intent_uri",
            "last_playback_position_millis", "duration_millis")
        try {
            context.contentResolver.query(Uri.parse("content://android.media.tv/watch_next_program"), columns, null, null,
                "last_engagement_time_utc_millis DESC")?.use { cursor ->
                buildList {
                    var inspected = 0
                    while (size < 30 && inspected < 300) {
                        readContext.ensureActive()
                        if (!cursor.moveToNext()) break
                        inspected++
                        val duration = cursor.getLong(7)
                        val artwork = cursor.getString(4)?.takeIf(String::isNotBlank) ?: cursor.getString(3)
                        val uri = cursor.getString(5) ?: continue
                        val pkg = cursor.getString(1) ?: continue
                        add(ContinueItem(cursor.getLong(0), pkg, cursor.getString(2).orEmpty(), artwork, uri,
                            if (duration > 0) (cursor.getLong(6).toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f))
                    }
                }
            } ?: emptyList()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }

    fun launch(packageName: String, tvOnly: Boolean = false) {
        require(packageName != context.packageName) { "Choose another app" }
        val manager = context.packageManager
        val intent = manager.getLeanbackLaunchIntentForPackage(packageName)
            ?: if (tvOnly) null else manager.getLaunchIntentForPackage(packageName)
        requireNotNull(intent) { "This app cannot be launched" }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(item: ContinueItem) {
        val intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME)
        intent.selector = null
        intent.component?.takeIf { it.packageName != item.packageName }?.let { intent.component = null }
        intent.setPackage(item.packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun canUninstall(app: AppEntry): Boolean =
        !app.system && app.packageName != context.packageName && app.packageName !in homePackages()
}
