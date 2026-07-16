package android.window;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-time stub for the hidden framework class {@code android.window.TransitionInfo}. Never packaged
 * (compileOnly); the real framework class shadows it at runtime. Only enough to let the vendored
 * AIDL-generated Stub compile — real field/method access is done via reflection at runtime.
 */
public class TransitionInfo implements Parcelable {
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Creator<TransitionInfo> CREATOR = new Creator<TransitionInfo>() {
        @Override public TransitionInfo createFromParcel(Parcel in) { return new TransitionInfo(); }
        @Override public TransitionInfo[] newArray(int size) { return new TransitionInfo[size]; }
    };
}
