package moe.shizuku.blurview.internal;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

/**
 * The GL_TEXTURE_2D holding the snapshot to blur. {@link #upload} re-allocates storage only when the
 * snapshot dimensions change, otherwise overwriting the existing texture in place. Must be used on the
 * GL thread.
 */
final class SourceTexture {

    private int texture;
    private int width;
    private int height;

    /**
     * Uploads the snapshot, reusing the texture storage when its size is unchanged.
     */
    void upload(Bitmap snapshot) {
        if (texture == 0) {
            int[] textureIds = new int[1];
            GLES20.glGenTextures(1, textureIds, 0);
            texture = textureIds[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (snapshot.getWidth() != width || snapshot.getHeight() != height) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, snapshot, 0);
            GlUtils.setDefaultTextureParams();
            width = snapshot.getWidth();
            height = snapshot.getHeight();
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, snapshot);
        }
    }

    int id() {
        return texture;
    }

    void release() {
        if (texture != 0) {
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            texture = 0;
            width = 0;
            height = 0;
        }
    }
}
