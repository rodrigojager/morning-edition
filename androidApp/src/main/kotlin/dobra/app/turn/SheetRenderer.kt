package dobra.app.turn

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import dobra.reader.turn.CurlPose
import dobra.reader.turn.SheetCurl
import dobra.reader.turn.SheetMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * What one turn draws: the picture left under the sheet, the picture printed on the sheet, the colour of the sheet's
 * blank back, and the size of the page in density-independent pixels (the unit of the geometry).
 */
internal class TurnScene(
    val under: Bitmap,
    val sheet: Bitmap,
    val paper: Int,
    val widthDp: Float,
    val heightDp: Float,
)

/**
 * Draws the single sheet turn with OpenGL ES 2, as the prototype's Three.js scene does: a perspective camera of 14
 * degrees looking at the page, the picture under the sheet, the soft shadow, and the sheet as a mesh whose printed
 * face shows the page picture and whose back shows blank paper, each face with its own light. Lives on the GL thread,
 * with a current context.
 */
internal class SheetRenderer {
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var aShade = 0
    private var uMvp = 0
    private var uTex = 0
    private var uColor = 0
    private var uMode = 0
    private val textures = IntArray(2)
    private var paper = FloatArray(4)
    private var curl: SheetCurl? = null
    private val mesh = SheetMesh()
    private val mvp = FloatArray(16)
    private var viewW = 1
    private var viewH = 1

