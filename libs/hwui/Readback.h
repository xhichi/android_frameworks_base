/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include "renderthread/RenderThread.h"
#include "Rect.h"

#include <SkBitmap.h>
#include <gui/Surface.h>

namespace android {
namespace uirenderer {

class Layer;

// Keep in sync with PixelCopy.java codes
enum class CopyResult {
    Success = 0,
    UnknownError = 1,
    Timeout = 2,
    SourceEmpty = 3,
    SourceInvalid = 4,
    DestinationInvalid = 5,
};

class Readback {
public:
    /**
     * Copies the surface's most recently queued buffer into the provided bitmap.
     */
    static CopyResult copySurfaceInto(renderthread::RenderThread& renderThread,
            Surface& surface, const Rect& srcRect, SkBitmap* bitmap);

    /**
     * Copies the TextureLayer's texture content (thus, the currently rendering buffer) into the
     * provided bitmap.
     */
    static CopyResult copyTextureLayerInto(renderthread::RenderThread& renderThread,
            Layer& layer, SkBitmap* bitmap);
};

} // namespace uirenderer
} // namespace android
