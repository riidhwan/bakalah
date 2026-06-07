package tachiyomi.data

import app.cash.sqldelight.ColumnAdapter
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultMangaCollectionState
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import java.util.Date

object DateColumnAdapter : ColumnAdapter<Date, Long> {
    override fun decode(databaseValue: Long): Date = Date(databaseValue)
    override fun encode(value: Date): Long = value.time
}

private const val LIST_OF_STRINGS_SEPARATOR = ", "
object StringListColumnAdapter : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String) = if (databaseValue.isEmpty()) {
        emptyList()
    } else {
        databaseValue.split(LIST_OF_STRINGS_SEPARATOR)
    }
    override fun encode(value: List<String>) = value.joinToString(
        separator = LIST_OF_STRINGS_SEPARATOR,
    )
}

object UpdateStrategyColumnAdapter : ColumnAdapter<UpdateStrategy, Long> {
    override fun decode(databaseValue: Long): UpdateStrategy =
        UpdateStrategy.entries.getOrElse(databaseValue.toInt()) { UpdateStrategy.ALWAYS_UPDATE }

    override fun encode(value: UpdateStrategy): Long = value.ordinal.toLong()
}

object VaultMangaStatusColumnAdapter : ColumnAdapter<VaultMangaStatus, Long> {
    override fun decode(databaseValue: Long): VaultMangaStatus =
        VaultMangaStatus.entries.getOrElse(databaseValue.toInt()) { VaultMangaStatus.UNKNOWN }

    override fun encode(value: VaultMangaStatus): Long = value.ordinal.toLong()
}

object VaultMangaCollectionStateColumnAdapter : ColumnAdapter<VaultMangaCollectionState, Long> {
    override fun decode(databaseValue: Long): VaultMangaCollectionState =
        VaultMangaCollectionState.entries.getOrElse(databaseValue.toInt()) { VaultMangaCollectionState.ACTIVE }

    override fun encode(value: VaultMangaCollectionState): Long = value.ordinal.toLong()
}

object VaultChapterContentFormatColumnAdapter : ColumnAdapter<VaultChapterContentFormat, Long> {
    override fun decode(databaseValue: Long): VaultChapterContentFormat =
        VaultChapterContentFormat.entries.getOrElse(databaseValue.toInt()) { VaultChapterContentFormat.UNKNOWN }

    override fun encode(value: VaultChapterContentFormat): Long = value.ordinal.toLong()
}

object VaultCacheStateColumnAdapter : ColumnAdapter<VaultCacheState, Long> {
    override fun decode(databaseValue: Long): VaultCacheState =
        VaultCacheState.entries.getOrElse(databaseValue.toInt()) { VaultCacheState.VAULT_ONLY }

    override fun encode(value: VaultCacheState): Long = value.ordinal.toLong()
}

object VaultTransferTypeColumnAdapter : ColumnAdapter<VaultTransferType, Long> {
    override fun decode(databaseValue: Long): VaultTransferType =
        VaultTransferType.entries.getOrElse(databaseValue.toInt()) { VaultTransferType.CACHE_CHAPTER }

    override fun encode(value: VaultTransferType): Long = value.ordinal.toLong()
}

object VaultTransferStateColumnAdapter : ColumnAdapter<VaultTransferState, Long> {
    override fun decode(databaseValue: Long): VaultTransferState =
        VaultTransferState.entries.getOrElse(databaseValue.toInt()) { VaultTransferState.FAILED }

    override fun encode(value: VaultTransferState): Long = value.ordinal.toLong()
}
