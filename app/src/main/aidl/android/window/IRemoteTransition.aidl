package android.window;

import android.view.SurfaceControl;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;
import android.window.WindowAnimationState;

/**
 * Vendored copy of the hidden framework AIDL (Android 15 / API 35). Provides the compile-time Stub we
 * subclass; the real framework interface shadows it at runtime (parent-first classloading), so the
 * Binder descriptor, method ORDER and count must match the platform's exactly — do not rename,
 * reorder, or omit methods (takeOverAnimation was added in API 34/35 and must stay in this position).
 * {@hide}
 */
oneway interface IRemoteTransition {
    void startAnimation(in IBinder token, in TransitionInfo info, in SurfaceControl.Transaction t,
            in IRemoteTransitionFinishedCallback finishCallback);

    void mergeAnimation(in IBinder transition, in TransitionInfo info,
            in SurfaceControl.Transaction t, in IBinder mergeTarget,
            in IRemoteTransitionFinishedCallback finishCallback);

    void takeOverAnimation(in IBinder transition, in TransitionInfo info,
            in SurfaceControl.Transaction t, in IRemoteTransitionFinishedCallback finishCallback,
            in WindowAnimationState[] states);

    void onTransitionConsumed(in IBinder transition, boolean aborted);
}
