import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * بترسم فعليًا نتيجة الرص (NestingResult) — لوح بلوح، وقطعة بقطعة بالشكل
 * والزاوية الحقيقية بتاعتها. ده بديل حقيقي للمعاينة الوهمية اللي كانت في
 * index.html القديمة (اللي كانت بترسم bounding box بس).
 */
@Composable
fun NestingPreviewView(result: NestingResult?, modifier: Modifier = Modifier) {
    if (result == null || result.boards.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("لسه مفيش نتيجة رص — اضبط الإعدادات ودوس ابدأ Nesting", color = Color.Gray)
        }
        return
    }

    var boardIdx by remember { mutableStateOf(0) }
    LaunchedEffect(result) { boardIdx = 0 } // رجّع لأول لوح كل ما نتيجة جديدة توصل

    val safeIdx = boardIdx.coerceIn(0, result.boards.size - 1)
    val board = result.boards[safeIdx]

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { if (boardIdx > 0) boardIdx-- }, enabled = boardIdx > 0) {
                Text("◀ السابق")
            }
            Spacer(Modifier.width(10.dp))
            Text("لوح ${safeIdx + 1} من ${result.boards.size}  —  ${board.pieces.size} قطعة")
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { if (boardIdx < result.boards.size - 1) boardIdx++ },
                enabled = boardIdx < result.boards.size - 1
            ) { Text("التالي ▶") }
        }

        Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            val pad = 20f
            val availW = (size.width - pad * 2).coerceAtLeast(1f)
            val availH = (size.height - pad * 2).coerceAtLeast(1f)
            val scale = min(availW / board.width.toFloat(), availH / board.height.toFloat())
            val boardWpx = board.width.toFloat() * scale
            val boardHpx = board.height.toFloat() * scale
            val originX = pad
            val originY = pad

            // اللوح نفسه (بمقاسه الحقيقي بعد التحجيم)
            drawRect(
                color = Color(0xFFCBB992),
                topLeft = Offset(originX, originY),
                size = Size(boardWpx, boardHpx)
            )
            drawRect(
                color = Color(0xFF8F7D5D),
                topLeft = Offset(originX, originY),
                size = Size(boardWpx, boardHpx),
                style = Stroke(width = 2f)
            )

            for (piece in board.pieces) {
                val worldPts = transformOutline(piece.polygon.outer, piece.rotationDeg, piece.x, piece.y)
                if (worldPts.size < 3) continue
                val path = Path()
                worldPts.forEachIndexed { idx, p ->
                    val px = originX + (p.x.toFloat() * scale)
                    val py = originY + (p.y.toFloat() * scale)
                    if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path = path, color = Color(0x55FF8A18))
                drawPath(path = path, color = Color(0xFF171717), style = Stroke(width = 1.4f))
            }
        }
    }
}

/** نفس منطق الدوران+التطبيع+النقل المستخدم جوه NestingEngine، لكن هنا لغرض الرسم بس */
private fun transformOutline(points: List<NestingPoint>, rotationDeg: Double, dx: Double, dy: Double): List<NestingPoint> {
    if (points.isEmpty()) return points
    val r = Math.toRadians(rotationDeg)
    val c = cos(r); val s = sin(r)
    val rotated = points.map { NestingPoint(it.x * c - it.y * s, it.x * s + it.y * c) }
    val minX = rotated.minOf { it.x }
    val minY = rotated.minOf { it.y }
    return rotated.map { NestingPoint(it.x - minX + dx, it.y - minY + dy) }
}
