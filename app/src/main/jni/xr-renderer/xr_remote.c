// Remote monitor mode (Pop3D). The PC does the thinking: it sends the
// textures and, every frame, the complete list of layers to draw. This file
// holds those textures, turns the list into composition layers, draws the
// laser from the controller's current pose and reads the controllers back for
// the PC. None of the furniture, rooms or pointer of the original app runs.
#include "xr_renderer.h"

void remoteInit(XrCtx* ctx) {
    pthread_mutex_init(&ctx->remoteLock, NULL);
}

void remoteDestroy(XrCtx* ctx) {
    if (!ctx->remoteMode) {
        return;
    }
    for (int i = 0; i < ctx->remoteTexCount; i++) {
        destroyArtSwapchain(&ctx->remoteTex[i].chain, &ctx->remoteTex[i].images);
    }
    ctx->remoteTexCount = 0;
    destroyArtSwapchain(&ctx->laserSwapchain, &ctx->laserImages);
    pthread_mutex_destroy(&ctx->remoteLock);
    ctx->remoteMode = 0;
}

static RemoteTex* findTex(XrCtx* ctx, const char* id) {
    for (int i = 0; i < ctx->remoteTexCount; i++) {
        if (strcmp(ctx->remoteTex[i].id, id) == 0) {
            return &ctx->remoteTex[i];
        }
    }
    return NULL;
}

// ---------------------------------------------------------------- textures

// A texture from the pc, already in the order GL wants it: the bottom row of
// the picture first. Runs on the frame loop thread, between begin and end of a
// frame, which is the only place a swapchain image can be waited on.
JNIEXPORT void JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeRemoteUploadTexture(JNIEnv* env, jobject thiz,
                                                                      jlong handle, jstring id,
                                                                      jobject buffer, jint width,
                                                                      jint height) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->remoteMode || id == NULL || buffer == NULL
            || width <= 0 || height <= 0 || width > 8192 || height > 8192) {
        return;
    }
    const unsigned char* px = (*env)->GetDirectBufferAddress(env, buffer);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (px == NULL || capacity < (jlong)width * height * 4) {
        LOGW("remote texture buffer missing or too small");
        return;
    }
    const char* chars = (*env)->GetStringUTFChars(env, id, NULL);
    if (chars == NULL) {
        return;
    }
    char name[REMOTE_ID_MAX];
    strncpy(name, chars, sizeof(name) - 1);
    name[sizeof(name) - 1] = '\0';
    (*env)->ReleaseStringUTFChars(env, id, chars);

    RemoteTex* t = findTex(ctx, name);
    if (t != NULL && (t->width != width || t->height != height)) {
        // A different size is a different swapchain
        destroyArtSwapchain(&t->chain, &t->images);
        t->ready = 0;
        t->width = 0;
        t->height = 0;
    }
    if (t == NULL) {
        if (ctx->remoteTexCount >= REMOTE_MAX_TEX) {
            LOGW("too many remote textures, ignoring %s", name);
            return;
        }
        t = &ctx->remoteTex[ctx->remoteTexCount++];
        memset(t, 0, sizeof(*t));
        strncpy(t->id, name, sizeof(t->id) - 1);
    }
    if (t->chain == XR_NULL_HANDLE) {
        if (!createArtSwapchain(ctx, width, height, "create remote texture", &t->chain,
                                &t->images, &t->imageCount)) {
            return;
        }
        t->width = width;
        t->height = height;
        LOGI("remote texture %s: new swapchain %dx%d", name, width, height);
    }
    if (uploadArtImage(ctx, t->chain, t->images, px, width, height)) {
        t->ready = 1;
        ctx->remoteStatTexUploads++;
    }
}

