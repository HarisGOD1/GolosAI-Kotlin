package su.kamil.dev.golos.app.ui

import su.kamil.dev.golos.app.DictationOrchestrator
import su.kamil.dev.golos.core.model.DictationState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToolTip
import javax.swing.JWindow
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * Lightweight, undecorated floating indicator bar window.
 * Directly bypasses OS Window Manager decorated min-size clamping (~480x360),
 * achieving a true compact floating indicator bar (~480x54).
 */
class IndicatorFloatingBar(
    owner: Frame?,
    private val orchestrator: DictationOrchestrator,
    val miniAppBulb: BulbWidget,
    val miniVoiceBulb: BulbWidget,
    val miniModeBulb: BulbWidget,
    val expandAction: () -> Unit,
    val exitAction: () -> Unit,
) : JWindow(owner) {
    val contentBarPanel = JPanel()
    val miniPttButton = JButton("Speak")
    val expandBtn =
        object : JButton("[+] " + AppLocalization.tr("btn.expand")) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }
    val closeBtn =
        object : JButton("X") {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }

    private var mouseOffset: Point? = null

    init {
        isAlwaysOnTop = true
        layout = BorderLayout()

        contentBarPanel.border =
            CompoundBorder(
                LineBorder(Color(195, 205, 220), 1, true),
                EmptyBorder(5, 10, 5, 10),
            )
        contentBarPanel.background = Color(245, 247, 250)
        contentBarPanel.layout = BoxLayout(contentBarPanel, BoxLayout.X_AXIS)

        val bulbsBox = JPanel(GridLayout(1, 3, 6, 0))
        bulbsBox.isOpaque = false
        bulbsBox.add(miniAppBulb)
        bulbsBox.add(miniVoiceBulb)
        bulbsBox.add(miniModeBulb)

        val actionsBox = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        actionsBox.isOpaque = false

        styleMinimalistButton(miniPttButton)
        miniPttButton.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    orchestrator.onPushToTalkPressed()
                }

                override fun mouseReleased(e: MouseEvent?) {
                    orchestrator.onPushToTalkReleased()
                }
            },
        )
        actionsBox.add(miniPttButton)

        styleMinimalistButton(expandBtn, isAccent = true)
        expandBtn.addActionListener { expandAction() }
        actionsBox.add(expandBtn)

        styleMinimalistButton(closeBtn)
        closeBtn.foreground = Color(180, 40, 40)
        closeBtn.toolTipText = AppLocalization.tr("btn.exit")
        closeBtn.addActionListener { exitAction() }
        actionsBox.add(closeBtn)

        contentBarPanel.add(bulbsBox)
        contentBarPanel.add(Box.createHorizontalGlue())
        contentBarPanel.add(actionsBox)

        val dragListener =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    mouseOffset = e.point
                }

                override fun mouseDragged(e: MouseEvent) {
                    mouseOffset?.let { offset ->
                        val cur = location
                        setLocation(cur.x + e.x - offset.x, cur.y + e.y - offset.y)
                    }
                }
            }
        contentBarPanel.addMouseListener(dragListener)
        contentBarPanel.addMouseMotionListener(dragListener)

        add(contentBarPanel, BorderLayout.CENTER)
        val barWidth = 480
        val barHeight = 54
        size = Dimension(barWidth, barHeight)
    }

    private fun createCleanToolTip(parent: JComponent): JToolTip {
        return object : JToolTip() {
            init {
                component = parent
                font = FontManager.regular(FontManager.SMALL_SIZE)
            }
        }
    }

    private fun styleMinimalistButton(
        btn: JButton,
        isAccent: Boolean = false,
    ) {
        btn.font = FontManager.regular(FontManager.INDICATOR_SIZE)
        btn.margin = java.awt.Insets(4, 8, 4, 8)
        btn.isFocusPainted = false
        if (!isAccent) {
            btn.background = Color(248, 249, 250)
            btn.border = CompoundBorder(LineBorder(Color(215, 220, 225), 1, true), EmptyBorder(4, 8, 4, 8))
        } else {
            btn.background = Color(238, 244, 255)
            btn.foreground = Color(30, 90, 200)
            btn.border = CompoundBorder(LineBorder(Color(180, 205, 245), 1, true), EmptyBorder(4, 8, 4, 8))
        }
    }

    fun updateStatus(state: DictationState) {
        when (state) {
            DictationState.IDLE -> {
                miniPttButton.text = "Speak"
                miniPttButton.background = null
            }
            DictationState.RECORDING -> {
                miniPttButton.text = "[REC]"
                miniPttButton.background = Color(255, 235, 235)
            }
            DictationState.PROCESSING -> {
                miniPttButton.text = "[...]"
                miniPttButton.background = Color(245, 245, 245)
            }
        }
    }
}
