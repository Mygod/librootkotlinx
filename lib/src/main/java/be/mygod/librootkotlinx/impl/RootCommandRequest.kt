package be.mygod.librootkotlinx.impl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
internal class RootCommandRequest(val command: Parcelable) : Parcelable
