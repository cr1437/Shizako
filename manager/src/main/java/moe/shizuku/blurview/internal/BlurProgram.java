package moe.shizuku.blurview.internal;

import android.opengl.GLES20;

/**
 * The separable-Gaussian shader program and its uniform locations. Compiles once, then {@link #draw}
 * runs one blur pass: it sets the per-pass uniforms from a {@link GaussianKernel} and draws the
 * fullscreen quad into the currently bound framebuffer. Must be created and used on the GL thread.
 */
final class BlurProgram {

    private final int program;
    private final int textureLocation;
    private final int texelSizeLocation;
    private final int directionLocation;
    private final int sampleCountLocation;
    private final int offsetsLocation;
    private final int weightsLocation;
    private final int flipYLocation;

    BlurProgram() {
        program = GlUtils.linkProgram(BlurShaders.VERTEX, BlurShaders.BLUR_FRAGMENT);
        textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
        texelSizeLocation = GLES20.glGetUniformLocation(program, "uTexelSize");
        directionLocation = GLES20.glGetUniformLocation(program, "uDirection");
        sampleCountLocation = GLES20.glGetUniformLocation(program, "uSampleCount");
        offsetsLocation = GLES20.glGetUniformLocation(program, "uOffsets[0]");
        weightsLocation = GLES20.glGetUniformLocation(program, "uWeights[0]");
        flipYLocation = GLES20.glGetUniformLocation(program, "uFlipY");
    }

    /**
     * Blurs {@code sourceTexture} along {@code axis} into the bound framebuffer, sized {@code width}
     * by {@code height}. {@code flipY} flips the vertical axis for the GL vs Bitmap origin.
     */
    void draw(GaussianKernel kernel, int sourceTexture, int width, int height, Axis axis, boolean flipY) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture);
        GLES20.glUniform1i(textureLocation, 0);
        GLES20.glUniform2f(texelSizeLocation, 1f / width, 1f / height);
        GLES20.glUniform2f(directionLocation, axis.directionX, axis.directionY);
        GLES20.glUniform1i(sampleCountLocation, kernel.sampleCount());
        GLES20.glUniform1fv(offsetsLocation, GaussianKernel.MAX_SAMPLES, kernel.offsets(), 0);
        GLES20.glUniform1fv(weightsLocation, GaussianKernel.MAX_SAMPLES, kernel.weights(), 0);
        GLES20.glUniform1i(flipYLocation, flipY ? 1 : 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    void release() {
        GLES20.glDeleteProgram(program);
    }

    /**
     * Direction of one separable pass, as the step applied to the texel size.
     */
    enum Axis {
        HORIZONTAL(1f, 0f),
        VERTICAL(0f, 1f);

        final float directionX;
        final float directionY;

        Axis(float directionX, float directionY) {
            this.directionX = directionX;
            this.directionY = directionY;
        }
    }
}
