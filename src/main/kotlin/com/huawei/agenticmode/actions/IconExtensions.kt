package com.huawei.agenticmode.actions

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * 将图标缩放到指定尺寸显示，解决 toolbar/SVG 默认尺寸与需求不一致的问题。
 * 使用双线性插值保证缩放质量。
 */
fun Icon.scaledTo(width: Int, height: Int): Icon {
    val base = this
    return object : Icon {
        override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as? Graphics2D ?: return
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.translate(x, y)
                g2.scale(width.toDouble() / base.iconWidth, height.toDouble() / base.iconHeight)
                base.paintIcon(c, g2, 0, 0)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = width
        override fun getIconHeight(): Int = height
    }
}

