package dev.komkov.m2sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Отдача .fit наружу: в системную «Поделиться», в Strava, в мессенджер, в файлы.
 *
 * Устройство именует файлы как 20260724103005.fit — для отправки делаем читаемую
 * копию вида 2026-07-24_10-30_40.99km_cycplus-m2.fit во временной папке.
 */
object Sharing {

    private const val MIME = "application/vnd.ant.fit"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm", Locale.US)

    private fun authority(ctx: Context) = "${ctx.packageName}.files"

    fun prettyName(ride: RideSummary): String {
        val date = stamp.format(ride.start.atZone(ZoneId.systemDefault()))
        val km = String.format(Locale.US, "%.2f", ride.distanceM / 1000)
        return "${date}_${km}km_cycplus-m2.fit"
    }

    private fun stageForSharing(ctx: Context, ride: RideSummary): Uri? {
        val source = File(SyncService.fitDir(ctx), ride.file)
        if (!source.exists()) return null
        val outDir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val target = File(outDir, prettyName(ride))
        if (!target.exists() || target.length() != source.length()) {
            source.copyTo(target, overwrite = true)
        }
        return FileProvider.getUriForFile(ctx, authority(ctx), target)
    }

    fun shareRide(ctx: Context, ride: RideSummary) {
        val uri = stageForSharing(ctx, ride) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, prettyName(ride))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(send, ctx.getString(R.string.share_ride))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun shareAll(ctx: Context, rides: List<RideSummary>) {
        val uris = ArrayList<Uri>(rides.mapNotNull { stageForSharing(ctx, it) })
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = MIME
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(send, ctx.getString(R.string.share_all))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