// Mouth of the list the pc sends every frame. Replaces the previous one whole.
JNIEXPORT void JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeRemoteSetLayers(JNIEnv* env, jobject thiz,
                                                                  jlong handle,
                                                                  jobjectArray ids,
                                                                  jfloatArray data, jint count) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->remoteMode) {
        return;
    }
    if (count < 0) {
        count = 0;
    }
    if (count > REMOTE_MAX_LAYERS) {
        count = REMOTE_MAX_LAYERS;
    }
    if (count > 0 && (*env)->GetArrayLength(env, data) < count * RL_STRIDE) {
        return;
    }
    RemoteLayerSpec staged[REMOTE_MAX_LAYERS];
    memset(staged, 0, sizeof(staged));
    for (int i = 0; i < count; i++) {
        (*env)->GetFloatArrayRegion(env, data, i * RL_STRIDE, RL_STRIDE, staged[i].v);
        jstring s = ids != NULL ? (jstring)(*env)->GetObjectArrayElement(env, ids, i) : NULL;
        if (s != NULL) {
            const char* chars = (*env)->GetStringUTFChars(env, s, NULL);
            if (chars != NULL) {
                strncpy(staged[i].tex, chars, sizeof(staged[i].tex) - 1);
                (*env)->ReleaseStringUTFChars(env, s, chars);
            }
            (*env)->DeleteLocalRef(env, s);
        }
    }
    pthread_mutex_lock(&ctx->remoteLock);
    memcpy(ctx->remoteLayers, staged, sizeof(RemoteLayerSpec) * count);
    ctx->remoteLayerCount = count;
    pthread_mutex_unlock(&ctx->remoteLock);
}

// The decoded picture changed size, so its swapchain has to as well. The layers
// that name the video wait out the first frame drawn at the new size.
JNIEXPORT void JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeRemoteResizeVideo(JNIEnv* env, jobject thiz,
                                                                    jlong handle, jint width,
                                                                    jint height) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->remoteMode || width <= 0 || height <= 0 || width > 8192
            || height > 8192) {
        return;
    }
    if (ctx->swapchain != XR_NULL_HANDLE && ctx->videoWidth == width
            && ctx->videoHeight == height) {
        return;
    }
    destroyArtSwapchain(&ctx->swapchain, &ctx->swapchainImages);
    ctx->videoWidth = width;
    ctx->videoHeight = height;
    ctx->eyeWidth = width;
    ctx->everRendered = 0;
    if (!createArtSwapchain(ctx, width, height, "resize video swapchain", &ctx->swapchain,
                            &ctx->swapchainImages, &ctx->swapchainImageCount)) {
        LOGE("video swapchain %dx%d could not be created", width, height);
        return;
    }
    LOGI("video swapchain now %dx%d", width, height);
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeRemoteHaptic(JNIEnv* env, jobject thiz,
                                                               jlong handle, jint hand,
                                                               jfloat amplitude, jlong durationMs) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->remoteMode || hand < 0 || hand >= HAND_COUNT) {
        return;
    }
    if (amplitude < 0.0f) amplitude = 0.0f;
    if (amplitude > 1.0f) amplitude = 1.0f;
    pthread_mutex_lock(&ctx->remoteLock);
    ctx->hapticAmplitude[hand] = amplitude;
    ctx->hapticDurationNs[hand] = (long)durationMs * 1000000L;
    pthread_mutex_unlock(&ctx->remoteLock);
}

// ---------------------------------------------------------------- input

static void putPose(float* out, const XrPosef* pose) {
    out[0] = pose->position.x;
    out[1] = pose->position.y;
    out[2] = pose->position.z;
    out[3] = pose->orientation.x;
    out[4] = pose->orientation.y;
    out[5] = pose->orientation.z;
    out[6] = pose->orientation.w;
}

static int locatePose(XrCtx* ctx, XrSpace space, XrPosef* pose) {
    XrSpaceLocation loc = { XR_TYPE_SPACE_LOCATION };
    const XrSpaceLocationFlags needed = XR_SPACE_LOCATION_POSITION_VALID_BIT
            | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    if (XR_FAILED(xrLocateSpace(space, ctx->localSpace, ctx->predictedDisplayTime, &loc))
            || (loc.locationFlags & needed) != needed) {
        return 0;
    }
    *pose = loc.pose;
    return 1;
}

static float remoteFloat(XrCtx* ctx, XrAction action, int hand) {
    if (action == XR_NULL_HANDLE) {
        return 0.0f;
    }
    XrActionStateGetInfo get = { XR_TYPE_ACTION_STATE_GET_INFO };
    get.action = action;
    get.subactionPath = ctx->handPaths[hand];
    XrActionStateFloat state = { XR_TYPE_ACTION_STATE_FLOAT };
    if (XR_FAILED(xrGetActionStateFloat(ctx->session, &get, &state)) || !state.isActive) {
        return 0.0f;
    }
    return state.currentState;
}

