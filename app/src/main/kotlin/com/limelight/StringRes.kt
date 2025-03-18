package com.limelight

import android.content.Context
import androidx.annotation.StringRes

sealed class StringResource {
    data class ByValue(val value: String) : StringResource()
    data class ById(@StringRes val id: Int) : StringResource()

    fun format(context: Context, vararg args: Any?): String = when (this) {
        is ByValue -> value.format(context, args)
        is ById -> context.getString(id, args)
    }
}