package app.booxultimatum.kit.update

import android.content.Context

/** Plain words for where an app stands, shared by every suite app's screens. */
object UpdateText {
    fun describe(context: Context, update: AppUpdate): String = when (val p = update.phase) {
        UpdatePhase.Idle -> context.getString(R.string.update_idle)
        UpdatePhase.Development -> context.getString(R.string.update_development)
        UpdatePhase.Checking -> context.getString(R.string.update_checking)
        UpdatePhase.UpToDate -> context.getString(R.string.update_up_to_date)
        UpdatePhase.NotPublished -> context.getString(R.string.update_not_published)
        is UpdatePhase.Available -> context.getString(
            when {
                update.installed == null -> R.string.update_install_available
                p.release.test -> R.string.update_available_test
                else -> R.string.update_available
            },
            p.release.version.toString(),
        )
        is UpdatePhase.NoChecksum -> context.getString(R.string.update_no_checksum, p.release.version.toString())
        is UpdatePhase.Downloading -> context.getString(R.string.update_downloading, p.percent)
        UpdatePhase.Verifying -> context.getString(R.string.update_verifying)
        UpdatePhase.NeedsUnknownSources -> context.getString(R.string.update_need_sources)
        UpdatePhase.Installing -> context.getString(R.string.update_installing)
        UpdatePhase.Confirming -> context.getString(R.string.update_confirming)
        UpdatePhase.Installed -> context.getString(R.string.update_installed)
        is UpdatePhase.Failed -> {
            val message = p.message.ifBlank { context.getString(R.string.update_android_refused) }
            context.getString(
                when (p.step) {
                    UpdatePhase.Step.Check -> R.string.update_failed_check
                    UpdatePhase.Step.Download -> R.string.update_failed_download
                    UpdatePhase.Step.Verify -> R.string.update_failed_verify
                    UpdatePhase.Step.Install -> R.string.update_failed_install
                },
                message,
            )
        }
    }
}