    private val pos = floats(mesh.vertexCount * 3)
    private val front = floats(mesh.vertexCount)
    private val back = floats(mesh.vertexCount)
    private val uv = floats(mesh.uv.size).apply { put(mesh.uv).position(0) }
    private val idx: ShortBuffer = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder())
        .asShortBuffer().apply { put(mesh.indices).position(0) }
    private val quadPos = floats(12)
    private val quadUv = floats(8)
    private val quadShade = floats(4).apply { put(floatArrayOf(1f, 1f, 1f, 1f)).position(0) }

    /** Compiles the program once per context. Throws when the driver refuses it. */
    fun init() {
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "page turn program: " + GLES20.glGetProgramInfoLog(program) }
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        aShade = GLES20.glGetAttribLocation(program, "aShade")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        GLES20.glGenTextures(2, textures, 0)
    }

    /** Takes the pictures and the size of a new turn; the bitmaps are uploaded and recycled. */
    fun load(scene: TurnScene) {
        upload(textures[0], scene.under)
        upload(textures[1], scene.sheet)
        scene.under.recycle()
        scene.sheet.recycle()
        val c = scene.paper
        paper = floatArrayOf(((c shr 16) and 0xff) / 255f, ((c shr 8) and 0xff) / 255f, (c and 0xff) / 255f, 1f)
        curl = SheetCurl(scene.widthDp, scene.heightDp)
        camera(scene.widthDp, scene.heightDp)
    }

    fun viewport(width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
    }

    fun draw(pose: CurlPose) {
        val curl = curl ?: return
        val bw = curl.width
        val bh = curl.height
        curl.pose(pose, mesh)

        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(paper[0], paper[1], paper[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // the page under the sheet
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        quad(-bw / 2, -bh / 2, -1f, bw, bh, 0f)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glUniform1f(uMode, MODE_TEXTURE)
        drawQuad()

        // the soft shadow of the turned part, under the sheet
        val s = mesh.shadow
        if (s.opacity > 0f) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            quad(s.x - bw / 2 - s.width / 2, s.y - s.height / 2, -0.5f, s.width, s.height, s.rotation, s.x - bw / 2, s.y)
            GLES20.glUniform1f(uMode, MODE_SHADOW)
            GLES20.glUniform4f(uColor, 0f, 0f, 0f, s.opacity)
            drawQuad()
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // the sheet: the printed face, then the blank back, the depth buffer sorting out what is on top
        val p = mesh.positions
        for (n in 0 until mesh.vertexCount) p[n * 3] -= bw / 2
        pos.put(p).position(0)
        front.put(mesh.front).position(0)
        back.put(mesh.back).position(0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glFrontFace(GLES20.GL_CCW)

        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
        GLES20.glUniform1f(uMode, MODE_TEXTURE)
        drawSheet(front)

        GLES20.glCullFace(GLES20.GL_FRONT)
        GLES20.glUniform1f(uMode, MODE_COLOR)
        GLES20.glUniform4f(uColor, paper[0], paper[1], paper[2], 1f)
        drawSheet(back)
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteTextures(2, textures, 0)
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun camera(bw: Float, bh: Float) {
        val dist = bh / (2f * tan(Math.toRadians(FOV / 2.0)).toFloat())
        val proj = FloatArray(16)
        val view = FloatArray(16)
        // near and far hug the scene (it spans z from -1 to twice the roll's radius) so the depth buffer stays precise
        Matrix.perspectiveM(proj, 0, FOV, bw / bh, dist / 2f, dist * 2f)
        Matrix.setLookAtM(view, 0, 0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
    }

    /** A rectangle [w] by [h] from ([x], [y]) at depth [z], turned by [angle] around ([cx], [cy]). */
    private fun quad(x: Float, y: Float, z: Float, w: Float, h: Float, angle: Float, cx: Float = 0f, cy: Float = 0f) {
        val c = cos(angle)
        val s = sin(angle)
        val corners = floatArrayOf(x, y, x + w, y, x, y + h, x + w, y + h)
        quadPos.position(0)
        for (k in 0 until 4) {
            val dx = corners[k * 2] - cx
            val dy = corners[k * 2 + 1] - cy
            quadPos.put(cx + dx * c - dy * s).put(cy + dx * s + dy * c).put(z)
        }
        quadPos.position(0)
        // v from the top of the picture: the bottom corners take v = 1
        quadUv.position(0)
        quadUv.put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)).position(0)
    }

    private fun drawQuad() {
        attributes(quadPos, quadUv, quadShade)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawSheet(shade: FloatBuffer) {
        attributes(pos, uv, shade)
        idx.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, idx)
    }

    private fun attributes(p: FloatBuffer, t: FloatBuffer, shade: FloatBuffer) {
        p.position(0)
        t.position(0)
        shade.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, p)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, t)
        GLES20.glEnableVertexAttribArray(aShade)
        GLES20.glVertexAttribPointer(aShade, 1, GLES20.GL_FLOAT, false, 0, shade)
    }

    private fun upload(texture: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "page picture upload failed: $error" }
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "page turn shader: " + GLES20.glGetShaderInfoLog(id) }
        return id
    }

    private companion object {
        const val FOV = 14f
        const val MODE_TEXTURE = 0f
        const val MODE_COLOR = 1f
        const val MODE_SHADOW = 2f

        fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        const val VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            attribute vec2 aUv;
            attribute float aShade;
            varying vec2 vUv;
            varying float vShade;
            void main() {
                vUv = aUv;
                vShade = aShade;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        // The light is a factor on linear colour, as in the prototype's colour-managed scene: on the stored (sRGB)
        // colour it amounts to the factor raised to 1/2.2. The shadow is a band that fades in over its outer quarters.
        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec4 uColor;
            uniform float uMode;
            varying vec2 vUv;
            varying float vShade;
            void main() {
                if (uMode > 1.5) {
                    float x = vUv.x;
                    float a = x < 0.25 ? x / 0.25 : (x > 0.75 ? (1.0 - x) / 0.25 : 1.0);
                    gl_FragColor = vec4(0.0, 0.0, 0.0, a * uColor.a);
                } else {
                    vec3 base = uMode > 0.5 ? uColor.rgb : texture2D(uTex, vUv).rgb;
                    gl_FragColor = vec4(base * pow(vShade, 1.0 / 2.2), 1.0);
                }
            }
        """
    }
}
