package org.jellyfin.androidtv.enhanced.subtitles

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** A signed-in account: the sync choice is kept per server and user. */
data class SubtitleSyncAccount(val serverId: UUID, val userId: UUID) {
	internal val key get() = "${serverId.toString().replace("-", "")}_${userId.toString().replace("-", "")}"
}

/**
 * Local state of the subtitle sync, on this device only (SharedPreferences file `je_subtitles`): the per-account
 * "use my JE style" choice, and the device-wide backup of the user's own subtitle preferences with the values the
 * last sync wrote (see [SubtitleSyncPlanner]).
 */
class SubtitleSyncStore(context: Context) {
	private val prefs = context.getSharedPreferences("je_subtitles", Context.MODE_PRIVATE)

	fun isEnabled(account: SubtitleSyncAccount): Boolean = prefs.getBoolean("enabled_${account.key}", false)

	fun setEnabled(account: SubtitleSyncAccount, enabled: Boolean) {
		prefs.edit().apply {
			if (enabled) putBoolean("enabled_${account.key}", true) else remove("enabled_${account.key}")
		}.apply()
	}

	val backup: NativeSubtitleStyle? get() = read(BACKUP)
	val lastApplied: NativeSubtitleStyle? get() = read(APPLIED)

	/** Remember [applied] as the last written values, and [backup] unless a backup is already kept. */
	fun saveApplied(applied: NativeSubtitleStyle, backup: NativeSubtitleStyle?) {
		prefs.edit().apply {
			if (backup != null && !prefs.contains("${BACKUP}present")) write(BACKUP, backup)
			write(APPLIED, applied)
		}.commit()
	}

	/** Forget the backup and the last applied values once the backup has been put back. */
	fun clear() {
		prefs.edit().apply {
			for (prefix in listOf(BACKUP, APPLIED)) for (field in FIELDS) remove("$prefix$field")
		}.commit()
	}

	private fun read(prefix: String): NativeSubtitleStyle? {
		if (!prefs.getBoolean("${prefix}present", false)) return null
		return NativeSubtitleStyle(
			textColor = prefs.getInt("${prefix}text", 0),
			backgroundColor = prefs.getInt("${prefix}background", 0),
			strokeColor = prefs.getInt("${prefix}stroke", 0),
			textSize = prefs.getFloat("${prefix}size", SubtitleStyleMapper.DEFAULT_TEXT_SIZE),
			offsetPosition = prefs.getFloat("${prefix}offset", 0f),
		)
	}

	private fun SharedPreferences.Editor.write(prefix: String, style: NativeSubtitleStyle) {
		putBoolean("${prefix}present", true)
		putInt("${prefix}text", style.textColor)
		putInt("${prefix}background", style.backgroundColor)
		putInt("${prefix}stroke", style.strokeColor)
		putFloat("${prefix}size", style.textSize)
		putFloat("${prefix}offset", style.offsetPosition)
	}

	private companion object {
		const val BACKUP = "backup_"
		const val APPLIED = "applied_"
		val FIELDS = listOf("present", "text", "background", "stroke", "size", "offset")
	}
}
