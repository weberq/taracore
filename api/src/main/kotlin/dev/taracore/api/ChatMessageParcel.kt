package dev.taracore.api

import android.os.Parcel
import android.os.Parcelable

/**
 * One chat turn. Roles follow the OpenAI convention -- "system", "user", "assistant"
 * -- because that is what the models' own chat templates expect and what every
 * client already speaks. Unknown roles are passed through to the template verbatim.
 */
data class ChatMessageParcel(
    @JvmField val role: String,
    @JvmField val content: String,
) : Parcelable {

    constructor(source: Parcel) : this(
        role = source.readString().orEmpty(),
        content = source.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(role)
        dest.writeString(content)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        @JvmField
        val CREATOR: Parcelable.Creator<ChatMessageParcel> =
            object : Parcelable.Creator<ChatMessageParcel> {
                override fun createFromParcel(source: Parcel) = ChatMessageParcel(source)
                override fun newArray(size: Int) = arrayOfNulls<ChatMessageParcel>(size)
            }
    }
}
