package enterprises.legitimate.rabbitears

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import enterprises.legitimate.rabbitears.api.models.AccountKind
import enterprises.legitimate.rabbitears.kit.DownloadScheduler
import enterprises.legitimate.rabbitears.kit.DownloadStore
import enterprises.legitimate.rabbitears.kit.Downloads
import enterprises.legitimate.rabbitears.kit.TitleState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fetches downloads in the background under WorkManager, in the foreground with a notification that
 * shows the title under way. Run again when the network went before the queue was done.
 */
class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private val process = context.applicationContext as RabbitEarsApp

    override suspend fun doWork(): Result {
        val api = process.downloadApi() ?: return Result.success()
        val active = { process.householdForDownloads()?.activeProfileId }
        val kids =
            process
                .householdForDownloads()
                ?.profiles
                .orEmpty()
                .filter { it.kind == AccountKind.kid }
                .map { it.id }
                .toSet()
        if (inputData.getBoolean(CLAIM_ONLY, false)) {
            process.downloads.claim(api, active(), kids)
            return Result.success()
        }
        runCatching { setForeground(getForegroundInfo()) }
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        return coroutineScope {
            val notifying = launch {
                while (isActive) {
                    delay(NOTIFY_MS)
                    runCatching { setForeground(getForegroundInfo()) }
                }
            }
            val again =
                process.downloads.run(
                    api,
                    active,
                    kids,
                    unmetered = !connectivity.isActiveNetworkMetered,
                    dataSaver = connectivity.dataSaver,
                )
            notifying.cancel()
            if (again) Result.retry() else Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val store = process.downloads.store
        val current = store.state.titles.values.firstOrNull { it.state == TitleState.Downloading }
        val waiting = store.state.titles.values.count { it.state in DownloadStore.WAITING }
        val notification =
            NotificationCompat.Builder(applicationContext, channel(applicationContext))
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(applicationContext.getString(R.string.downloads_notification))
                .setContentText(
                    applicationContext.resources.getQuantityString(
                        R.plurals.downloads_waiting,
                        waiting,
                        waiting,
                    ),
                )
                .setOngoing(true)
                .setSilent(true)
                .apply {
                    if (current != null && current.sizeBytes > 0)
                        setProgress(PROGRESS_MAX, (current.progress * PROGRESS_MAX).toInt(), false)
                    else setProgress(0, 0, true)
                }
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        /** Input of a run that only claims, ahead of transfers the network holds back. */
        const val CLAIM_ONLY = "claimOnly"
        private const val NOTIFICATION_ID = 4
        private const val NOTIFY_MS = 2_000L
        private const val PROGRESS_MAX = 100
        private const val CHANNEL = "downloads"

        private fun channel(context: Context): String {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null)
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.downloads_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            return CHANNEL
        }
    }
}

/** Data Saver is on and this app is held to it: on a metered network, background data waits. */
private val ConnectivityManager.dataSaver
    get() = restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

/**
 * Queues [DownloadWorker] as one unique chain. A run in progress under the same network rule is let
 * finish, with the new one queued behind it; any other is replaced, so work backing off or blocked
 * never holds up a new run, and a changed Wi-Fi or Data Saver rule applies at once. Transfers a
 * metered network would hold back wait for an unmetered one, behind a run that claims on any.
 * Registers for Data Saver changes for the life of the process, as WorkManager cannot wait on them.
 */
class WorkDownloadScheduler(private val context: Context, private val store: () -> DownloadStore) :
    DownloadScheduler {
    init {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val titles = store().state.titles.values
                    if (
                        titles.any {
                            it.state in DownloadStore.WAITING || it.state == TitleState.Downloading
                        }
                    )
                        schedule(store().device.wifiOnly)
                }
            },
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun schedule(wifiOnly: Boolean) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val dataSaver = connectivity.dataSaver
        // Said at once, so a title shows what it waits for before the worker could run to say it.
        Downloads.waitingFor(wifiOnly, connectivity.isActiveNetworkMetered, dataSaver)?.let {
            store().awaitNetwork(it)
        }
        val held = Downloads.waitingFor(wifiOnly, metered = true, dataSaver) != null
        val rule = if (held) WIFI_ONLY else ANY_NETWORK
        val request = request(rule, if (held) NetworkType.UNMETERED else NetworkType.CONNECTED)
        val work = WorkManager.getInstance(context)
        val chain = work.getWorkInfosForUniqueWork(WORK)
        chain.addListener(
            {
                val infos = runCatching { chain.get() }.getOrDefault(emptyList())
                val policy = policy(infos, rule)
                if (!held) work.enqueueUniqueWork(WORK, policy, request)
                else {
                    val claims =
                        request(
                            rule,
                            NetworkType.CONNECTED,
                            workDataOf(DownloadWorker.CLAIM_ONLY to true),
                        )
                    work.beginUniqueWork(WORK, policy, claims).then(request).enqueue()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun request(rule: String, network: NetworkType, input: Data = Data.EMPTY) =
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(rule)
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()

    companion object {
        private const val WORK = "downloads"
        private const val WIFI_ONLY = "wifi"
        private const val ANY_NETWORK = "any"

        fun policy(chain: List<WorkInfo>, rule: String): ExistingWorkPolicy =
            if (chain.any { it.state == WorkInfo.State.RUNNING && rule in it.tags })
                ExistingWorkPolicy.APPEND_OR_REPLACE
            else ExistingWorkPolicy.REPLACE
    }
}
