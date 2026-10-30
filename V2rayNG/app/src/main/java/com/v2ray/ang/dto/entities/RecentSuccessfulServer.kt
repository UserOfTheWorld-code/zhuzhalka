package com.v2ray.ang.dto.entities

/** No connection credentials: the profile is resolved again before every web check. */
data class RecentSuccessfulServer(val guid: String = "", val verifiedAtMillis: Long = 0L)
