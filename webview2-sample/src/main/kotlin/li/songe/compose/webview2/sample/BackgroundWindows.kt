package li.songe.compose.webview2.sample

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyEvent
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/** Sample test mode only: prevent all current and future popup/dialog windows from activating. */
object BackgroundWindows {
    var activations = 0
        private set
    fun install(): AutoCloseable {
        val seen = java.util.WeakHashMap<Window, Boolean>()
        val listener = AWTEventListener { event ->
            if (event is WindowEvent && event.id == WindowEvent.WINDOW_ACTIVATED) activations++
            if (event is HierarchyEvent && event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
                val window = event.component as? Window ?: SwingUtilities.getWindowAncestor(event.component)
                if (window != null) {
                    window.isAutoRequestFocus = false
                    window.focusableWindowState = false
                    if (seen.put(window, true) == null) window.addPropertyChangeListener("focusableWindowState") {
                        if (it.newValue == true) window.focusableWindowState = false
                    }
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.HIERARCHY_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK)
        return AutoCloseable { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
}
