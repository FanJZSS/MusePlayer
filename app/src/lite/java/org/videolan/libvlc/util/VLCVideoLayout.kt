package org.videolan.libvlc.util

import android.content.Context
import android.widget.FrameLayout

/**
 * 轻量版占位类：不含 libVLC 原生库，仅用于让引用该类型的界面代码通过编译。
 */
class VLCVideoLayout(context: Context) : FrameLayout(context)
