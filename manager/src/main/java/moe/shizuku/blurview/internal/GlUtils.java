package moe.shizuku.blurview.internal;

import android.opengl.GLES20;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Stateless GL helpers shared by the OpenGL blur pipeline: shader and program building, and the
 * fixed fullscreen-quad setup. All methods must be called on the thread that owns the GL context.
 */
final class GlUtils {

    // Vertex layout of the fullscreen quad. The attribute locations must match the
    // layout(location = ...) pins in the pipeline's vertex shader so one VAO is valid for every program.
    static final int POSITION_LOCATION = 0;
    static final int UV_LOCATION = 1;

    private static final int FLOAT_BYTES = 4;
    private static final int STRIDE_BYTES = 4 * FLOAT_BYTES;   // pos.xy + uv.xy
    private static final int UV_OFFSET_BYTES = 2 * FLOAT_BYTES;

    private GlUtils() {
    }

    /**
     * Compiles and links a program from the given sources. Throws on compile or link failure.
     */
    static int linkProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader;
        try {
            fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        } catch (RuntimeException fragmentFailed) {
            GLES20.glDeleteShader(vertexShader);
            throw fragmentFailed;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        // The shaders are linked into the program (or abandoned on failure); either way, free them.
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linkStatus[0] == 0) {
            String infoLog = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("GlUtils: program link failed: " + infoLog);
        }
        return program;
    }

    private static int compileShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            String infoLog = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("GlUtils: shader compile failed: " + infoLog);
        }
        return shader;
    }

    /**
     * Creates the static fullscreen-quad VBO and returns its id. Four vertices of four interleaved
     * floats each - clip {@code pos.xy} then {@code uv.xy} - spanning clip space {@code [-1, 1]},
     * drawn as a {@code GL_TRIANGLE_STRIP}. UV origin is top-left ({@code uv.y} runs opposite clip
     * {@code y}) so a top-left-origin Bitmap maps upright.
     *
     * <pre>
     *   float layout (16 floats):
     *   [ px py u v ][ px py u v ][ px py u v ][ px py u v ]
     *    \-vertex 0-/ \-vertex 1-/ \-vertex 2-/ \-vertex 3-/
     * </pre>
     */
    static int createFullscreenQuad() {
        float[] vertices = {
                // px  py  u  v
                -1f, -1f, 0f, 1f,   // 0 bottom-left
                1f, -1f, 1f, 1f,   // 1 bottom-right
                -1f, 1f, 0f, 0f,    // 2 top-left
                1f, 1f, 1f, 0f     // 3 top-right
        };
        FloatBuffer vertexBuffer = ByteBuffer
                .allocateDirect(vertices.length * FLOAT_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(vertices);
        vertexBuffer.position(0);

        int[] bufferIds = new int[1];
        GLES20.glGenBuffers(1, bufferIds, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferIds[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.length * FLOAT_BYTES, vertexBuffer, GLES20.GL_STATIC_DRAW);
        return bufferIds[0];
    }

    /**
     * Creates a VAO that records the given VBO bound with the fullscreen-quad attribute layout
     * (position at POSITION_LOCATION, uv at UV_LOCATION). Bind it once per frame instead of
     * re-specifying the vertex attributes on every draw. Leaves no VAO bound. Returns the VAO id.
     */
    static int createQuadVao(int vbo) {
        int[] vaoIds = new int[1];
        GLES30.glGenVertexArrays(1, vaoIds, 0);
        GLES30.glBindVertexArray(vaoIds[0]);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(POSITION_LOCATION);
        GLES20.glVertexAttribPointer(POSITION_LOCATION, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, 0);
        GLES20.glEnableVertexAttribArray(UV_LOCATION);
        GLES20.glVertexAttribPointer(UV_LOCATION, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, UV_OFFSET_BYTES);
        GLES30.glBindVertexArray(0);
        return vaoIds[0];
    }

    /**
     * Applies CLAMP_TO_EDGE wrap and GL_LINEAR filtering on the currently bound GL_TEXTURE_2D. The
     * linear filter is load-bearing: {@link GaussianKernel}'s pair-fusion taps rely on hardware
     * bilinear sampling to fold two texels into one fetch, and CLAMP_TO_EDGE keeps edge samples from
     * wrapping.
     */
    static void setDefaultTextureParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    }
}
