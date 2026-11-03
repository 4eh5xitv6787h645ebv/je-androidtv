package org.jellyfin.androidtv.enhanced.subtitles

/** What a sync does to upstream's subtitle preferences. */
sealed interface SubtitleSyncAction {
	/** Leave the preferences alone. */
	data object None : SubtitleSyncAction

	/**
	 * Write [target], first saving [backup] (the user's own values) when no backup exists yet. [target] is also
	 * remembered as the last applied style.
	 */
	data class Apply(val target: NativeSubtitleStyle, val backup: NativeSubtitleStyle?) : SubtitleSyncAction

	/** Write [values] and forget the backup and the last applied style. */
	data class Restore(val values: NativeSubtitleStyle) : SubtitleSyncAction
}

/**
 * Decides what a sync does. Upstream's subtitle preferences are device-wide while the "use my JE style" choice is
 * per server and user, so the backup is device-wide too: it is taken before the first JE style is applied and put
 * back as soon as the signed-in account doesn't sync (turned off, or another account signs in).
 */
object SubtitleSyncPlanner {
	/**
	 * @param enabled the signed-in account syncs its JE style to this device.
	 * @param style the account's resolved JE style, or null while it isn't known (plugin not loaded or absent).
	 * @param current upstream's preferences now.
	 * @param backup the user's own values saved before the first sync, if a sync is in effect.
	 * @param lastApplied the values the last sync wrote, if a sync is in effect.
	 */
	fun plan(
		enabled: Boolean,
		style: JeSubtitleStyle?,
		current: NativeSubtitleStyle,
		backup: NativeSubtitleStyle?,
		lastApplied: NativeSubtitleStyle?,
	): SubtitleSyncAction = when {
		!enabled -> if (backup == null) SubtitleSyncAction.None else SubtitleSyncAction.Restore(restored(current, backup, lastApplied))
		// Unknown style, or JE's custom subtitle styles are off for this user: never write, keep whatever is there
		style == null || style.disabled -> SubtitleSyncAction.None
		else -> {
			val target = SubtitleStyleMapper.map(style)
			if (target == current && backup != null && target == lastApplied) SubtitleSyncAction.None
			else SubtitleSyncAction.Apply(target, if (backup == null) current else null)
		}
	}

	/**
	 * The backup, except for values the user changed in the app's own subtitle settings since the last sync wrote
	 * them: those are the user's newest choice and stay.
	 */
	fun restored(current: NativeSubtitleStyle, backup: NativeSubtitleStyle, lastApplied: NativeSubtitleStyle?): NativeSubtitleStyle {
		if (lastApplied == null) return backup
		fun <T> pick(get: NativeSubtitleStyle.() -> T): T = if (current.get() == lastApplied.get()) backup.get() else current.get()
		return NativeSubtitleStyle(
			textColor = pick { textColor },
			backgroundColor = pick { backgroundColor },
			strokeColor = pick { strokeColor },
			textSize = pick { textSize },
			offsetPosition = pick { offsetPosition },
		)
	}
}
