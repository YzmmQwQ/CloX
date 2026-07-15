package android.view;

import android.view.RemoteAnimationTarget;
import android.view.IRemoteAnimationFinishedCallback;

/**
 * Vendored copy of the hidden framework AIDL (API 30). Provides the compile-time Stub we subclass;
 * the real framework interface shadows it at runtime (parent-first classloading), so the Binder
 * descriptor and method order must match the platform's exactly — do not rename or reorder.
 * {@hide}
 */
oneway interface IRemoteAnimationRunner {
    void onAnimationStart(in RemoteAnimationTarget[] apps, in RemoteAnimationTarget[] wallpapers,
            in IRemoteAnimationFinishedCallback finishedCallback);

    void onAnimationCancelled();
}
