package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files

internal object SasayakiAudiobookMp4Metadata {
    fun parse(file: File): SasayakiAudiobookMetadata {
        return try {
            if (!file.isFile) return SasayakiAudiobookMetadata.Empty
            Files.newByteChannel(file.toPath()).use(::parse)
        } catch (_: Exception) {
            SasayakiAudiobookMetadata.Empty
        }
    }

    fun parse(channel: SeekableByteChannel): SasayakiAudiobookMetadata =
        try {
            Mp4MetadataReader(channel).read()
        } catch (_: Exception) {
            SasayakiAudiobookMetadata.Empty
        }
}

private class Mp4MetadataReader(
    private val input: SeekableByteChannel,
) {
    fun read(): SasayakiAudiobookMetadata {
        val moov = childBox(start = 0L, end = input.size(), type = "moov") ?: return SasayakiAudiobookMetadata.Empty
        val meta = childBox(moov, "udta")?.let { udta -> childBox(udta, "meta") }
            ?: childBox(moov, "meta")
            ?: return SasayakiAudiobookMetadata.Empty
        if (meta.contentStart + FullBoxHeaderSize > meta.end) return SasayakiAudiobookMetadata.Empty
        val ilst = childBox(start = meta.contentStart + FullBoxHeaderSize, end = meta.end, type = "ilst")
            ?: return SasayakiAudiobookMetadata.Empty

        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var artworkData: ByteArray? = null
        for (item in childBoxes(start = ilst.contentStart, end = ilst.end)) {
            val data = readMetadataData(item) ?: continue
            when (item.type) {
                Mp4TitleItem -> title = title ?: data.utf8Text()
                Mp4ArtistItem -> artist = artist ?: data.utf8Text()
                Mp4AlbumArtistItem -> albumArtist = albumArtist ?: data.utf8Text()
                Mp4CoverItem -> artworkData = artworkData ?: data.bytes.takeIf { it.isNotEmpty() }
            }
        }
        return SasayakiAudiobookMetadata(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            artworkData = artworkData,
        )
    }

    private fun readMetadataData(item: Mp4MetadataBox): Mp4MetadataData? {
        val data = childBox(item, "data") ?: return null
        if (data.contentStart + MetadataDataHeaderSize > data.end) return null
        input.position(data.contentStart)
        input.skip(IntSize)
        input.skip(IntSize)
        val byteCount = data.end - input.position()
        if (byteCount <= 0L || byteCount > MaxMetadataDataBytes) return null
        val bytes = ByteArray(byteCount.toInt())
        input.readFully(bytes)
        return Mp4MetadataData(bytes)
    }

    private fun childBox(parent: Mp4MetadataBox, type: String): Mp4MetadataBox? =
        childBox(start = parent.contentStart, end = parent.end, type = type)

    private fun childBox(start: Long, end: Long, type: String): Mp4MetadataBox? =
        childBoxes(start = start, end = end).firstOrNull { it.type == type }

    private fun childBoxes(start: Long, end: Long): List<Mp4MetadataBox> {
        val boxes = mutableListOf<Mp4MetadataBox>()
        var position = start
        while (position + BoxHeaderSize <= end) {
            val box = readBox(position, end) ?: break
            boxes += box
            position = box.end
        }
        return boxes
    }

    private fun readBox(position: Long, parentEnd: Long): Mp4MetadataBox? {
        input.position(position)
        val shortSize = input.readUInt32()
        val type = input.readAscii(IntSize)
        val headerSize: Long
        val size: Long
        if (shortSize == 1L) {
            headerSize = ExtendedBoxHeaderSize
            size = input.readUInt64()
        } else {
            headerSize = BoxHeaderSize
            size = if (shortSize == 0L) parentEnd - position else shortSize
        }
        if (size < headerSize || position + size > parentEnd) return null
        return Mp4MetadataBox(
            type = type,
            start = position,
            headerSize = headerSize,
            size = size,
        )
    }

    private fun SeekableByteChannel.skip(bytes: Int) {
        position(position() + bytes)
    }

    private fun SeekableByteChannel.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.ISO_8859_1)
    }

    private fun SeekableByteChannel.readUInt32(): Long {
        var value = 0L
        repeat(IntSize) {
            value = (value shl 8) or (readUnsignedByte().toLong() and 0xff)
        }
        return value
    }

    private fun SeekableByteChannel.readUInt64(): Long {
        var value = 0L
        repeat(LongSize) {
            value = (value shl 8) or (readUnsignedByte().toLong() and 0xff)
        }
        return value
    }

    private fun SeekableByteChannel.readUnsignedByte(): Int {
        val buffer = ByteBuffer.allocate(ByteSize)
        if (read(buffer) != ByteSize) return 0
        buffer.flip()
        return buffer.get().toInt() and 0xff
    }

    private fun SeekableByteChannel.readFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) return
        }
    }
}

private data class Mp4MetadataData(
    val bytes: ByteArray,
) {
    fun utf8Text(): String? =
        bytes.toString(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
}

private data class Mp4MetadataBox(
    val type: String,
    val start: Long,
    val headerSize: Long,
    val size: Long,
) {
    val contentStart: Long = start + headerSize
    val end: Long = start + size
}

private const val Mp4TitleItem = "\u00a9nam"
private const val Mp4ArtistItem = "\u00a9ART"
private const val Mp4AlbumArtistItem = "aART"
private const val Mp4CoverItem = "covr"
private const val BoxHeaderSize = 8L
private const val ExtendedBoxHeaderSize = 16L
private const val FullBoxHeaderSize = 4
private const val MetadataDataHeaderSize = 8
private const val IntSize = 4
private const val LongSize = 8
private const val ByteSize = 1
private const val MaxMetadataDataBytes = 20L * 1024L * 1024L