static float remoteBool(XrCtx* ctx, XrAction action, int hand) {
    if (action == XR_NULL_HANDLE) {
        return 0.0f;
    }
    XrActionStateGetInfo get = { XR_TYPE_ACTION_STATE_GET_INFO };
    get.action = action;
    get.subactionPath = ctx->handPaths[hand];
    XrActionStateBoolean state = { XR_TYPE_ACTION_STATE_BOOLEAN };
    if (XR_FAILED(xrGetActionStateBoolean(ctx->session, &get, &state)) || !state.isActive) {
        return 0.0f;
    }
    return state.currentState ? 1.0f : 0.0f;
}

static void remoteVec2(XrCtx* ctx, XrAction action, int hand, float* x, float* y) {
    *x = 0.0f;
    *y = 0.0f;
    if (action == XR_NULL_HANDLE) {
        return;
    }
    XrActionStateGetInfo get = { XR_TYPE_ACTION_STATE_GET_INFO };
    get.action = action;
    get.subactionPath = ctx->handPaths[hand];
    XrActionStateVector2f state = { XR_TYPE_ACTION_STATE_VECTOR2F };
    if (XR_FAILED(xrGetActionStateVector2f(ctx->session, &get, &state)) || !state.isActive) {
        return;
    }
    *x = state.currentState.x;
    *y = state.currentState.y;
}

// What the pc wants to know once a frame. Fills the RIN_ slots.
JNIEXPORT void JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeRemoteInput(JNIEnv* env, jobject thiz,
                                                              jlong handle, jfloatArray outArr) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    float out[RIN_SLOTS];
    memset(out, 0, sizeof(out));
    if (ctx == NULL || !ctx->remoteMode) {
        (*env)->SetFloatArrayRegion(env, outArr, 0, RIN_SLOTS, out);
        return;
    }

    out[RIN_STATE] = ctx->sessionState == XR_SESSION_STATE_FOCUSED ? 2.0f
            : (ctx->sessionState == XR_SESSION_STATE_VISIBLE ? 1.0f : 0.0f);
    out[RIN_RECENTERED] = ctx->recenteredFlag ? 1.0f : 0.0f;
    ctx->recenteredFlag = 0;

    int live = ctx->sessionRunning && ctx->inputReady
            && (ctx->sessionState == XR_SESSION_STATE_VISIBLE
                || ctx->sessionState == XR_SESSION_STATE_FOCUSED);
    if (live) {
        // Whatever the pc asked to be felt, now that a thread that may touch the
        // session is the one doing it
        pthread_mutex_lock(&ctx->remoteLock);
        float amps[HAND_COUNT];
        long durations[HAND_COUNT];
        for (int h = 0; h < HAND_COUNT; h++) {
            amps[h] = ctx->hapticAmplitude[h];
            durations[h] = ctx->hapticDurationNs[h];
            ctx->hapticAmplitude[h] = 0.0f;
        }
        pthread_mutex_unlock(&ctx->remoteLock);
        for (int h = 0; h < HAND_COUNT; h++) {
            if (amps[h] > 0.0f && ctx->hapticAction != XR_NULL_HANDLE) {
                XrHapticVibration vib = { XR_TYPE_HAPTIC_VIBRATION };
                vib.duration = durations[h] > 0 ? (XrDuration)durations[h] : XR_MIN_HAPTIC_DURATION;
                vib.frequency = XR_FREQUENCY_UNSPECIFIED;
                vib.amplitude = amps[h];
                XrHapticActionInfo info = { XR_TYPE_HAPTIC_ACTION_INFO };
                info.action = ctx->hapticAction;
                info.subactionPath = ctx->handPaths[h];
                XrResult res = xrApplyHapticFeedback(ctx->session, &info,
                                                     (const XrHapticBaseHeader*)&vib);
                LOGI("vibrar mao %d forca %.2f %ld ms: resultado %d", h, amps[h],
                     durations[h] / 1000000L, res);
            }
        }

        XrActiveActionSet active;
        active.actionSet = ctx->actionSet;
        active.subactionPath = XR_NULL_PATH;
        XrActionsSyncInfo sync = { XR_TYPE_ACTIONS_SYNC_INFO };
        sync.countActiveActionSets = 1;
        sync.activeActionSets = &active;
        int synced = XR_SUCCEEDED(xrSyncActions(ctx->session, &sync));

        XrPosef pose;
        if (locatePose(ctx, ctx->viewSpace, &pose)) {
            out[RIN_HEAD] = 1.0f;
            putPose(out + RIN_HEAD + 1, &pose);
        }
        for (int h = 0; h < HAND_COUNT; h++) {
            float* o = out + (h == HAND_LEFT ? RIN_LEFT : RIN_RIGHT);
            if (ctx->aimSpaces[h] != XR_NULL_HANDLE && locatePose(ctx, ctx->aimSpaces[h], &pose)) {
                o[0] = 1.0f;
                putPose(o + 1, &pose);
            }
            if (synced) {
                o[8] = remoteFloat(ctx, ctx->triggerAction, h);
                o[9] = remoteFloat(ctx, ctx->grabAction, h);
                o[10] = remoteBool(ctx, ctx->rightClickAction, h);
                o[11] = remoteBool(ctx, ctx->middleClickAction, h);
                remoteVec2(ctx, ctx->scrollAction, h, &o[12], &o[13]);
            }
        }
    }
    ctx->remoteStatInputs++;
    (*env)->SetFloatArrayRegion(env, outArr, 0, RIN_SLOTS, out);
}

