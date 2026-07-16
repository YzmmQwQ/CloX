package android.window;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-time stub for the hidden framework class {@code android.window.WindowContainerTransaction}. Never packaged
 * (compileOnly); the real framework class shadows it at runtime. Only enough to let the vendored
 * AIDL-generated Stub compile — real field/method access is done via reflection at runtime.
 */
public class WindowContainerTransaction implements Parcelable {
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Creator<WindowContainerTransaction> CREATOR = new Creator<WindowContainerTransaction>() {
        @Override public WindowContainerTransaction createFromParcel(Parcel in) { return new WindowContainerTransaction(); }
        @Override public WindowContainerTransaction[] newArray(int size) { return new WindowContainerTransaction[size]; }
    };
}
