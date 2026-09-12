package moe.shizuku.blurview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * A FrameLayout that records a snapshot of its children on a RenderNode.
 * This snapshot is used by the BlurView to apply blur effect.
 */
public class BlurTarget extends FrameLayout {

    /**
     * 全局开关：是否需要「把整棵内容树录进一个离屏 RenderNode」。
     *
     * 关掉实时磨砂时**必须**关掉它。原因：只要这个开关是开的，每帧都会
     * `beginRecording() → 画整棵子树 → endRecording() → drawRenderNode()`，
     * 也就是整个内容区变成一个硬件层。硬件层由 RenderThread 单独合成，
     * 在滚动时它会比窗口背景（壁纸 / 纯色底）慢一帧 —— 观感就是
     * 「整屏背景抖 / 卡片边缘拖影 / 重影」，而且每帧多一遍离屏绘制。
     *
     * 没有 BlurView 在用它的时候，这层离屏 RenderNode 纯粹是白拿的开销 + 风险。
     */
    public static boolean snapshotEnabled = true;

    // RenderEffect is available from API 31, enabling the fully hardware-accelerated blur path.
    static final boolean canUseHardwareRendering = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    // API 29-30: RenderEffect is not available, so the OpenGL path is used with a software snapshot.
    // The OpenGL path draws the target onto a software canvas, so it does NOT use the RenderNode.
    // This is the only path that reads contentGeneration; the other paths leave it untouched.
    static final boolean usesOpenGLBlur = !canUseHardwareRendering
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;

    @Nullable
    RenderNode renderNode;

    // Content version counter, read across frames by the OpenGL blur controller (UI thread) to skip
    // re-capturing when nothing changed. Bumped on every change to the target's drawn content:
    // a re-record in dispatchDraw, and a descendant invalidation (which does not re-run dispatchDraw).
    // Sibling BlurView overlays are not descendants, so their own redraws do not bump it - without
    // that property the controller's invalidate() on each blurred frame would re-trigger itself.
    int contentGeneration;

    {
        if (canUseHardwareRendering) {
            renderNode = new RenderNode("BlurViewHost node");
        }
    }

    public BlurTarget(@NonNull Context context) {
        super(context);
    }

    public BlurTarget(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BlurTarget(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public BlurTarget(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (snapshotEnabled && canUseHardwareRendering && canvas.isHardwareAccelerated()) {
            renderNode.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas recordingCanvas = renderNode.beginRecording();
            super.dispatchDraw(recordingCanvas);
            renderNode.endRecording();
            if (usesOpenGLBlur) {
                contentGeneration++;
            }
            canvas.drawRenderNode(renderNode);
        } else {
            super.dispatchDraw(canvas);
        }
    }

    // A descendant invalidation (e.g. a scrolling list) changes the target's content without
    // re-running dispatchDraw, so the OpenGL path needs this as a separate capture trigger.
    @Override
    public void onDescendantInvalidated(@NonNull View child, @NonNull View descendant) {
        super.onDescendantInvalidated(child, descendant);
        if (usesOpenGLBlur) {
            contentGeneration++;
        }
    }
}
