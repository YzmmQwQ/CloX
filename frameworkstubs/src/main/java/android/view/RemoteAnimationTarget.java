package android.view;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * COMPILE-ONLY STUB of the hidden framework class android.view.RemoteAnimationTarget (API 30).
 * Never packaged into the APK — the real framework class shadows this at runtime (parent-first
 * classloading). Field names/types mirror the API-30 class exactly so runtime field access on the
 * real objects resolves correctly. Only the members the launcher reads are declared.
 *
 * @noinspection ALL
 */
public class RemoteAnimationTarget implements Parcelable {

    public static final int MODE_OPENING = 0;
    public static final int MODE_CLOSING = 1;
    public static final int MODE_CHANGING = 2;

    public int mode;
    public int taskId;
    public SurfaceControl leash;
    public SurfaceControl startLeash;
    public boolean isTranslucent;
    public Rect clipRect;
    public Rect contentInsets;
    public int prefixOrderIndex;
    public Point position;
    public Rect localBounds;
    public Rect sourceContainerBounds;
    public Rect screenSpaceBounds;
    public Rect startBounds;
    public boolean isNotInRecents;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // Never invoked: the real framework class handles marshalling at runtime.
    }

    public static final Creator<RemoteAnimationTarget> CREATOR =
            new Creator<RemoteAnimationTarget>() {
                @Override
                public RemoteAnimationTarget createFromParcel(Parcel source) {
                    return new RemoteAnimationTarget();
                }

                @Override
                public RemoteAnimationTarget[] newArray(int size) {
                    return new RemoteAnimationTarget[size];
                }
            };
}
