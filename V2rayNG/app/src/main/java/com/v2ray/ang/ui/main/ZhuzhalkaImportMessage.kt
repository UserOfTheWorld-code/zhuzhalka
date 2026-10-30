package com.v2ray.ang.ui.main

import com.v2ray.ang.R
import com.v2ray.ang.handler.PublicSourceImportResult
import com.v2ray.ang.handler.PublicSourceImportStatus

internal fun importResultMessage(result: PublicSourceImportResult): Int = when (result.status) {
    PublicSourceImportStatus.SUCCESS -> R.string.buzz_loaded
    PublicSourceImportStatus.NO_UNIQUE_SERVERS -> R.string.buzz_no_unique
    PublicSourceImportStatus.INVALID_URL -> R.string.buzz_invalid_url
    PublicSourceImportStatus.DISABLED -> R.string.buzz_disabled_source
    PublicSourceImportStatus.UNKNOWN_SOURCE,
    PublicSourceImportStatus.REFRESH_FAILED,
    PublicSourceImportStatus.STORAGE_FAILED -> R.string.buzz_load_failed
}
