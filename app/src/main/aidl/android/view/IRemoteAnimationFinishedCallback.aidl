package android.view;

/**
 * Vendored copy of the hidden framework AIDL (API 30). Provides the compile-time Stub; the real
 * framework interface shadows it at runtime (parent-first classloading), so the Binder descriptor
 * must match the platform's exactly — do not rename or reorder.
 * {@hide}
 */
interface IRemoteAnimationFinishedCallback {
    void onAnimationFinished();
}
