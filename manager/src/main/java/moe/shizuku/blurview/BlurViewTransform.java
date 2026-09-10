package moe.shizuku.blurview;

import android.view.View;

/**
 * Encapsulates the layout position of a BlurView in its root's coordinate space,
 * recovered by inverting the view's own scale × rotation transform.
 * <p>
 * {@link View#getLocationOnScreen} returns the <em>visual</em> position of the view's
 * local origin after applying its transforms. This class inverts that to recover the
 * <em>layout</em> position (what it would be with no transforms), which is needed to
 * correctly counter-transform the blur content.
 * <p>
 * The view's transform maps local (0,0) to parent as:
 * <pre>
 *   visual = layout + pivot - Scale × Rotate × pivot
 * </pre>
 * Inverting:
 * <pre>
 *   layoutLeft = visualLeft + pivotX * (scaleX * cosR - 1) - pivotY * scaleY * sinR
 *   layoutTop  = visualTop  + pivotY * (scaleY * cosR - 1) + pivotX * scaleX * sinR
 * </pre>
 */
class BlurViewTransform {

    final float scaleX;
    final float scaleY;
    final float rotationDeg;
    /** Layout (pre-transform) left of the BlurView in root coordinates. */
    final float layoutLeft;
    /** Layout (pre-transform) top of the BlurView in root coordinates. */
    final float layoutTop;

    private BlurViewTransform(float scaleX, float scaleY, float rotationDeg,
                              float layoutLeft, float layoutTop) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.rotationDeg = rotationDeg;
        this.layoutLeft = layoutLeft;
        this.layoutTop = layoutTop;
    }

    static BlurViewTransform compute(View blurView, int[] blurViewLocation, int[] rootLocation) {
        float scaleX = blurView.getScaleX();
        float scaleY = blurView.getScaleY();
        float pivotX = blurView.getPivotX();
        float pivotY = blurView.getPivotY();
        float rotationDeg = blurView.getRotation();
        float cosR = (float) Math.cos(Math.toRadians(rotationDeg));
        float sinR = (float) Math.sin(Math.toRadians(rotationDeg));

        float visualLeft = blurViewLocation[0] - rootLocation[0];
        float visualTop = blurViewLocation[1] - rootLocation[1];

        float layoutLeft = visualLeft + pivotX * (scaleX * cosR - 1) - pivotY * scaleY * sinR;
        float layoutTop = visualTop + pivotY * (scaleY * cosR - 1) + pivotX * scaleX * sinR;

        return new BlurViewTransform(scaleX, scaleY, rotationDeg, layoutLeft, layoutTop);
    }
}
