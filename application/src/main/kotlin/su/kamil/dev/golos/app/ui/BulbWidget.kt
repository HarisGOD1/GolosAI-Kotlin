package su.kamil.dev.golos.app.ui

import java.awt.*
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Custom Swing component representing a photorealistic glowing indicator bulb lamp.
 * Renders a 3D spherical lamp with radial gradient highlights, luminous aura halo,
 * and metallic bezel border.
 */
class BulbWidget(
    var bulbColor: Color = Color(46, 204, 113),
    var glowColor: Color = Color(46, 204, 113, 140),
    var title: String = "STATUS",
    var statusText: String = "ACTIVE",
    var compact: Boolean = false,
) : JPanel() {

    init {
        isOpaque = false
        border = EmptyBorder(4, 6, 4, 6)
        updatePreferredSize()
    }

    private fun updatePreferredSize() {
        if (compact) {
            preferredSize = Dimension(120, 44)
            minimumSize = Dimension(90, 40)
        } else {
            preferredSize = Dimension(160, 56)
            minimumSize = Dimension(120, 50)
        }
    }

    fun updateState(
        color: Color,
        glow: Color,
        titleText: String,
        stateText: String,
    ) {
        this.bulbColor = color
        this.glowColor = glow
        this.title = titleText
        this.statusText = stateText
        toolTipText = "$title: $statusText"
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val width = width
        val height = height

        val bulbDiameter = if (compact) 18 else 24
        val bulbX = 8
        val bulbY = (height - bulbDiameter) / 2

        // 1. Soft Luminous Glow Halo
        val haloDiameter = bulbDiameter + 10
        val haloX = bulbX - 5
        val haloY = bulbY - 5
        val haloCenter = Point2D.Float(haloX + haloDiameter / 2f, haloY + haloDiameter / 2f)
        val haloDist = floatArrayOf(0.0f, 0.6f, 1.0f)
        val haloColors = arrayOf(
            Color(glowColor.red, glowColor.green, glowColor.blue, (glowColor.alpha * 0.7f).toInt().coerceIn(0, 255)),
            Color(glowColor.red, glowColor.green, glowColor.blue, (glowColor.alpha * 0.25f).toInt().coerceIn(0, 255)),
            Color(glowColor.red, glowColor.green, glowColor.blue, 0),
        )
        val haloPaint = RadialGradientPaint(haloCenter, haloDiameter / 2f, haloDist, haloColors)
        g2d.paint = haloPaint
        g2d.fill(Ellipse2D.Float(haloX.toFloat(), haloY.toFloat(), haloDiameter.toFloat(), haloDiameter.toFloat()))

        // 2. Bulb 3D Spherical Surface with Highlight Focus
        val bulbCenter = Point2D.Float(bulbX + bulbDiameter * 0.35f, bulbY + bulbDiameter * 0.35f)
        val bulbDist = floatArrayOf(0.0f, 0.45f, 0.85f, 1.0f)

        val highlightColor = Color(
            (bulbColor.red + 180).coerceAtMost(255),
            (bulbColor.green + 180).coerceAtMost(255),
            (bulbColor.blue + 180).coerceAtMost(255),
        )
        val midColor = bulbColor
        val rimColor = Color(
            (bulbColor.red * 0.55f).toInt(),
            (bulbColor.green * 0.55f).toInt(),
            (bulbColor.blue * 0.55f).toInt(),
        )

        val bulbColors = arrayOf(highlightColor, midColor, rimColor, rimColor.darker())
        val bulbPaint = RadialGradientPaint(bulbCenter, bulbDiameter * 0.75f, bulbDist, bulbColors)
        g2d.paint = bulbPaint
        g2d.fill(Ellipse2D.Float(bulbX.toFloat(), bulbY.toFloat(), bulbDiameter.toFloat(), bulbDiameter.toFloat()))

        // 3. Dark Outer Bezel Ring
        g2d.color = Color(40, 45, 55, 180)
        g2d.stroke = BasicStroke(1.2f)
        g2d.draw(Ellipse2D.Float(bulbX.toFloat(), bulbY.toFloat(), bulbDiameter.toFloat(), bulbDiameter.toFloat()))

        // 4. Specular White Highlight Reflection
        val specW = bulbDiameter * 0.35f
        val specH = bulbDiameter * 0.22f
        val specX = bulbX + bulbDiameter * 0.2f
        val specY = bulbY + bulbDiameter * 0.16f
        g2d.color = Color(255, 255, 255, 170)
        g2d.fill(Ellipse2D.Float(specX, specY, specW, specH))

        // 5. Text Information (Title and Value)
        val textX = bulbX + bulbDiameter + (if (compact) 8 else 12)
        val titleFont = FontManager.bold(if (compact) 10f else 11f)
        val statusFont = FontManager.bold(if (compact) 12f else 13f)

        g2d.font = titleFont
        g2d.color = Color(110, 120, 135)
        val titleY = if (compact) height / 2 - 2 else height / 2 - 4
        g2d.drawString(title.uppercase(), textX, titleY)

        g2d.font = statusFont
        g2d.color = Color(35, 40, 50)
        val statusY = if (compact) height / 2 + 12 else height / 2 + 14

        // Bound status string to prevent clipping
        val maxTextWidth = width - textX - 4
        var displayStatus = statusText
        val fm = g2d.getFontMetrics(statusFont)
        if (fm.stringWidth(displayStatus) > maxTextWidth && maxTextWidth > 20) {
            while (displayStatus.length > 3 && fm.stringWidth("$displayStatus...") > maxTextWidth) {
                displayStatus = displayStatus.dropLast(1)
            }
            displayStatus = "$displayStatus..."
        }
        g2d.drawString(displayStatus, textX, statusY)

        g2d.dispose()
    }
}
