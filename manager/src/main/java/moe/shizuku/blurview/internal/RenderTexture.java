package moe.shizuku.blurview.internal;

import android.opengl.GLES20;
import android.opengl.GLES30;

/**
 * The offscreen framebuffer the horizontal pass renders into and the vertical pass samples back. Owns
 * a color texture and its FBO, reallocated only when the blur resolution changes. Must be used on the
 * GL thread.
 */
final class RenderTexture {

    private static final int[] DISCARD = {GLES30.GL_COLOR_ATTACHMENT0};

    private int fbo;
    private int texture;
    private int width;
    private int height;

    /**
     * (Re)allocates the texture and framebuffer when the size changes; a no-op otherwise.
     */
    void ensureSize(int newWidth, int newHeight) {
        if (texture != 0 && newWidth == width && newHeight == height) {
            return;
        }
        release();
        width = newWidth;
        height = newHeight;

        int[] textureIds = new int[1];
        int[] framebufferIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        GLES20.glGenFramebuffers(1, framebufferIds, 0);
        texture = textureIds[0];
        fbo = framebufferIds[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GlUtils.setDefaultTextureParams();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * Binds this framebuffer as the draw target. The pass overwrites every pixel, so the previous
     * contents are discarded to spare tile-based GPUs the load into tile memory.
     */
    void bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, width, height);
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, DISCARD, 0);
    }

    int texture() {
        return texture;
    }

    void release() {
        if (texture != 0) {
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            texture = 0;
            fbo = 0;
        }
    }
}
