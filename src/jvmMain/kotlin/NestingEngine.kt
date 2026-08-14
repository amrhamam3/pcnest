import kotlin.math.*

/**
 * محرك الرص الحقيقي (Real Nesting Engine)
 * ==========================================
 * بياخد شكل واحد (NestingPolygon) وبيحاول يرصه Config.copies مرة على لوح/ألواح
 * بمقاس NestingConfig.boardWidth × boardHeight.
 *
 * الفرق عن الرص القديم اللي في index.html (JS):
 * - القديم كان بيحسب "الصندوق المحيط" (bounding box) بس ويرصه زي مستطيل.
 * - هنا بنفحص تصادم الشكل الحقيقي (edge-to-edge + احتواء نقطة) مش الصندوق،
 *   يعني لو الشكل غير منتظم (زي قطعة L أو دائرة فيها زاوية ناقصة) الرص بيبقى
 *   أدق وبيستغل المساحة أحسن.
 * - بيجرب زوايا دوران مختلفة لكل قطعة (حسب RotationMode) ويختار أفضل مكان.
 * - بيحترم "المسافة بين القطع" (clearanceMm) كمسافة حقيقية بين حواف الأشكال،
 *   مش بس فرق في الصندوق المحيط.
 *
 * ملحوظة: الفتحات الداخلية (holes) بيتم تجاهلها في اختبار التصادم لتبسيط
 * الحساب (الرص هيحترم الحد الخارجي بس). لو حبيت دقة أعلى ممكن نضيفها لاحقاً.
 */
object NestingEngine {

    fun nest(
        piece: NestingPolygon,
        config: NestingConfig,
        onProgress: ((NestingProgress) -> Unit)? = null
    ): NestingResult {
        val startTime = System.currentTimeMillis()

        val rotations = rotationCandidates(config)
        val usableWidth = config.boardWidth - config.edgeLeftMm - config.edgeRightMm
        val usableHeight = config.boardHeight - config.edgeTopMm - config.edgeBottomMm

        val sourceOuterArea = abs(NestingShapeBuilder.signedArea(piece.outer))

        val boards = mutableListOf<NestingBoard>()
        var currentBoardPieces = mutableListOf<NestingPiece>()
        var boardIndex = 0
        var placedCount = 0
        val totalRequested = config.copies

        // خطوة البحث عن مكان: كل ما الشكل أصغر كل ما بندور بدقة أعلى (وبطء أكتر)،
        // فبنربطها بحجم الشكل نفسه عشان الأداء يفضل معقول.
        val pieceBounds = boundsOf(piece.outer)
        val minSide = max(1.0, min(pieceBounds.width, pieceBounds.height))
        val step = max(2.0, minSide / 12.0)

        repeat(config.copies) { copyIndex ->
            var placement = findPlacement(
                existing = currentBoardPieces,
                piece = piece,
                rotations = rotations,
                usableWidth = usableWidth,
                usableHeight = usableHeight,
                clearance = config.clearanceMm,
                step = step
            )

            // لو مفيش مكان في اللوح الحالي، اقفله وافتح لوح جديد
            if (placement == null) {
                boards += finalizeBoard(boardIndex, currentBoardPieces, config)
                boardIndex++
                currentBoardPieces = mutableListOf()
                placement = findPlacement(
                    existing = currentBoardPieces,
                    piece = piece,
                    rotations = rotations,
                    usableWidth = usableWidth,
                    usableHeight = usableHeight,
                    clearance = config.clearanceMm,
                    step = step
                )
            }

            if (placement != null) {
                val worldPoly = transform(piece.outer, placement.rotationDeg, placement.offsetX, placement.offsetY)
                val bounds = boundsOf(worldPoly)
                // ملحوظة: x/y هنا لسه بمساحة "القابلة للاستخدام" (من غير هامش الحواف)
                // عشان اختبار التصادم مع القطع الأخرى في نفس اللوح يفضل متسق. هامش
                // الحواف بيتضاف مرة واحدة بس لحظة إقفال اللوح في finalizeBoard.
                currentBoardPieces += NestingPiece(
                    index = copyIndex,
                    polygon = piece,
                    x = placement.offsetX,
                    y = placement.offsetY,
                    rotationDeg = placement.rotationDeg,
                    boundsWidth = bounds.width,
                    boundsHeight = bounds.height
                )
                placedCount++
            }
            // لو حتى اللوح الجديد الفاضي مقدرش يستوعب القطعة (القطعة أكبر من
            // اللوح نفسه) بنسيبها من غير رص وبنكمل - القطعة دي مش قابلة للرص أصلاً.

            onProgress?.invoke(
                NestingProgress(
                    placed = placedCount,
                    total = totalRequested,
                    boardIndex = boardIndex,
                    percent = ((copyIndex + 1) * 100 / max(1, totalRequested)),
                    stage = NestingStage.NESTING,
                    stagePercent = ((copyIndex + 1) * 100 / max(1, totalRequested)),
                    stageLabel = "جاري الرص"
                )
            )
        }

        // اقفل آخر لوح (سواء فيه قطع أو حتى فاضي لو محصلش رص خالص)
        boards += finalizeBoard(boardIndex, currentBoardPieces, config)

        val elapsed = System.currentTimeMillis() - startTime
        return NestingResult(
            boards = boards,
            totalRequested = totalRequested,
            totalPlaced = placedCount,
            sourceWidth = pieceBounds.width,
            sourceHeight = pieceBounds.height,
            sourceArea = sourceOuterArea,
            elapsedMs = elapsed
        )
    }

