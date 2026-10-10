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
 * Material simply does not have — the queue glyph's circular arrow wrapping two list
 * bars, the branch glyph's line forking across the card — guessing at a lookalike
 * would misread, so the SVG path is parsed into a vector instead.
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
 * A drawn rather than filled glyph, for ui-primitives' `*Outline*` artwork: those
 * are `fill: none` + `stroke: currentColor`, so [glyph]'s fill would paint them as
 * solid blobs (or nothing at all). Each string is one SVG path, opened exactly as
 * the artwork opens it; `Icon`'s tint recolours the stroke, so the literal colour
 * here is irrelevant.
 */
private fun strokeGlyph(
    name: String,
    viewport: Float,
    strokeWidth: Float,
    paths: List<String>,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = viewport.dp,
        defaultHeight = viewport.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).apply {
        paths.forEach { pathData ->
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
            )
        }
    }.build()

/**
 * `IconBranchOutlineRegular`: one line splitting into two across the card, with a
 * node on each branch — the marker for "start a new conversation from here", used
 * by the branch action on a completed turn's closing answer.
 *
 * The artwork is a 16×16 viewBox with two open paths and two circles; the circles
 * are written as the two arc halves that sweep them, because an [ImageVector] path
 * has no circle node. Stroke width is the web's own `1`.
 */
val DshIconBranch16: ImageVector by lazy {
    strokeGlyph(
        name = "Branch",
        viewport = 16f,
        strokeWidth = 1f,
        paths = listOf(
            "M1.01503 8.0001L5.6964 8.0001C6.41913 8.0001 6.78049 8.0001 7.12115 7.91951" +
                "C7.4232 7.84804 7.71233 7.73014 7.97821 7.57C8.27809 7.38939 8.5364 7.13669" +
                " 9.05303 6.63129L11.3281 4.40564",
            "M1.01221 7.9999L5.6964 7.9999C6.41913 7.9999 6.78049 7.9999 7.12115 8.08049" +
                "C7.4232 8.15196 7.71233 8.26986 7.97821 8.43C8.27809 8.61061 8.5364 8.86331" +
                " 9.05303 9.36871L11.3281 11.5944",
            // Circle (12.4502, 3.3079) r 1.56962, as two arcs.
            "M10.88058,3.3079a1.56962,1.56962 0 1,0 3.13924,0a1.56962,1.56962 0 1,0 -3.13924,0Z",
            // Circle (12.4502, 12.6921) r 1.56962.
            "M10.88058,12.6921a1.56962,1.56962 0 1,0 3.13924,0a1.56962,1.56962 0 1,0 -3.13924,0Z",
        ),
    )
}

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
