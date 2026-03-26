package com.huawei.agenticmode.debug

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.project.Project
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.LayoutManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.accessibility.AccessibleContext
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.border.Border

class ComponentDebugHelper(
    private val project: Project
) : Disposable {

    private var installed = false

    private val listener = AWTEventListener { event ->
        when (event) {
            is MouseEvent -> handleMouseEvent(event)
            is KeyEvent -> handleKeyEvent(event)
        }
    }

    fun install() {
        if (installed) return
        installed = true

        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK
        )
        println("ComponentDebugHelper installed")
    }

    override fun dispose() {
        if (!installed) return
        installed = false
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        println("ComponentDebugHelper disposed")
    }

    private fun handleMouseEvent(e: MouseEvent) {
        if (e.id != MouseEvent.MOUSE_PRESSED) return
        if (!e.isAltDown) return

        val src = e.source as? Component ?: return
        val deepest = SwingUtilities.getDeepestComponentAt(src, e.x, e.y)

        println("\n================ COMPONENT DEBUG (MOUSE) ================")
        println("Mouse event: button=${e.button}, clickCount=${e.clickCount}, point=(${e.x}, ${e.y}), alt=${e.isAltDown}")
        println("Source = ${briefComponentInfo(src)}")
        println("Deepest = ${briefComponentInfo(deepest)}")

        val target = deepest ?: src
        dumpComponentReport("TARGET REPORT", target)

        println("=========================================================\n")
    }

    private fun handleKeyEvent(e: KeyEvent) {
        if (e.id != KeyEvent.KEY_PRESSED) return

        when {
            e.keyCode == KeyEvent.VK_T && e.isControlDown && e.isAltDown -> {
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                println("\n================ COMPONENT DEBUG (FOCUS) ================")
                println("Focus owner = ${briefComponentInfo(focusOwner)}")
                if (focusOwner != null) {
                    dumpComponentReport("FOCUS REPORT", focusOwner)
                }
                println("=========================================================\n")
            }

            e.keyCode == KeyEvent.VK_Y && e.isControlDown && e.isAltDown -> {
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                println("\n================ COMPONENT TREE DEBUG ===================")
                println("Focus owner = ${briefComponentInfo(focusOwner)}")
                if (focusOwner != null) {
                    val root = SwingUtilities.getRoot(focusOwner)
                    println("Root = ${briefComponentInfo(root)}")
                    if (root != null) {
                        dumpComponentTree(root, maxDepth = 4)
                    }
                }
                println("=========================================================\n")
            }
        }
    }

    private fun dumpComponentReport(title: String, component: Component) {
        println("---- $title ----")

        dumpBasicComponentInfo(component)
        dumpComponentSpecialInfo(component)
        dumpClientProperties(component)
        dumpDataContextInfo(component)
        dumpParentChain("Parent chain", component)
        dumpInterestingAncestors(component)
        dumpDirectChildren(component)

        println("---- END OF $title ----")
    }

    private fun dumpBasicComponentInfo(component: Component) {
        println("[Basic]")
        println("class = ${component.javaClass.name}")
        println("objectId = ${objectId(component)}")
        println("name = ${component.name}")
        println("bounds = ${component.bounds}")
        println("location = ${component.location}")
        println("size = ${component.size}")
        println("preferredSize = ${safePreferredSize(component)}")
        println("minimumSize = ${safeMinimumSize(component)}")
        println("maximumSize = ${safeMaximumSize(component)}")
        println("visible = ${component.isVisible}, showing = ${component.isShowing}, enabled = ${component.isEnabled}")
        println("focusable = ${component.isFocusable}, valid = ${component.isValid}")
        println("parent = ${briefComponentInfo(component.parent)}")
        println("foreground = ${runCatching { component.foreground }.getOrNull()}")
        println("background = ${runCatching { component.background }.getOrNull()}")
        println("font = ${runCatching { component.font }.getOrNull()}")

        if (component is JComponent) {
            println("opaque = ${component.isOpaque}")
            println("doubleBuffered = ${component.isDoubleBuffered}")
            println("toolTipText = ${component.toolTipText}")
            println("border = ${safeBorder(component.border)}")
            println("alignmentX = ${component.alignmentX}, alignmentY = ${component.alignmentY}")
            println("autoscrolls = ${component.autoscrolls}")
            println("inheritsPopupMenu = ${component.inheritsPopupMenu}")
            println("componentPopupMenu = ${briefComponentInfo(component.componentPopupMenu)}")
        }

        if (component is Container) {
            println("layout = ${safeLayout(component.layout)}")
            println("componentCount = ${component.componentCount}")
        }

        println("accessible = ${safeAccessible(component.accessibleContext)}")
    }

    private fun dumpComponentSpecialInfo(component: Component) {
        println("[Special]")

        when (component) {
            is ActionToolbarImpl -> dumpActionToolbar(component)
            is ActionButton -> dumpActionButton(component)
            is ActionToolbar -> dumpActionToolbarInterface(component)
            is AbstractButton -> dumpAbstractButton(component)
            is JTabbedPane -> dumpTabbedPane(component)
            is JTree -> dumpTree(component)
            is JList<*> -> dumpList(component)
            is JTable -> dumpTable(component)
            is JScrollPane -> dumpScrollPane(component)
            is JViewport -> dumpViewport(component)
            is JRootPane -> dumpRootPane(component)
            is Window -> dumpWindow(component)
            else -> println("No specialized dump for this component type.")
        }

        dumpIntellijUiHints(component)
    }

    private fun dumpClientProperties(component: Component) {
        if (component !is JComponent) return

        println("[ClientProperties]")
        val keys = runCatching {
            component.javaClass.methods
                .firstOrNull { it.name == "getClientProperties" && it.parameterCount == 0 }
                ?.invoke(component)
        }.getOrNull()

        if (keys != null) {
            println("getClientProperties() = $keys")
            return
        }

        val knownKeys = listOf(
            "ActionToolbar.smallVariant",
            "JComponent.sizeVariant",
            "styleId",
            "html.disable",
            "JTree.lineStyle"
        )

        var found = false
        for (key in knownKeys) {
            val value = component.getClientProperty(key)
            if (value != null) {
                found = true
                println("$key = $value")
            }
        }
        if (!found) {
            println("<no known client properties found>")
        }
    }

    private fun dumpDataContextInfo(component: Component) {
        println("[DataContext]")

        val dataContext = DataManager.getInstance().getDataContext(component)
        val ctxProject = CommonDataKeys.PROJECT.getData(dataContext)
        val fileEditor = PlatformDataKeys.FILE_EDITOR.getData(dataContext)
        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        val virtualFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)

        println("project = ${ctxProject?.name} (${if (ctxProject === project) "same-project" else "other-project"})")
        println("fileEditor = ${fileEditor?.javaClass?.name}")
        println("editor = ${editor?.javaClass?.name}")
        println("virtualFile = ${virtualFile?.path}")
    }

    private fun dumpParentChain(title: String, component: Component) {
        println("[$title]")
        collectParentChain(component).forEachIndexed { index, c ->
            println("[$index] id=${objectId(c)} ${briefComponentInfo(c)}")
        }
    }

    private fun dumpInterestingAncestors(component: Component) {
        println("[InterestingAncestors]")

        var cur: Component? = component
        var found = false
        var index = 0

        while (cur != null) {
            val tags = buildList {
                if (cur is ActionToolbarImpl) add("ActionToolbarImpl")
                if (cur is ActionToolbar) add("ActionToolbar")
                if (cur is ActionButton) add("ActionButton")
                if (cur is JTabbedPane) add("JTabbedPane")
                if (cur is JScrollPane) add("JScrollPane")
                if (cur is JViewport) add("JViewport")
                if (cur is JRootPane) add("JRootPane")
                if (cur is Window) add("Window")

                val name = cur!!.javaClass.name
                if (name.contains("JBTabsImpl")) add("JBTabsImpl?")
                if (name.contains("TabLabel")) add("TabLabel?")
                if (name.contains("EditorTabbedContainer")) add("EditorTabbedContainer?")
                if (name.contains("EditorsSplitters")) add("EditorsSplitters?")
                if (name.contains("ToolWindow")) add("ToolWindow?")
            }

            if (tags.isNotEmpty()) {
                found = true
                println("[$index] ${cur.javaClass.name}  tags=${tags.joinToString()}")
            }

            index++
            cur = cur.parent
        }

        if (!found) {
            println("<none>")
        }
    }

    private fun dumpDirectChildren(component: Component) {
        if (component !is Container) return

        println("[DirectChildren]")
        if (component.componentCount == 0) {
            println("<no children>")
            return
        }

        component.components.forEachIndexed { index, child ->
            println("[$index] ${briefComponentInfo(child)}")
        }
    }

    private fun dumpActionToolbar(toolbar: ActionToolbarImpl) {
        println("ActionToolbarImpl:")
        println("  place = ${safeToolbarPlace(toolbar)}")
        println("  targetComponent = ${briefComponentInfo(toolbar.targetComponent)}")
        println("  orientation = ${runCatching { toolbar.orientation }.getOrNull()}")

        val buttons = toolbar.components.filterIsInstance<ActionButton>()
        println("  actionButtons.count = ${buttons.size}")
        buttons.forEachIndexed { i, btn ->
            val actionClass = btn.action.javaClass.name
            val text = btn.action.templatePresentation.text
            val desc = btn.action.templatePresentation.description
            println("    [$i] action=$actionClass, text=$text, desc=$desc, bounds=${btn.bounds}")
        }
    }

    private fun dumpActionToolbarInterface(toolbar: ActionToolbar) {
        println("ActionToolbar:")
        println("  class = ${toolbar.javaClass.name}")
        println("  component = ${briefComponentInfo(toolbar.component)}")
    }

    private fun dumpActionButton(button: ActionButton) {
        println("ActionButton:")
        println("  action.class = ${button.action.javaClass.name}")
        println("  action.text = ${button.action.templatePresentation.text}")
        println("  action.description = ${button.action.templatePresentation.description}")
    }

    private fun dumpAbstractButton(button: AbstractButton) {
        println("AbstractButton:")
        println("  text = ${button.text}")
        println("  selected = ${button.isSelected}")
        println("  model = ${button.model?.javaClass?.name}")
        println("  margin = ${button.margin}")
        println("  icon = ${button.icon}")
        println("  pressedIcon = ${button.pressedIcon}")
    }

    private fun dumpTabbedPane(tabbedPane: JTabbedPane) {
        println("JTabbedPane:")
        println("  tabCount = ${tabbedPane.tabCount}")
        println("  selectedIndex = ${tabbedPane.selectedIndex}")
        for (i in 0 until tabbedPane.tabCount) {
            println(
                "    [$i] title=${tabbedPane.getTitleAt(i)}, component=${
                    briefComponentInfo(
                        tabbedPane.getComponentAt(
                            i
                        )
                    )
                }"
            )
        }
    }

    private fun dumpTree(tree: JTree) {
        println("JTree:")
        println("  rowCount = ${tree.rowCount}")
        println("  selectionCount = ${tree.selectionCount}")
        println("  isRootVisible = ${tree.isRootVisible}")
        println("  showsRootHandles = ${tree.showsRootHandles}")
        println("  model = ${tree.model?.javaClass?.name}")
    }

    private fun dumpList(list: JList<*>) {
        println("JList:")
        println("  model = ${list.model.javaClass.name}")
        println("  selectedIndex = ${list.selectedIndex}")
        println("  visibleRowCount = ${list.visibleRowCount}")
        println("  layoutOrientation = ${list.layoutOrientation}")
    }

    private fun dumpTable(table: JTable) {
        println("JTable:")
        println("  rowCount = ${table.rowCount}, columnCount = ${table.columnCount}")
        println("  selectedRow = ${table.selectedRow}, selectedColumn = ${table.selectedColumn}")
        println("  autoResizeMode = ${table.autoResizeMode}")
        println("  model = ${table.model.javaClass.name}")
    }

    private fun dumpScrollPane(scrollPane: JScrollPane) {
        println("JScrollPane:")
        println("  viewport = ${briefComponentInfo(scrollPane.viewport)}")
        println("  view = ${briefComponentInfo(scrollPane.viewport?.view)}")
        println("  rowHeader = ${briefComponentInfo(scrollPane.rowHeader)}")
        println("  columnHeader = ${briefComponentInfo(scrollPane.columnHeader)}")
    }

    private fun dumpViewport(viewport: JViewport) {
        println("JViewport:")
        println("  view = ${briefComponentInfo(viewport.view)}")
        println("  viewPosition = ${viewport.viewPosition}")
        println("  extentSize = ${viewport.extentSize}")
    }

    private fun dumpRootPane(rootPane: JRootPane) {
        println("JRootPane:")
        println("  contentPane = ${briefComponentInfo(rootPane.contentPane)}")
        println("  layeredPane = ${briefComponentInfo(rootPane.layeredPane)}")
        println("  glassPane = ${briefComponentInfo(rootPane.glassPane)}")
        println("  defaultButton = ${briefComponentInfo(rootPane.defaultButton)}")
    }

    private fun dumpWindow(window: Window) {
        println("Window:")
        println("  owner = ${briefComponentInfo(window.owner)}")
        println("  focusOwner = ${briefComponentInfo(window.focusOwner)}")
        println("  mostRecentFocusOwner = ${briefComponentInfo(window.mostRecentFocusOwner)}")
        println("  ownedWindows.count = ${window.ownedWindows.size}")
    }

    private fun dumpIntellijUiHints(component: Component) {
        println("[IntelliJHints]")

        val className = component.javaClass.name
        val hints = buildList {
            if (className.contains("ActionButton")) add("ActionButton-like")
            if (className.contains("ActionToolbar")) add("ActionToolbar-like")
            if (className.contains("JBTabsImpl")) add("JBTabsImpl-like")
            if (className.contains("TabLabel")) add("TabLabel-like")
            if (className.contains("EditorTabbedContainer")) add("EditorTabbedContainer-like")
            if (className.contains("EditorsSplitters")) add("EditorsSplitters-like")
            if (className.contains("StripeButton")) add("StripeButton-like")
            if (className.contains("ToolWindow")) add("ToolWindow-like")
        }

        if (hints.isEmpty()) {
            println("<none>")
        } else {
            println(hints.joinToString())
        }
    }

    private fun dumpComponentTree(root: Component, maxDepth: Int = 4) {
        fun dfs(node: Component, depth: Int) {
            if (depth > maxDepth) return

            val indent = "  ".repeat(depth)
            println("$indent- ${treeLine(node)}")

            if (node is Container) {
                node.components.forEach { child ->
                    dfs(child, depth + 1)
                }
            }
        }

        dfs(root, 0)
    }

    private fun treeLine(component: Component): String {
        val extras = buildList {
            component.name?.let { add("name=$it") }
            if (component is JComponent && component.toolTipText != null) add("tooltip=${component.toolTipText}")
            if (component is ActionButton) {
                add("action=${component.action.javaClass.simpleName}")
                component.action.templatePresentation.text?.let { add("text=$it") }
            }
        }.joinToString(", ")

        return "${component.javaClass.name} bounds=${component.bounds}" +
                if (extras.isNotEmpty()) " [$extras]" else ""
    }

    private fun collectParentChain(component: Component): List<Component> {
        val result = mutableListOf<Component>()
        var cur: Component? = component
        while (cur != null) {
            result += cur
            cur = cur.parent
        }
        return result
    }

    private fun safeToolbarPlace(toolbar: ActionToolbarImpl): String {
        return try {
            val method = toolbar.javaClass.methods.firstOrNull {
                it.name == "getPlace" && it.parameterCount == 0
            }
            method?.invoke(toolbar)?.toString() ?: "<unknown>"
        } catch (t: Throwable) {
            "<error:${t.javaClass.simpleName}>"
        }
    }

    private fun safePreferredSize(component: Component) =
        runCatching { component.preferredSize }.getOrNull()

    private fun safeMinimumSize(component: Component) =
        runCatching { component.minimumSize }.getOrNull()

    private fun safeMaximumSize(component: Component) =
        runCatching { component.maximumSize }.getOrNull()

    private fun safeLayout(layout: LayoutManager?): String {
        return layout?.javaClass?.name ?: "null"
    }

    private fun safeBorder(border: Border?): String {
        return border?.javaClass?.name ?: "null"
    }

    private fun safeAccessible(accessible: AccessibleContext?): String {
        if (accessible == null) return "null"
        return buildString {
            append("name=${accessible.accessibleName}, ")
            append("description=${accessible.accessibleDescription}, ")
            append("role=${accessible.accessibleRole}")
        }
    }

    private fun briefComponentInfo(c: Component?): String {
        if (c == null) return "null"

        val extras = buildList {
            c.name?.let { add("name=$it") }

            if (c is JComponent) {
                c.toolTipText?.let { add("tooltip=$it") }
            }

            if (c is ActionButton) {
                add("action=${c.action.javaClass.name}")
                c.action.templatePresentation.text?.let { add("text=$it") }
            }

            if (c is ActionToolbar) {
                add("toolbar")
            }

            val className = c.javaClass.name
            if (className.contains("JBTabsImpl")) add("JBTabsImpl?")
            if (className.contains("TabLabel")) add("TabLabel?")
            if (className.contains("EditorTabbedContainer")) add("EditorTabbedContainer?")
        }.joinToString(", ")

        return "${c.javaClass.name}(bounds=${c.bounds}, visible=${c.isVisible}, showing=${c.isShowing}" +
                if (extras.isNotEmpty()) ", $extras" else "" +
                        ")"
    }
}

fun objectId(obj: Any?): String {
    if (obj == null) return "null"
    return "${obj.javaClass.name}@${Integer.toHexString(System.identityHashCode(obj))}"
}