package io.github.derundevu.yaxc.dto

data class ProfileList(
    var id: Long,
    var index: Int,
    var name: String,
    var link: Long?,
    var config: String = "",
)
