/*
 * Copyright (c) Live2D Inc. All rights reserved.
 *
 * This adapter follows the official Cubism Java minimum sample's Android
 * renderer lifecycle. It is not part of the default build: the matching Core
 * AAR and Framework source must be supplied by the project owner.
 */
package com.linxi.diary.live2d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.ICubismModelSetting;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A single-model, static-first GLSurfaceView.  Motion playback is intentionally
 * not started here: a model with no motion files still renders its default
 * pose, and callers can later add a bounded ticker without making every card
 * consume a 60fps loop.
 */
public final class CubismRendererView extends GLSurfaceView {
    private static final String TAG = "Live2D";
    private final Renderer renderer;

    CubismRendererView(Context context, File modelDirectory, String manifestPath) {
        super(context);
        setEGLContextClientVersion(2);
        // Keep the model's transparent canvas over the neumorphic Compose
        // surface instead of forcing a black rectangle behind every role.
        // The alpha channel must be requested before the renderer is set.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setPreserveEGLContextOnPause(false);
        renderer = new Renderer(context.getApplicationContext(), modelDirectory, manifestPath);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setFocusable(false);
        setContentDescription("Live2D 角色预览");
    }

    /** Must be called on the view's GL thread before the view is discarded. */
    public void close() {
        queueEvent(renderer::release);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) requestRender();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        requestRender();
    }

    private static final class Renderer implements GLSurfaceView.Renderer {
        private final Context context;
        private final File modelDirectory;
        private final String manifestPath;
        private CubismFileModel model;
        private int width;
        private int height;

        Renderer(Context context, File modelDirectory, String manifestPath) {
            this.context = context;
            this.modelDirectory = modelDirectory;
            this.manifestPath = manifestPath;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            try {
                ensureFramework();
                if (model != null) model.releaseModel();
                model = new CubismFileModel(context, modelDirectory, manifestPath);
                model.load(1, 1);
            } catch (Throwable error) {
                Log.e(TAG, "Cubism model initialization failed", error);
                model = null;
            }
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            GLES20.glViewport(0, 0, this.width, this.height);
            if (model != null) {
                try {
                    model.reloadRenderer(this.width, this.height);
                } catch (Throwable error) {
                    Log.e(TAG, "Cubism renderer resize failed", error);
                    model = null;
                }
            }
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (model == null || width <= 0 || height <= 0) return;
            try {
                model.updateAndDraw(width, height);
            } catch (Throwable error) {
                // A bad third-party model must only blank this preview, not
                // take down status sync, music, or the whole Compose host.
                Log.e(TAG, "Cubism frame failed", error);
                model.releaseModel();
                model = null;
            }
        }

        void release() {
            if (model != null) {
                try {
                    model.releaseModel();
                } catch (Throwable error) {
                    Log.w(TAG, "Cubism model release failed", error);
                }
                model = null;
            }
            try {
                if (CubismFramework.isInitialized()) CubismFramework.dispose();
                if (CubismFramework.isStarted()) CubismFramework.cleanUp();
            } catch (Throwable error) {
                Log.w(TAG, "Cubism framework release failed", error);
            }
        }

        private void ensureFramework() {
            if (!CubismFramework.isStarted()) {
                CubismFramework.Option option = new CubismFramework.Option();
                option.loadFileFunction = path -> readBytes(path);
                CubismFramework.startUp(option);
            }
            if (!CubismFramework.isInitialized()) CubismFramework.initialize();
        }

        private File resolveSafe(String rawPath) {
            File root;
            try {
                root = modelDirectory.getCanonicalFile();
                File base = new File(root, manifestPath).getCanonicalFile().getParentFile();
                File target = new File(base, rawPath.replace('\\', File.separatorChar)).getCanonicalFile();
                String rootPath = root.getPath() + File.separator;
                if (!target.getPath().startsWith(rootPath)) throw new IOException("unsafe model path");
                return target;
            } catch (IOException error) {
                throw new IllegalArgumentException("unsafe model reference", error);
            }
        }

        private byte[] readBytes(File file) {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (IOException error) {
                throw new IllegalArgumentException("model resource unavailable", error);
            }
        }

        private byte[] readBytes(String path) {
            if (path.startsWith("com/live2d/sdk/")) {
                try (java.io.InputStream input = context.getAssets().open(path)) {
                    return readAll(input);
                } catch (IOException error) {
                    throw new IllegalArgumentException("framework shader unavailable", error);
                }
            }
            return readBytes(resolveSafe(path));
        }

        private static byte[] readAll(java.io.InputStream input) throws IOException {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static final class CubismFileModel extends CubismUserModel {
        private final Context context;
        private final File modelDirectory;
        private final String manifestPath;
        private ICubismModelSetting setting;
        private final List<Integer> textureIds = new ArrayList<>();

        CubismFileModel(Context context, File modelDirectory, String manifestPath) {
            this.context = context;
            this.modelDirectory = modelDirectory;
            this.manifestPath = manifestPath;
        }

        void load(int width, int height) {
            byte[] json = readBytes(resolveSafe(manifestPath));
            setting = new CubismModelSettingJson(json);
            if (setting.getJson() == null || setting.getModelFileName().isEmpty()) {
                throw new IllegalArgumentException("model3.json has no model reference");
            }
            loadModel(readBytes(resolveSafe(setting.getModelFileName())), true);
            if (getModel() == null) throw new IllegalArgumentException("moc3 could not create a model");
            reloadRenderer(width, height);
            Map<String, Float> layout = new HashMap<>();
            if (setting.getLayoutMap(layout)) getModelMatrix().setupFromLayout(layout);
        }

        void reloadRenderer(int width, int height) {
            releaseTextures();
            deleteRenderer();
            CubismRenderer renderer = CubismRendererAndroid.create(Math.max(1, width), Math.max(1, height));
            setupRenderer(renderer);
            if (setting != null) bindTextures();
        }

        void updateAndDraw(int width, int height) {
            float aspectRatio = (float) width / (float) height;
            float displayRatio = (float) height / (float) width;
            float canvasRatio = getModel().getCanvasHeight() / getModel().getCanvasWidth();
            CubismMatrix44 projection = CubismMatrix44.create();
            if (canvasRatio < displayRatio) {
                getModelMatrix().setWidth(2.0f);
                projection.scale(1.0f, aspectRatio);
            } else {
                getModelMatrix().setHeight(2.0f);
                projection.scale(1.0f / aspectRatio, 1.0f);
            }
            getModel().update();
            CubismMatrix44.multiply(getModelMatrix().getArray(), projection.getArray(), projection.getArray());
            CubismRendererAndroid androidRenderer = getRenderer();
            androidRenderer.setMvpMatrix(projection);
            androidRenderer.drawModel();
        }

        void releaseModel() {
            releaseTextures();
            delete();
        }

        private void releaseTextures() {
            for (Integer textureId : textureIds) {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            }
            textureIds.clear();
        }

        private void bindTextures() {
            CubismRendererAndroid androidRenderer = getRenderer();
            androidRenderer.isPremultipliedAlpha(true);
            for (int index = 0; index < setting.getTextureCount(); index++) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPremultiplied = true;
                Bitmap bitmap = BitmapFactory.decodeFile(resolveSafe(setting.getTextureFileName(index)).getPath(), options);
                if (bitmap == null) throw new IllegalArgumentException("texture decode failed");
                int[] ids = new int[1];
                GLES20.glGenTextures(1, ids, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                androidRenderer.bindTexture(index, ids[0]);
                textureIds.add(ids[0]);
                bitmap.recycle();
            }
        }

        private File resolveSafe(String rawPath) {
            try {
                File root = modelDirectory.getCanonicalFile();
                File base = new File(root, manifestPath).getCanonicalFile().getParentFile();
                File target = new File(base, rawPath.replace('\\', File.separatorChar)).getCanonicalFile();
                String rootPath = root.getPath() + File.separator;
                if (!target.getPath().startsWith(rootPath)) throw new IOException("unsafe model path");
                return target;
            } catch (IOException error) {
                throw new IllegalArgumentException("unsafe model reference", error);
            }
        }

        private byte[] readBytes(File file) {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (IOException error) {
                throw new IllegalArgumentException("model resource unavailable", error);
            }
        }
    }
}
