package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.PointF
import android.graphics.RectF

abstract class ViewerNavigation {

    sealed interface NavigationRegion {
        data object MENU : NavigationRegion
        data object PREV : NavigationRegion
        data object NEXT : NavigationRegion
        data object LEFT : NavigationRegion
        data object RIGHT : NavigationRegion
    }

    data class Region(
        val rectF: RectF,
        val type: NavigationRegion,
    )

    private var constantMenuRegion: RectF = RectF(0f, 0f, 1f, 0.05f)

    protected abstract var regionList: List<Region>

    fun getRegions(): List<Region> {
        return regionList
    }

    fun getAction(pos: PointF): NavigationRegion {
        val x = pos.x
        val y = pos.y
        val region = getRegions().find { it.rectF.contains(x, y) }
        return when {
            region != null -> region.type
            constantMenuRegion.contains(x, y) -> NavigationRegion.MENU
            else -> NavigationRegion.MENU
        }
    }
}
