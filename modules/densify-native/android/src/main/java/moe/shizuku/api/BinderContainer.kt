package moe.shizuku.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/** Same wire format as the Shizuku API's BinderContainer, so client apps can read the binder we hand them. */
class BinderContainer(@JvmField var binder: IBinder?) : Parcelable {
  constructor(p: Parcel) : this(p.readStrongBinder())

  override fun describeContents(): Int = 0
  override fun writeToParcel(dest: Parcel, flags: Int) { dest.writeStrongBinder(binder) }

  companion object {
    @JvmField
    val CREATOR: Parcelable.Creator<BinderContainer> = object : Parcelable.Creator<BinderContainer> {
      override fun createFromParcel(source: Parcel): BinderContainer = BinderContainer(source)
      override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
    }
  }
}