// ---------------------------------------------------------------- layers

// The beam: a ribbon whose alpha climbs toward the end, so the point it lands
// on is the strongest part. Row zero of a texture is the bottom of the quad,
// and the quad's up is pointed along the beam, so row zero meets the hand.
static void uploadLaserArt(XrCtx* ctx) {
    ctx->laserReady = -1;
    if (!createArtSwapchain(ctx, LASER_TEX_W, LASER_TEX_H, "create laser swapchain",
                            &ctx->laserSwapchain, &ctx->laserImages, &ctx->laserImageCount)) {
        return;
    }
    unsigned char* px = malloc(LASER_TEX_W * LASER_TEX_H * 4);
    if (px == NULL) {
        return;
    }
    for (int y = 0; y < LASER_TEX_H; y++) {
        float t = (y + 0.5f) / LASER_TEX_H;
        float along = 0.26f + 0.60f * t * t * t;
        if (t > 0.94f) {
            along = 0.92f;
        }
        float lead = t / 0.05f;
        if (lead < 1.0f) {
            along *= lead;
        }
        for (int x = 0; x < LASER_TEX_W; x++) {
            float r = fabsf((x + 0.5f) - LASER_TEX_W * 0.5f) / (LASER_TEX_W * 0.5f);
            float across = r < 0.4f ? 1.0f : (1.0f - r) / 0.6f;
            if (across < 0.0f) across = 0.0f;
            unsigned char* p = px + ((size_t)y * LASER_TEX_W + x) * 4;
            p[0] = p[1] = p[2] = 255;
            p[3] = (unsigned char)(along * across * 255.0f + 0.5f);
        }
    }
    if (uploadArtImage(ctx, ctx->laserSwapchain, ctx->laserImages, px, LASER_TEX_W, LASER_TEX_H)) {
        ctx->laserReady = 1;
    }
    free(px);
}

static XrQuaternionf safeQuat(const float* q) {
    XrQuaternionf r = { q[0], q[1], q[2], q[3] };
    float len = sqrtf(r.x * r.x + r.y * r.y + r.z * r.z + r.w * r.w);
    if (len < 1e-4f) {
        XrQuaternionf id = { 0.0f, 0.0f, 0.0f, 1.0f };
        return id;
    }
    r.x /= len;
    r.y /= len;
    r.z /= len;
    r.w /= len;
    return r;
}

static XrEyeVisibility eyeOf(float v) {
    return v > 1.5f ? XR_EYE_VISIBILITY_RIGHT
            : (v > 0.5f ? XR_EYE_VISIBILITY_LEFT : XR_EYE_VISIBILITY_BOTH);
}

