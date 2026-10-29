package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

enum class TapZoneAction { PREVIOUS, MENU, NEXT, NONE }
enum class TapZone { LEFT, MIDDLE, RIGHT }

/** Physical thirds of the current reader viewport, independent of publication reading direction. */
data class TapZoneSettings(
    val enabled: Boolean = false,
    val left: TapZoneAction = TapZoneAction.PREVIOUS,
    val middle: TapZoneAction = TapZoneAction.MENU,
    val right: TapZoneAction = TapZoneAction.NEXT,
) {
    val valid: Boolean get() = listOf(left, middle, right).contains(TapZoneAction.MENU)
    fun action(zone: TapZone) = when (zone) {
        TapZone.LEFT -> left
        TapZone.MIDDLE -> middle
        TapZone.RIGHT -> right
    }
    fun withAction(zone: TapZone, action: TapZoneAction) = when (zone) {
        TapZone.LEFT -> copy(left = action)
        TapZone.MIDDLE -> copy(middle = action)
        TapZone.RIGHT -> copy(right = action)
    }
}

interface TapZoneSettingsRepository {
    val tapZones: Flow<TapZoneSettings>
    suspend fun saveTapZones(settings: TapZoneSettings)
}