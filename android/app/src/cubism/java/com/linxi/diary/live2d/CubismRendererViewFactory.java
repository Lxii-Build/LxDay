/*
 * Copyright (c) Live2D Inc. All rights reserved.
 *
 * This adapter is derived from the structure of Live2D's Cubism Java minimum
 * sample.  It is compiled only when the project owner supplies the matching
 * Cubism Core AAR and the official Java Framework source directory.  Review
 * the Live2D Open Software license before enabling distribution.
 */
package com.linxi.diary.live2d;

import android.content.Context;
import android.view.View;

import java.io.File;

/** Reflection target used by the dependency-free application layer. */
public final class CubismRendererViewFactory {
    private CubismRendererViewFactory() {}

    public static View create(Context context, File modelDirectory, String manifestPath) {
        return new CubismRendererView(context, modelDirectory, manifestPath);
    }
}
