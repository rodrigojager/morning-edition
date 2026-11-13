package dobra.app.turn

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import dobra.reader.turn.BookPose
import dobra.reader.turn.BookStrip
import dobra.reader.turn.BookTurn
import dobra.reader.turn.CurlPose
import dobra.reader.turn.SheetCurl
import dobra.reader.turn.SheetMesh
import dobra.reader.turn.SheetShadow
import dobra.reader.turn.TurnPose
import dobra.reader.turn.TurnSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** The two ways a page turns: the single sheet rolling off the screen, or the book's right page swinging over the fold. */
enum class TurnKind { SHEET, BOOK }

/**
 * What one turn draws. A turn is always "a to b", played forwards or backwards (see [turnFaces]): [printed] is the
 * picture of screen a, printed on the turning sheet's face; [reached] the picture of screen b. The single sheet lies on
 * [reached] and has a blank back of colour [paper]. The book's page shows on its back the left page of [reached]; under
 * it lie the left page of [printed] and the right page of [reached]. Sizes are density-independent pixels (the unit
 * of the geometry); [hingeDp] is where the book's fold is, from the left edge.
 */
internal class TurnScene(
    val kind: TurnKind,
    val printed: Bitmap,
    val reached: Bitmap,
    val paper: Int,
    val widthDp: Float,
    val heightDp: Float,
    val hingeDp: Float = widthDp / 2f,
)