    /** بيقفل لوح: بياخد إحداثيات القطع (بمساحة "القابل للاستخدام") ويضيفلها هامش
     *  الحواف مرة واحدة بس، عشان تبقى جاهزة للعرض/الإخراج بإحداثيات اللوح الكاملة. */
    private fun finalizeBoard(index: Int, pieces: List<NestingPiece>, config: NestingConfig): NestingBoard {
        val shifted = pieces.map { it.copy(x = it.x + config.edgeLeftMm, y = it.y + config.edgeTopMm) }
        return NestingBoard(
            index = index,
            width = config.boardWidth,
            height = config.boardHeight,
            pieces = shifted,
            color = config.boardColor
        )
    }

    // ====================== البحث عن أفضل مكان لقطعة واحدة ======================

    private data class Placement(val offsetX: Double, val offsetY: Double, val rotationDeg: Double)

    private fun findPlacement(
        existing: List<NestingPiece>,
        piece: NestingPolygon,
        rotations: List<Double>,
        usableWidth: Double,
        usableHeight: Double,
        clearance: Double,
        step: Double
    ): Placement? {
        // بنحول القطع الموجودة بالفعل على اللوح لإحداثيات عالمية جاهزة للمقارنة
        val placedPolys = existing.map { p ->
            transform(p.polygon.outer, p.rotationDeg, p.x, p.y)
        }

        var best: Placement? = null

        for (rotationDeg in rotations) {
            val rotatedLocal = rotate(piece.outer, rotationDeg)
            val localBounds = boundsOf(rotatedLocal)
            // بنطبّع الشكل عشان أصغر إحداثي يبقى صفر، فبعدين offsetX/Y = ركن الصندوق فعلياً
            val normalized = rotatedLocal.map {
                NestingPoint(it.x - localBounds.minX, it.y - localBounds.minY)
            }
            val w = localBounds.width
            val h = localBounds.height
            if (w > usableWidth || h > usableHeight) continue // القطعة أكبر من اللوح بالاتجاه ده

            // مسح Bottom-Left: بندور من أسفل لأعلى، ومن اليسار لليمين، وناخد أول
            // مكان يثبت فيه إن مفيش تصادم - ده بيدّي رص متلاصق وكفاءة عالية
            var y = 0.0
            while (y + h <= usableHeight) {
                var x = 0.0
                while (x + w <= usableWidth) {
                    val candidate = normalized.map { NestingPoint(it.x + x, it.y + y) }
                    if (fits(candidate, placedPolys, clearance)) {
                        best = Placement(x, y, rotationDeg)
                        return best // أول مكان صالح في المسح من أسفل-يسار كافي (bottom-left heuristic)
                    }
                    x += step
                }
                y += step
            }
        }
        return best
    }

    /** هل شكل مرشح (candidate) يقدر يتحط من غير تصادم أو تعدي على مسافة الأمان؟ */
    private fun fits(candidate: List<NestingPoint>, placed: List<List<NestingPoint>>, clearance: Double): Boolean {
        for (other in placed) {
            // فحص سريع بالـ bounding box الأول (أرخص بكتير من فحص الحواف الكامل)
            val a = boundsOf(candidate)
            val b = boundsOf(other)
            if (a.maxX + clearance < b.minX || b.maxX + clearance < a.minX ||
                a.maxY + clearance < b.minY || b.maxY + clearance < a.minY
            ) continue // بعيدين عن بعض خالص، تجاوز الفحص الدقيق

            if (polygonsOverlap(candidate, other)) return false
            if (clearance > 0.0 && minDistance(candidate, other) < clearance) return false
        }
        return true
    }

    // ====================== أدوات هندسية (Geometry helpers) ======================

