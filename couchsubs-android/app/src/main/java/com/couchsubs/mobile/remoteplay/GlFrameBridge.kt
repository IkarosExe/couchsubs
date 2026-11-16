// SPDX-License-Identifier: AGPL-3.0-only
// IkarosExe fork'u (2026-11-16): TV'de görüntüyü donanım çözücü çözer, kareyi GPU okur.
//
// Neden: TCL C7L'de (MediaTek MT9653) donanım çözücünün kareleri karolu (tiled) düzendedir; ImageReader'dan işlemciyle
// düz okunamıyordu. Yazılım çözücü ise TV'nin 4 çekirdeğini doldurup OCR'ı ve çeviriyi yavaşlatıyordu. GPU karolu
// düzeni doğal olarak okur: çözücü bir SurfaceTexture'a yazar, burada kare tam ekran bir dörtgenle parlaklığa (gri)
// çevrilip RGBA_8888 bir ImageReader'a çizilir. Uygulamanın geri kalanı karenin ilk düzlemini okur; RGBA'da her
// pikselin ilk baytı (R) parlaklık olduğundan kod değişmeden çalışır (pixelStride 4).
package com.couchsubs.mobile.remoteplay

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GlFrameBridge(private val width: Int, private val height: Int, output: Surface) : SurfaceTexture.OnFrameAvailableListener {
    private companion object {
        const val TAG = "CouchSubsGl"

        const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // Parlaklık (BT.601) R, G ve B'ye yazılır: ilk bayt okunan Y düzlemi yerine geçer.
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                vec3 c = texture2D(uTexture, vTexCoord).rgb;
                float y = dot(c, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(y, y, y, 1.0);
            }
        """

        // Tam ekran dörtgen (üçgen şeridi): konum x,y ve doku s,t.
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
    }

    private val thread = HandlerThread("CouchSubs GL").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var positionLoc = 0
    private var texCoordLoc = 0
    private var texMatrixLoc = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(QUAD); position(0) }
    @Volatile private var released = false

    /** Çözücünün yazacağı yüzey (MediaCodec'e verilir). */
    val inputSurface: Surface

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        var input: Surface? = null
        handler.post {
            try {
                setUp(output)
                input = Surface(surfaceTexture)
            } catch (error: Throwable) {
                failure = error
            } finally {
                ready.countDown()
            }
        }
        if (!ready.await(3, TimeUnit.SECONDS)) {
            release()
            throw IllegalStateException("GL setup timed out")
        }
        failure?.let {
            release()
            throw it
        }
        inputSurface = input ?: throw IllegalStateException("GL input surface missing")
        Log.i(TAG, "GPU okuma hazır ${width}x$height")
    }

    private fun setUp(output: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${EGL14.eglGetError()}" }
        surface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        // ImageReader'a çizim ekran tazelemesini beklemesin.
        EGL14.eglSwapInterval(display, 0)

        program = buildProgram()
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("texture")

        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener(this@GlFrameBridge, handler)
        }
    }

    override fun onFrameAvailable(texture: SurfaceTexture) {
        if (released) return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, this.texture)
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
            quad.position(0)
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(positionLoc)
            quad.position(2)
            GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(texCoordLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, surface)
        } catch (error: RuntimeException) {
            Log.w(TAG, "GPU karesi çizilemedi", error)
        }
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("GL link: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source.trimIndent())
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("GL shader: $log")
        }
        return shader
    }

    private fun checkGl(step: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL $step: 0x${Integer.toHexString(error)}" }
    }

    /** GL kaynaklarını bırakır (çözücü durdurulduktan sonra çağrılır). */
    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                surfaceTexture = null
                if (display != EGL14.EGL_NO_DISPLAY) {
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "GL bırakılamadı", error)
            } finally {
                display = EGL14.EGL_NO_DISPLAY
                done.countDown()
            }
        }
        if (posted) done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }
}