/**
 * Draws the page turn with OpenGL ES 2, as the prototype's Three.js scene does: a perspective camera of 14 degrees
 * looking at the page, the pictures under the turning page, the soft shadow, and the turning page. The single sheet is
 * a grid whose printed face shows its picture and whose back shows blank paper, each face with its own light; the
 * book's page is a strip hinged on the fold with a page picture on each face. Lives on the GL thread, with a current
 * context.
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
    private var book: BookTurn? = null
    private var strip: BookStrip? = null
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

    // the book's strip: its size is fixed, its texture coordinates depend on where the fold is
    private val stripVertices = (TurnSpec.BOOK_SEGMENTS + 1) * 2
    private val stripPos = floats(stripVertices * 3)
    private val stripShade = floats(stripVertices)
    private val stripFrontUv = floats(stripVertices * 2)
    private val stripBackUv = floats(stripVertices * 2)
    private val stripIdx: ShortBuffer = ByteBuffer.allocateDirect(TurnSpec.BOOK_SEGMENTS * 6 * 2).order(ByteOrder.nativeOrder())
        .asShortBuffer()

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
        upload(textures[REACHED], scene.reached)
        upload(textures[PRINTED], scene.printed)
        scene.reached.recycle()
        scene.printed.recycle()
        val c = scene.paper
        paper = floatArrayOf(((c shr 16) and 0xff) / 255f, ((c shr 8) and 0xff) / 255f, (c and 0xff) / 255f, 1f)
        if (scene.kind == TurnKind.BOOK) {
            curl = null
            val b = BookTurn(scene.widthDp, scene.heightDp, scene.hingeDp)
            val s = b.strip()
            book = b
            strip = s
            stripFrontUv.position(0)
            stripFrontUv.put(s.frontUv).position(0)
            stripBackUv.position(0)
            stripBackUv.put(s.backUv).position(0)
            stripIdx.position(0)
            stripIdx.put(s.indices).position(0)
        } else {
            book = null
            strip = null
            curl = SheetCurl(scene.widthDp, scene.heightDp)
        }
        camera(scene.widthDp, scene.heightDp)
    }

    fun viewport(width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
    }

    fun draw(pose: TurnPose) {
        when (pose) {
            is CurlPose -> drawCurl(pose)
            is BookPose -> drawBook(pose)
        }
    }

    /** Clears to the paper and readies the program: everything is drawn with the same camera and texture unit. */
    private fun begin() {
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(paper[0], paper[1], paper[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** The soft shadow of the turning page, on the pictures under it ([bw]: the page width, x is from its left). */
    private fun shadow(s: SheetShadow, bw: Float) {
        if (s.opacity <= 0f) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        quad(s.x - bw / 2 - s.width / 2, s.y - s.height / 2, -0.5f, s.width, s.height, s.rotation, s.x - bw / 2, s.y)
        GLES20.glUniform1f(uMode, MODE_SHADOW)
        GLES20.glUniform4f(uColor, 0f, 0f, 0f, s.opacity)
        drawQuad()
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Depth on and the faces told apart, counter-clockwise being the printed face. */
    private fun sheetState() {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glFrontFace(GLES20.GL_CCW)
    }

    /** The book: the left page of a and the right page of b under the strip, its shadow, then the strip with the
     *  right page of a on its face and the left page of b on its back. */
    private fun drawBook(pose: BookPose) {
        val book = book ?: return
        val strip = strip ?: return
        val bw = book.width
        val bh = book.height
        val hinge = book.hinge
        book.pose(pose, strip)
        begin()

        GLES20.glUniform1f(uMode, MODE_TEXTURE)
        quad(-bw / 2, -bh / 2, -1f, hinge, bh, 0f, u0 = 0f, u1 = hinge / bw)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[PRINTED])
        drawQuad()
        quad(hinge - bw / 2, -bh / 2, -1f, bw - hinge, bh, 0f, u0 = hinge / bw, u1 = 1f)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[REACHED])
        drawQuad()

        shadow(strip.shadow, bw)

        val p = strip.positions
        for (n in 0 until strip.vertexCount) p[n * 3] -= bw / 2
        stripPos.position(0)
        stripPos.put(p).position(0)
        stripShade.position(0)
        stripShade.put(strip.shade).position(0)
        sheetState()
        GLES20.glUniform1f(uMode, MODE_TEXTURE)

        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[PRINTED])
        drawStrip(stripFrontUv)

        GLES20.glCullFace(GLES20.GL_FRONT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[REACHED])
        drawStrip(stripBackUv)
    }

    private fun drawStrip(uvs: FloatBuffer) {
        attributes(stripPos, uvs, stripShade)
        stripIdx.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, TurnSpec.BOOK_SEGMENTS * 6, GLES20.GL_UNSIGNED_SHORT, stripIdx)
    }

    private fun drawCurl(pose: CurlPose) {
        val curl = curl ?: return
        val bw = curl.width
        val bh = curl.height
        curl.pose(pose, mesh)
        begin()

        // the page under the sheet
        quad(-bw / 2, -bh / 2, -1f, bw, bh, 0f)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[REACHED])
        GLES20.glUniform1f(uMode, MODE_TEXTURE)
        drawQuad()

        // the soft shadow of the turned part, under the sheet
        shadow(mesh.shadow, bw)

        // the sheet: the printed face, then the blank back, the depth buffer sorting out what is on top
        val p = mesh.positions
        for (n in 0 until mesh.vertexCount) p[n * 3] -= bw / 2
        pos.put(p).position(0)
        front.put(mesh.front).position(0)
        back.put(mesh.back).position(0)
        sheetState()

        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[PRINTED])
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
        // near and far hug the scene so the depth buffer stays precise: it spans z from -1 to twice the roll's radius,
        // or to the book's page width when it stands over the fold (well inside unless the screen is 4 times wider
        // than tall)
        Matrix.perspectiveM(proj, 0, FOV, bw / bh, dist / 2f, dist * 2f)
        Matrix.setLookAtM(view, 0, 0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
    }

    /** A rectangle [w] by [h] from ([x], [y]) at depth [z], turned by [angle] around ([cx], [cy]), showing the picture
     *  from [u0] to [u1] across. */
    private fun quad(
        x: Float, y: Float, z: Float, w: Float, h: Float, angle: Float,
        cx: Float = 0f, cy: Float = 0f, u0: Float = 0f, u1: Float = 1f,
    ) {
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
        quadUv.put(floatArrayOf(u0, 1f, u1, 1f, u0, 0f, u1, 0f)).position(0)
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
        const val REACHED = 0
        const val PRINTED = 1
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