    private data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val width get() = maxX - minX
        val height get() = maxY - minY
    }

    private fun boundsOf(p: List<NestingPoint>): Bounds {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (pt in p) {
            if (pt.x < minX) minX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.x > maxX) maxX = pt.x
            if (pt.y > maxY) maxY = pt.y
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    private fun rotate(points: List<NestingPoint>, deg: Double): List<NestingPoint> {
        if (deg == 0.0) return points
        val r = Math.toRadians(deg)
        val c = cos(r); val s = sin(r)
        return points.map { NestingPoint(it.x * c - it.y * s, it.x * s + it.y * c) }
    }

    private fun transform(points: List<NestingPoint>, deg: Double, dx: Double, dy: Double): List<NestingPoint> {
        val rotated = rotate(points, deg)
        val b = boundsOf(rotated)
        // القطع بتتخزن بحيث offsetX/Y هو ركن الصندوق المحيط بعد التطبيع (زي findPlacement)
        return rotated.map { NestingPoint(it.x - b.minX + dx, it.y - b.minY + dy) }
    }

    private fun rotationCandidates(config: NestingConfig): List<Double> {
        val base = when (config.rotationMode) {
            RotationMode.HORIZONTAL -> listOf(0.0, 180.0)
            RotationMode.VERTICAL -> listOf(90.0, 270.0)
            RotationMode.FREE -> {
                val stepDeg = config.rotationStepDeg.coerceAtLeast(1.0)
                var d = 0.0
                val list = mutableListOf<Double>()
                while (d < 360.0) { list += d; d += stepDeg }
                list
            }
        }
        return base
    }

    /** تقاطع بوليجونين حقيقي: بيفحص تقاطع كل ضلع مع كل ضلع، وكمان احتواء بالكامل (شكل جوه التاني) */
    private fun polygonsOverlap(a: List<NestingPoint>, b: List<NestingPoint>): Boolean {
        for (i in a.indices) {
            val a1 = a[i]; val a2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                val b1 = b[j]; val b2 = b[(j + 1) % b.size]
                if (segmentsIntersect(a1, a2, b1, b2)) return true
            }
        }
        // مفيش تقاطع أضلاع، لكن ممكن شكل يكون بالكامل جوه التاني (زي فتحة كبيرة محتواة)
        if (a.isNotEmpty() && NestingShapeBuilder.pointInPolygon(a[0], b)) return true
        if (b.isNotEmpty() && NestingShapeBuilder.pointInPolygon(b[0], a)) return true
        return false
    }

    private fun segmentsIntersect(p1: NestingPoint, p2: NestingPoint, p3: NestingPoint, p4: NestingPoint): Boolean {
        fun cross(o: NestingPoint, a: NestingPoint, b: NestingPoint) =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val d1 = cross(p3, p4, p1)
        val d2 = cross(p3, p4, p2)
        val d3 = cross(p1, p2, p3)
        val d4 = cross(p1, p2, p4)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        ) return true
        // حالات التماس/التوازي على نفس الخط (نادرة لكن بنغطيها للأمان)
        if (d1 == 0.0 && onSegment(p3, p4, p1)) return true
        if (d2 == 0.0 && onSegment(p3, p4, p2)) return true
        if (d3 == 0.0 && onSegment(p1, p2, p3)) return true
        if (d4 == 0.0 && onSegment(p1, p2, p4)) return true
        return false
    }

    private fun onSegment(a: NestingPoint, b: NestingPoint, p: NestingPoint): Boolean {
        return min(a.x, b.x) <= p.x && p.x <= max(a.x, b.x) &&
            min(a.y, b.y) <= p.y && p.y <= max(a.y, b.y)
    }

    /** أقل مسافة حقيقية بين حواف بوليجونين (لاستخدامها في فحص مسافة الأمان clearance) */
    private fun minDistance(a: List<NestingPoint>, b: List<NestingPoint>): Double {
        var best = Double.MAX_VALUE
        for (i in a.indices) {
            val a1 = a[i]; val a2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                val b1 = b[j]; val b2 = b[(j + 1) % b.size]
                best = min(best, segmentDistance(a1, a2, b1, b2))
                if (best <= 0.0) return 0.0
            }
        }
        return best
    }

    private fun segmentDistance(p1: NestingPoint, p2: NestingPoint, p3: NestingPoint, p4: NestingPoint): Double {
        return minOf(
            pointToSegmentDistance(p1, p3, p4),
            pointToSegmentDistance(p2, p3, p4),
            pointToSegmentDistance(p3, p1, p2),
            pointToSegmentDistance(p4, p1, p2)
        )
    }

    private fun pointToSegmentDistance(p: NestingPoint, a: NestingPoint, b: NestingPoint): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return NestingShapeBuilder.distance(p, a)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        val proj = NestingPoint(a.x + t * dx, a.y + t * dy)
        return NestingShapeBuilder.distance(p, proj)
    }
}
