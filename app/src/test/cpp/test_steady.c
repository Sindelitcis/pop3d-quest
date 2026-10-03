// The click pin, run frame by frame through the gestures it has to tell
// apart: a hand at rest, a tap, a double tap, a drag, a slow fine drag and a
// finger resting on the trigger
#include "check.h"
#include "xr_steady.h"

// One radian of hand per unit of u and v keeps the angles readable, and a
// 72 Hz frame is what the headset runs at
#define RAD 1.0f
#define FRAME_NS 13888889L

static long now;

static int step(SteadyState* s, float u, float v, int arming, int down) {
    now += FRAME_NS;
    return steadyPointer(s, u, v, RAD, RAD, arming, down, now);
}

static void start(SteadyState* s, float u, float v) {
    steadyReset(s);
    now = 1000000000L;
    step(s, u, v, 0, 0);
}

static void testTremorAtRest(void) {
    SteadyState s;
    start(&s, 0.5f, 0.5f);
    // Shaking inside the dead band leaves the cursor where it is
    for (int i = 0; i < 50; i++) {
        float shake = (i & 1) ? 0.001f : -0.001f;
        step(&s, 0.5f + shake, 0.5f - shake, 0, 0);
    }
    CHECK_NEAR(s.outU, 0.5f, 1e-6);
    CHECK_NEAR(s.outV, 0.5f, 1e-6);

    // A real move is followed, trailing by the band
    step(&s, 0.6f, 0.5f, 0, 0);
    CHECK_NEAR(s.outU, 0.6f - STEADY_DEADBAND_RAD, 1e-5);
    CHECK(s.mode == STEADY_FREE);
}

static void testTapHoldsThroughTheJerk(void) {
    SteadyState s;
    start(&s, 0.5f, 0.5f);
    // The finger starts to pull and the hand jerks, before and during the
    // press and as it comes up again
    step(&s, 0.505f, 0.5f, 1, 0);
    CHECK(s.mode == STEADY_ARM);
    step(&s, 0.512f, 0.49f, 1, 0);
    step(&s, 0.518f, 0.488f, 0, 1);
    CHECK(s.mode == STEADY_DOWN);
    step(&s, 0.52f, 0.485f, 0, 1);
    step(&s, 0.51f, 0.49f, 0, 0);
    CHECK(s.mode == STEADY_AFTER);
    // The cursor never left the pixel it was on when the finger moved
    CHECK_NEAR(s.outU, 0.5f, 1e-6);
    CHECK_NEAR(s.outV, 0.5f, 1e-6);

    // And lets go once the double click window is over
    for (int i = 0; i < 40; i++) {
        step(&s, 0.51f, 0.49f, 0, 0);
    }
    CHECK(s.mode == STEADY_FREE);
    CHECK(s.outU > 0.505f);
}

static void testDoubleTapSharesThePixel(void) {
    SteadyState s;
    start(&s, 0.3f, 0.3f);
    step(&s, 0.3f, 0.3f, 0, 1);
    step(&s, 0.305f, 0.3f, 0, 0);
    step(&s, 0.31f, 0.302f, 0, 0);
    // The second tap drifted 10 mrad, still the same pixel
    step(&s, 0.31f, 0.305f, 1, 0);
    step(&s, 0.312f, 0.305f, 0, 1);
    CHECK(s.mode == STEADY_DOWN);
    CHECK_NEAR(s.outU, 0.3f, 1e-6);
    CHECK_NEAR(s.outV, 0.3f, 1e-6);
}

static void testDragLetsGo(void) {
    SteadyState s;
    start(&s, 0.5f, 0.5f);
    step(&s, 0.5f, 0.5f, 0, 1);
    step(&s, 0.52f, 0.5f, 0, 1);
    CHECK(s.mode == STEADY_DOWN);
    // Past the drag threshold the cursor jumps to the hand and follows it
    int changed = step(&s, 0.5f + STEADY_DRAG_RAD + 0.01f, 0.5f, 0, 1);
    CHECK(changed);
    CHECK(s.mode == STEADY_FREE);
    CHECK_NEAR(s.outU, 0.5f + STEADY_DRAG_RAD + 0.01f, 1e-6);
    step(&s, 0.7f, 0.5f, 0, 1);
    CHECK(s.outU > 0.69f);
    // Letting go after a drag does not pin anything
    step(&s, 0.7f, 0.5f, 0, 0);
    CHECK(s.mode == STEADY_FREE);
}

static void testLongPressLoosens(void) {
    SteadyState s;
    start(&s, 0.5f, 0.5f);
    step(&s, 0.5f, 0.5f, 0, 1);
    // A small move early in the press is still shake
    step(&s, 0.5f + STEADY_FINE_RAD * 1.5f, 0.5f, 0, 1);
    CHECK(s.mode == STEADY_DOWN);
    step(&s, 0.5f, 0.5f, 0, 1);
    // Held still for a while, the same move is a fine drag
    for (int i = 0; i < 40; i++) {
        step(&s, 0.5f, 0.5f, 0, 1);
    }
    step(&s, 0.5f + STEADY_FINE_RAD * 1.5f, 0.5f, 0, 1);
    CHECK(s.mode == STEADY_FREE);
}

static void testRestingFinger(void) {
    SteadyState s;
    start(&s, 0.5f, 0.5f);
    // A finger resting on the trigger arms, times out and is then ignored
    // until it lifts, so the cursor moves again
    for (int i = 0; i < 40; i++) {
        step(&s, 0.5f, 0.5f, 1, 0);
    }
    CHECK(s.mode == STEADY_FREE);
    step(&s, 0.6f, 0.5f, 1, 0);
    CHECK(s.mode == STEADY_FREE);
    CHECK(s.outU > 0.59f);

    // Lifting it rearms the next press
    step(&s, 0.6f, 0.5f, 0, 0);
    step(&s, 0.6f, 0.5f, 1, 0);
    CHECK(s.mode == STEADY_ARM);
}

static void testHalfPressAborted(void) {
    SteadyState s;
    start(&s, 0.5f, 0.5f);
    step(&s, 0.5f, 0.5f, 1, 0);
    step(&s, 0.51f, 0.5f, 0, 0);
    // The finger came off without clicking, nothing to hold for
    CHECK(s.mode == STEADY_FREE);
}

int main(void) {
    testTremorAtRest();
    testTapHoldsThroughTheJerk();
    testDoubleTapSharesThePixel();
    testDragLetsGo();
    testLongPressLoosens();
    testRestingFinger();
    testHalfPressAborted();
    return checksDone("xr_steady");
}
