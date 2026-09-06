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
 * achieving a true compact floating indicator bar with 3 glowing indicator bulbs.
 */
class IndicatorFloatingBar(
    owner: Frame? = null,
    private val orchestrator: DictationOrchestrator,
    val expandAction: () -> Unit,
    val exitAction: () -> Unit,
) : JWindow(owner) {
    private val greenActive = Color(46, 204, 113)
    private val greenGlow = Color(46, 204, 113, 140)
    private val amberListening = Color(243, 156, 18)
    private val amberGlow = Color(243, 156, 18, 160)
    private val redProcessing = Color(231, 76, 60)
    private val redGlow = Color(231, 76, 60, 160)
    private val blueMode = Color(52, 152, 219)
    private val blueGlow = Color(52, 152, 219, 140)

    val floatingAppBulb =
        BulbWidget(
            bulbColor = greenActive,
            glowColor = greenGlow,
            title = AppLocalization.tr("bulb.app.title"),
            statusText = AppLocalization.tr("bulb.app.active"),
            compact = true,
        )

    val floatingVoiceBulb =
        BulbWidget(
            bulbColor = greenActive,
            glowColor = greenGlow,
            title = AppLocalization.tr("bulb.voice.title"),
            statusText = AppLocalization.tr("bulb.voice.idle"),
            compact = true,
        )

    val floatingModeBulb =
        BulbWidget(
            bulbColor = blueMode,
            glowColor = blueGlow,
            title = AppLocalization.tr("bulb.mode.title"),
            statusText = "DIRECT",
            compact = true,
        )

    val miniAppBulb get() = floatingAppBulb
    val miniVoiceBulb get() = floatingVoiceBulb
    val miniModeBulb get() = floatingModeBulb

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
                EmptyBorder(5, 12, 5, 12),
            )
        contentBarPanel.background = Color(245, 247, 250)
        contentBarPanel.layout = BorderLayout(12, 0)

        val bulbsBox = JPanel(GridLayout(1, 3, 8, 0))
        bulbsBox.isOpaque = false
        bulbsBox.add(floatingAppBulb)
        bulbsBox.add(floatingVoiceBulb)
        bulbsBox.add(floatingModeBulb)

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

        contentBarPanel.add(bulbsBox, BorderLayout.WEST)
        contentBarPanel.add(actionsBox, BorderLayout.EAST)

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
        bulbsBox.addMouseListener(dragListener)
        bulbsBox.addMouseMotionListener(dragListener)

        add(contentBarPanel, BorderLayout.CENTER)
        pack()
        val targetWidth = 560.coerceAtLeast(preferredSize.width)
        val targetHeight = 54.coerceAtLeast(preferredSize.height)
        size = Dimension(targetWidth, targetHeight)
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

    fun updateStatus(
        state: DictationState,
        modeDisplay: String = "DIRECT",
    ) {
        floatingAppBulb.updateState(
            greenActive,
            greenGlow,
            AppLocalization.tr("bulb.app.title"),
            AppLocalization.tr("bulb.app.active"),
        )
        when (state) {
            DictationState.IDLE -> {
                floatingVoiceBulb.updateState(
                    greenActive,
                    greenGlow,
                    AppLocalization.tr("bulb.voice.title"),
                    AppLocalization.tr("bulb.voice.idle"),
                )
                miniPttButton.text = "Speak"
                miniPttButton.background = null
            }
            DictationState.RECORDING -> {
                floatingVoiceBulb.updateState(
                    amberListening,
                    amberGlow,
                    AppLocalization.tr("bulb.voice.title"),
                    AppLocalization.tr("bulb.voice.listening"),
                )
                miniPttButton.text = "[REC]"
                miniPttButton.background = Color(255, 235, 235)
            }
            DictationState.PROCESSING -> {
                floatingVoiceBulb.updateState(
                    redProcessing,
                    redGlow,
                    AppLocalization.tr("bulb.voice.title"),
                    AppLocalization.tr("bulb.voice.processing"),
                )
                miniPttButton.text = "[...]"
                miniPttButton.background = Color(245, 245, 245)
            }
        }
        floatingModeBulb.updateState(
            blueMode,
            blueGlow,
            AppLocalization.tr("bulb.mode.title"),
            modeDisplay,
        )
        contentBarPanel.revalidate()
        contentBarPanel.repaint()
    }

    fun updateLocalization(
        modeDisplay: String = "DIRECT",
        state: DictationState = orchestrator.state.value,
    ) {
        expandBtn.text = "[+] " + AppLocalization.tr("btn.expand")
        expandBtn.toolTipText = AppLocalization.tr("btn.expand")
        closeBtn.toolTipText = AppLocalization.tr("btn.exit")
        updateStatus(state, modeDisplay)
        pack()
        val targetWidth = 560.coerceAtLeast(preferredSize.width)
        val targetHeight = 54.coerceAtLeast(preferredSize.height)
        size = Dimension(targetWidth, targetHeight)
        contentBarPanel.revalidate()
        contentBarPanel.repaint()
    }
}
