package android.window;

import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;

/**
 * Vendored copy of the hidden framework AIDL (Android 15 / API 35). The real framework interface
 * shadows it at runtime; keep the descriptor and method signature identical to the platform's.
 * {@hide}
 */
interface IRemoteTransitionFinishedCallback {
    void onTransitionFinished(in WindowContainerTransaction wct, in SurfaceControl.Transaction sct);
}