// Resolves the texture a layer names. The video is the decoded picture, all
// else is a texture the pc sent.
static int resolveTex(XrCtx* ctx, const char* id, XrSwapchain* chain, int* w, int* h) {
    if (strcmp(id, "video") == 0) {
        if (!ctx->everRendered || ctx->swapchain == XR_NULL_HANDLE) {
            return 0;
        }
        *chain = ctx->swapchain;
        *w = ctx->videoWidth;
        *h = ctx->videoHeight;
        return 1;
    }
    RemoteTex* t = findTex(ctx, id);
    if (t == NULL || !t->ready || t->chain == XR_NULL_HANDLE) {
        return 0;
    }
    *chain = t->chain;
    *w = t->width;
    *h = t->height;
    return 1;
}

// The rectangle arrives top left origin, in pixels. A GL swapchain counts rows
// from the bottom, so the offset is measured from there.
static XrSwapchainSubImage subImageFor(XrSwapchain chain, int tw, int th, const float* rect) {
    int x = (int)rect[0], y = (int)rect[1], w = (int)rect[2], h = (int)rect[3];
    if (w <= 0 || h <= 0) {
        x = 0;
        y = 0;
        w = tw;
        h = th;
    }
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x >= tw) x = tw - 1;
    if (y >= th) y = th - 1;
    if (x + w > tw) w = tw - x;
    if (y + h > th) h = th - y;
    XrSwapchainSubImage sub;
    memset(&sub, 0, sizeof(sub));
    sub.swapchain = chain;
    sub.imageRect.offset.x = x;
    sub.imageRect.offset.y = th - (y + h);
    sub.imageRect.extent.width = w;
    sub.imageRect.extent.height = h;
    sub.imageArrayIndex = 0;
    return sub;
}

static void beamLayer(XrCompositionLayerQuad* quad, XrSpace space, Vec3 start, Vec3 end,
                      Vec3 head, XrSwapchain chain) {
    Vec3 along = vecSub(end, start);
    float length = sqrtf(along.x * along.x + along.y * along.y + along.z * along.z);
    Vec3 mid = { (start.x + end.x) * 0.5f, (start.y + end.y) * 0.5f, (start.z + end.z) * 0.5f };
    Vec3 beamY = vecNorm(along);
    Vec3 toHead = vecNorm(vecSub(head, mid));
    Vec3 beamX = vecCross(beamY, toHead);
    float sideLen = sqrtf(beamX.x * beamX.x + beamX.y * beamX.y + beamX.z * beamX.z);
    // A quad has one facing, so the ribbon turns to the head. Looking along the
    // beam there is no such direction and any side will do.
    if (sideLen < 0.15f) {
        Vec3 up = { 0.0f, 1.0f, 0.0f };
        beamX = vecCross(beamY, up);
        sideLen = sqrtf(beamX.x * beamX.x + beamX.y * beamX.y + beamX.z * beamX.z);
        if (sideLen < 0.15f) {
            Vec3 side = { 1.0f, 0.0f, 0.0f };
            beamX = vecCross(beamY, side);
        }
    }
    beamX = vecNorm(beamX);
    Vec3 beamZ = vecCross(beamX, beamY);

    memset(quad, 0, sizeof(*quad));
    quad->type = XR_TYPE_COMPOSITION_LAYER_QUAD;
    quad->layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT
            | XR_COMPOSITION_LAYER_UNPREMULTIPLIED_ALPHA_BIT;
    quad->eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    quad->space = space;
    quad->subImage.swapchain = chain;
    quad->subImage.imageRect.extent.width = LASER_TEX_W;
    quad->subImage.imageRect.extent.height = LASER_TEX_H;
    quad->pose.orientation = quatFromBasis(beamX, beamY, beamZ);
    quad->pose.position.x = mid.x;
    quad->pose.position.y = mid.y;
    quad->pose.position.z = mid.z;
    // About 6 mm of solid ribbon once the soft edges are taken off
    quad->size.width = 0.0085f;
    quad->size.height = length;
}

typedef struct {
    XrCompositionLayerQuad quads[REMOTE_MAX_LAYERS];
    XrCompositionLayerCylinderKHR cylinders[REMOTE_MAX_LAYERS];
    const XrCompositionLayerBaseHeader* order[REMOTE_MAX_LAYERS];
    uint32_t count;
} RemoteFrame;

