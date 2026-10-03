// Keeps the host cursor still through a click from a hand held pointer.
//
// A hand at rest still shakes, and pulling a trigger or closing a pinch
// knocks the ray further. The host reads any move with the button down as a
// drag, Windows after only 4 px, so a click on an icon picks it up instead of
// opening it, and a double click lands on two different pixels and is not one.
//
// So the cursor is pinned from the moment the finger starts to press, before
// the click itself registers, and stays pinned while the button is held and
// for a moment after it comes up, so a second tap finds the same pixel. Only a
// deliberate move, well past what a shaking hand does, unpins it and becomes a
// drag. A press held still for a while is read as the start of a drag, so the
// pin loosens then to let a fine drag or a text selection through. Between
// clicks a small dead band keeps the cursor from crawling while it is aimed.
//
// Distances are angles seen from the hand, in radians, so the feel is the
// same whatever size and distance the screen is set to. Pure bookkeeping on
// numbers, so it is built and checked on a desktop too.

#ifndef XR_STEADY_H
#define XR_STEADY_H

// Movement smaller than this is dropped while aiming, about 0.1 degree or
// 3 px on the default screen
#define STEADY_DEADBAND_RAD   0.0017f
// A pinned cursor is let go by a move past this, which is what a drag looks
// like, about 2 degrees or 60 px on the default screen
#define STEADY_DRAG_RAD       0.035f
// Once a press has been held still this long, the user is starting a drag on
// purpose and a much smaller move lets it go
#define STEADY_LONG_PRESS_NS  450000000L
#define STEADY_FINE_RAD       0.009f
// A finger that starts to press but never clicks is resting on the trigger,
// and the cursor is not kept waiting on it
#define STEADY_ARM_NS         400000000L
// How long the pin outlives the click, and how far the hand may drift before
// that. Covers the drift measured between the two taps of a natural double
// click, which is up to about 1 degree.
#define STEADY_AFTER_NS       500000000L
#define STEADY_AFTER_RAD      0.020f

enum {
    STEADY_FREE = 0,
    // The finger is on its way down, the click is not in yet
    STEADY_ARM,
    STEADY_DOWN,
    // Just clicked, waiting to see whether a second tap follows
    STEADY_AFTER,
};

typedef struct {
    int mode;
    int valid;
    // What the cursor shows, and where it is pinned when it is
    float outU, outV;
    float pinU, pinV;
    // When the current mode began
    long sinceNs;
    // A press that timed out while arming, so a resting finger does not pin
    // the cursor again until it lifts
    int armSpent;
    // The button as it was last frame, since only a fresh press pins: one
    // still held after a drag broke free stays free
    int wasDown;
} SteadyState;

void steadyReset(SteadyState* s);

// One frame. u and v are the smoothed hit point in 0..1 across the picture,
// radPerU and radPerV turn a step in each into an angle seen from the hand.
// arming is a finger on its way to a press, down is the button held. Returns
// whether the mode changed, and leaves the cursor in outU, outV.
int steadyPointer(SteadyState* s, float u, float v, float radPerU, float radPerV,
                  int arming, int down, long nowNs);

const char* steadyModeName(int mode);

#endif
