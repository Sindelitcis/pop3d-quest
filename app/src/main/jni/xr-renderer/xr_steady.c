#include <math.h>

#include "xr_steady.h"

void steadyReset(SteadyState* s) {
    s->mode = STEADY_FREE;
    s->valid = 0;
    s->armSpent = 0;
    s->wasDown = 0;
}

static float angleBetween(float u0, float v0, float u1, float v1,
                          float radPerU, float radPerV) {
    float du = (u1 - u0) * radPerU;
    float dv = (v1 - v0) * radPerV;
    return sqrtf(du * du + dv * dv);
}

static void pin(SteadyState* s, int mode, long nowNs) {
    // The pin goes where the cursor already is, not where this frame's
    // sample has knocked the ray to
    s->pinU = s->outU;
    s->pinV = s->outV;
    s->mode = mode;
    s->sinceNs = nowNs;
}

int steadyPointer(SteadyState* s, float u, float v, float radPerU, float radPerV,
                  int arming, int down, long nowNs) {
    if (!s->valid) {
        s->valid = 1;
        s->outU = u;
        s->outV = v;
        s->mode = STEADY_FREE;
    }
    if (!arming && !down) {
        s->armSpent = 0;
    }

    int was = s->mode;
    long held = nowNs - s->sinceNs;
    float away = angleBetween(s->pinU, s->pinV, u, v, radPerU, radPerV);

    switch (s->mode) {
    case STEADY_FREE:
        if (down && !s->wasDown) {
            pin(s, STEADY_DOWN, nowNs);
        }
        else if (arming && !s->armSpent) {
            pin(s, STEADY_ARM, nowNs);
        }
        break;

    case STEADY_ARM:
        if (down) {
            s->mode = STEADY_DOWN;
            s->sinceNs = nowNs;
        }
        else if (!arming || away > STEADY_DRAG_RAD) {
            s->mode = STEADY_FREE;
        }
        else if (held > STEADY_ARM_NS) {
            s->mode = STEADY_FREE;
            s->armSpent = 1;
        }
        break;

    case STEADY_DOWN:
        if (!down) {
            s->mode = STEADY_AFTER;
            s->sinceNs = nowNs;
        }
        else if (away > (held >= STEADY_LONG_PRESS_NS ? STEADY_FINE_RAD : STEADY_DRAG_RAD)) {
            // A drag. The cursor jumps to the hand, which carries whatever
            // was picked up along with it.
            s->mode = STEADY_FREE;
            s->outU = u;
            s->outV = v;
        }
        break;

    case STEADY_AFTER:
        // A second tap keeps the pixel the first one had, which is what
        // makes the pair a double click
        if (down) {
            s->mode = STEADY_DOWN;
            s->sinceNs = nowNs;
        }
        else if (arming && !s->armSpent) {
            s->mode = STEADY_ARM;
            s->sinceNs = nowNs;
        }
        else if (held > STEADY_AFTER_NS || away > STEADY_AFTER_RAD) {
            s->mode = STEADY_FREE;
        }
        break;
    }

    s->wasDown = down;

    if (s->mode == STEADY_FREE) {
        // Follows the hand only once it has moved past the dead band, and
        // then trails it by the band, so a hand at rest leaves the cursor
        // alone while a sweep is followed in full
        float off = angleBetween(s->outU, s->outV, u, v, radPerU, radPerV);
        if (off > STEADY_DEADBAND_RAD) {
            float keep = STEADY_DEADBAND_RAD / off;
            s->outU = u + (s->outU - u) * keep;
            s->outV = v + (s->outV - v) * keep;
        }
    }
    else {
        s->outU = s->pinU;
        s->outV = s->pinV;
    }
    return s->mode != was;
}

const char* steadyModeName(int mode) {
    switch (mode) {
    case STEADY_ARM:
        return "arm";
    case STEADY_DOWN:
        return "down";
    case STEADY_AFTER:
        return "after";
    default:
        return "free";
    }
}