static void logRemoteStats(XrCtx* ctx, int lastLayers) {
    long now = nowNs();
    if (ctx->remoteStatNs == 0) {
        ctx->remoteStatNs = now;
    }
    if (now - ctx->remoteStatNs < 1000000000L) {
        return;
    }
    double dt = (now - ctx->remoteStatNs) / 1e9;
    LOGI("Pop3D remoto: %.0f quadros/s, %d camadas por quadro (%d lasers), "
         "puladas %d, entradas lidas %.0f/s, texturas %d (%d envios), video %dx%d",
         ctx->remoteStatFrames / dt, lastLayers, ctx->remoteStatLasers, ctx->remoteStatSkipped,
         ctx->remoteStatInputs / dt, ctx->remoteTexCount, ctx->remoteStatTexUploads,
         ctx->videoWidth, ctx->videoHeight);
    ctx->remoteStatNs = now;
    ctx->remoteStatFrames = 0;
    ctx->remoteStatSkipped = 0;
    ctx->remoteStatInputs = 0;
    ctx->remoteStatTexUploads = 0;
    ctx->remoteStatLasers = 0;
}

void remoteEndFrame(XrCtx* ctx, int newFrame, const float* texMatrix) {
    if (newFrame && ctx->shouldRender && ctx->swapchain != XR_NULL_HANDLE) {
        renderVideoFrame(ctx, texMatrix, 0.0f);
    }
    if (ctx->laserReady == 0 && ctx->shouldRender) {
        uploadLaserArt(ctx);
    }

    XrFrameEndInfo endInfo = { XR_TYPE_FRAME_END_INFO };
    endInfo.displayTime = ctx->predictedDisplayTime;
    // Same two step as the original: the first focused frame goes out opaque so
    // the runtime sees the change of blend mode afterwards
    int wantPassthrough = ctx->passthrough && ctx->alphaBlendSupported;
    endInfo.environmentBlendMode = (wantPassthrough && ctx->focusedFrames > 0)
            ? XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND : XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    if (ctx->sessionState == XR_SESSION_STATE_FOCUSED) {
        ctx->focusedFrames++;
    }

    RemoteFrame frame;
    frame.count = 0;
    int lasers = 0;

    if (ctx->shouldRender) {
        RemoteLayerSpec specs[REMOTE_MAX_LAYERS];
        pthread_mutex_lock(&ctx->remoteLock);
        int n = ctx->remoteLayerCount;
        memcpy(specs, ctx->remoteLayers, sizeof(RemoteLayerSpec) * n);
        pthread_mutex_unlock(&ctx->remoteLock);

        uint32_t cap = (uint32_t)ctx->maxLayerCount;
        if (cap > REMOTE_MAX_LAYERS) {
            cap = REMOTE_MAX_LAYERS;
        }

        int haveHead = 0;
        XrPosef headPose;
        memset(&headPose, 0, sizeof(headPose));

        for (int i = 0; i < n; i++) {
            const float* v = specs[i].v;
            int kind = (int)v[RL_KIND];
            if (frame.count >= cap) {
                if (!ctx->remoteLayerCapWarned) {
                    ctx->remoteLayerCapWarned = 1;
                    LOGW("pc sent %d layers, this runtime takes %u: the rest are dropped", n, cap);
                }
                ctx->remoteStatSkipped += n - i;
                break;
            }

            if (kind == RL_KIND_LASER) {
                if (ctx->laserReady != 1) {
                    ctx->remoteStatSkipped++;
                    continue;
                }
                int hand = v[RL_HAND] > 0.5f ? HAND_RIGHT : HAND_LEFT;
                XrPosef aim;
                if (ctx->aimSpaces[hand] == XR_NULL_HANDLE
                        || !locatePose(ctx, ctx->aimSpaces[hand], &aim)) {
                    ctx->remoteStatSkipped++;
                    continue;
                }
                if (!haveHead) {
                    haveHead = locatePose(ctx, ctx->viewSpace, &headPose);
                }
                Vec3 start = { aim.position.x, aim.position.y, aim.position.z };
                Vec3 end = { v[RL_POINT], v[RL_POINT + 1], v[RL_POINT + 2] };
                Vec3 head = haveHead
                        ? (Vec3){ headPose.position.x, headPose.position.y, headPose.position.z }
                        : (Vec3){ end.x, end.y, end.z + 1.0f };
                Vec3 d = vecSub(end, start);
                if (sqrtf(d.x * d.x + d.y * d.y + d.z * d.z) < 0.03f) {
                    continue;
                }
                XrCompositionLayerQuad* quad = &frame.quads[frame.count];
                beamLayer(quad, ctx->localSpace, start, end, head, ctx->laserSwapchain);
                frame.order[frame.count++] = (const XrCompositionLayerBaseHeader*)quad;
                lasers++;
                continue;
            }

            XrSwapchain chain = XR_NULL_HANDLE;
            int tw = 0, th = 0;
            if (specs[i].tex[0] == '\0' || !resolveTex(ctx, specs[i].tex, &chain, &tw, &th)) {
                ctx->remoteStatSkipped++;
                continue;
            }
            XrSwapchainSubImage sub = subImageFor(chain, tw, th, &v[RL_RECT]);
            XrCompositionLayerFlags flags = 0;
            if (v[RL_ALPHA] > 1.5f) {
                flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
            }
            else if (v[RL_ALPHA] > 0.5f) {
                flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT
                        | XR_COMPOSITION_LAYER_UNPREMULTIPLIED_ALPHA_BIT;
            }
            XrPosef pose;
            pose.position.x = v[RL_POS];
            pose.position.y = v[RL_POS + 1];
            pose.position.z = v[RL_POS + 2];
            pose.orientation = safeQuat(&v[RL_ROT]);

            if (kind == RL_KIND_CYLINDER && ctx->cylinderSupported
                    && v[RL_RADIUS] > 0.01f && v[RL_ANGLE] > 0.001f && v[RL_ASPECT] > 0.001f) {
                XrCompositionLayerCylinderKHR* cyl = &frame.cylinders[frame.count];
                memset(cyl, 0, sizeof(*cyl));
                cyl->type = XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR;
                cyl->layerFlags = flags;
                cyl->space = ctx->localSpace;
                cyl->eyeVisibility = eyeOf(v[RL_EYE]);
                cyl->subImage = sub;
                cyl->pose = pose;
                cyl->radius = v[RL_RADIUS];
                cyl->centralAngle = v[RL_ANGLE];
                cyl->aspectRatio = v[RL_ASPECT];
                frame.order[frame.count++] = (const XrCompositionLayerBaseHeader*)cyl;
                continue;
            }

            XrCompositionLayerQuad* quad = &frame.quads[frame.count];
            memset(quad, 0, sizeof(*quad));
            quad->type = XR_TYPE_COMPOSITION_LAYER_QUAD;
            quad->layerFlags = flags;
            quad->space = ctx->localSpace;
            quad->eyeVisibility = eyeOf(v[RL_EYE]);
            quad->subImage = sub;
            if (kind == RL_KIND_CYLINDER) {
                // No cylinder layers here (or nonsense numbers): a flat quad of
                // the same width and height, sitting where the surface would
                float radius = v[RL_RADIUS] > 0.01f ? v[RL_RADIUS] : 1.0f;
                float width = radius * v[RL_ANGLE];
                float height = v[RL_ASPECT] > 0.001f ? width / v[RL_ASPECT] : width;
                Vec3 front = { 0.0f, 0.0f, -radius };
                Vec3 shift = quatRotate(pose.orientation, front);
                pose.position.x += shift.x;
                pose.position.y += shift.y;
                pose.position.z += shift.z;
                quad->size.width = width;
                quad->size.height = height;
            }
            else {
                quad->size.width = v[RL_SIZE];
                quad->size.height = v[RL_SIZE + 1];
            }
            quad->pose = pose;
            frame.order[frame.count++] = (const XrCompositionLayerBaseHeader*)quad;
        }
    }

    endInfo.layerCount = frame.count;
    endInfo.layers = frame.order;
    checkXr(xrEndFrame(ctx->session, &endInfo), "xrEndFrame");

    ctx->remoteStatFrames++;
    ctx->remoteStatLasers = lasers;
    logRemoteStats(ctx, (int)frame.count);
}
