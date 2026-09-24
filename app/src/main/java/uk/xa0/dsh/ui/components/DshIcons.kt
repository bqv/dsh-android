package uk.xa0.dsh.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * DSH's own icon set, lifted verbatim from `ui-primitives/src/icons/index.tsx`.
 *
 * Material's rounded set is a close cousin of DSH's Figma icons for generic
 * affordances (chevrons, trash, check), which is why those still come from
 * `androidx.compose.material.icons`. Where the shape carries meaning that
 * Material simply does not have — the queue glyph here is a circular arrow
 * wrapping two list bars — guessing at a lookalike would misread, so the SVG
 * path is parsed into a vector instead.
 *
 * `Icon` tints a vector through a colour filter, so the path's own fill colour
 * is irrelevant and black is as good as anything.
 */
private fun glyph(name: String, viewport: Float, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = viewport.dp,
        defaultHeight = viewport.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()

/**
 * `IconQueueOutline14`: a rotation arrow around a two-bar list — the marker for
 * "waiting its turn", used by the queue dock's header and single-row lead.
 */
val DshIconQueue14: ImageVector by lazy {
    glyph(
        name = "Queue",
        viewport = 14f,
        pathData = "M7.00049 0.199829C3.24488 0.199829 0.199952 3.24408 0.199707 6.99963" +
            "C0.199707 8.0414 0.434087 9.03061 0.854004 9.91467L1.11279 10.4576L2.19775 9.94202" +
            "L1.94092 9.39905L1.81787 9.12268C1.5498 8.46885 1.40186 7.75171 1.40186 6.99963" +
            "C1.4021 3.90808 3.90888 1.40198 7.00049 1.40198C10.0919 1.40219 12.5979 3.90821 12.5981 6.99963" +
            "C12.5981 10.0913 10.0921 12.5981 7.00049 12.5983C6.36734 12.5983 5.90348 12.5535 5.49268 12.4401" +
            "C5.08803 12.3283 4.7041 12.1414 4.24463 11.8209C3.57111 11.3511 2.60588 11.1855 1.81006 11.6881" +
            "L1.79736 11.6959L1.78467 11.7047L1.25537 12.0778L1.65381 13.2672L2.46045 12.6989" +
            "C2.75029 12.5214 3.18004 12.5442 3.55615 12.8063C4.10063 13.1861 4.60863 13.4423 5.17334 13.5983" +
            "C5.73194 13.7525 6.31665 13.8004 7.00049 13.8004C10.7561 13.8002 13.8003 10.7553 13.8003 6.99963" +
            "C13.8 3.24421 10.7559 0.200041 7.00049 0.199829ZM3.81201 7.47327V8.67542H7.11572V7.47327H3.81201Z" +
            "M3.81201 6.34924H10.2173V5.14709H3.81201V6.34924Z",
    )
}
