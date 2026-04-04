package com.example.videoeditorapp.model.timeline

import com.example.videoeditorapp.R

data class StickerItem(val id: String, val url: String, val category: String)

data class StickerCategory(val name: String, val stickers: List<Int>)

object StickerLibrary {
    val categories =
            listOf(
                    StickerCategory("Shapes", listOf(R.drawable.ic_heart, R.drawable.ic_star)),
                    StickerCategory("Status", listOf(R.drawable.ic_crown, R.drawable.ic_premium))
            )

    val remoteStickers =
            (1..50).map { i ->
                val emojiHex = (0x1F600 + i).toString(16)
                StickerItem(
                        "s_$i",
                        "https://raw.githubusercontent.com/googlefonts/noto-emoji/master/png/128/emoji_u$emojiHex.png",
                        if (i % 2 == 0) "Emotions" else "Objects"
                )
            }

    fun getAllStickers(): List<Int> = categories.flatMap { it.stickers }
}

object EmojiLibrary {
    val categories = mutableMapOf<String, List<String>>()

    init {
        categories["Smilies"] = (0x1F600..0x1F64F).map { String(Character.toChars(it)) }
        categories["Animals"] = (0x1F400..0x1F43F).map { String(Character.toChars(it)) }
        categories["Plants"] = (0x1F330..0x1F350).map { String(Character.toChars(it)) }
        categories["Food"] = (0x1F354..0x1F37F).map { String(Character.toChars(it)) }
        categories["Activities"] = (0x1F3A0..0x1F3C4).map { String(Character.toChars(it)) }
        categories["Travel"] = (0x1F680..0x1F6A4).map { String(Character.toChars(it)) }
        categories["Objects"] = (0x1F4A1..0x1F4D9).map { String(Character.toChars(it)) }
        categories["Hearts"] = (0x1F493..0x1F49C).map { String(Character.toChars(it)) }
    }

    fun getAllEmojis(): List<String> = categories.values.flatten()
}

data class GifItem(val id: String, val previewUrl: String, val gifUrl: String)

object GifLibrary {
    // A larger set of generic high-quality GIFs from common public sources (Mixkit, Giphy IDs)
    val trendingGifs =
            (1..30).map { i ->
                val ids =
                        listOf(
                                "3o7TKMGpxxyD60vNDO",
                                "l41lTfO3vF5pB8w6o",
                                "3o7TKVUn7iM8FMEU24",
                                "l0Hlx09YqT9X9EwBW",
                                "3o7TKX0P0Z2O5QkC52",
                                "3o7TKW5n3pIDLgL1yM",
                                "3o7TKv8p5vXyD60vNDO",
                                "3o7TKr6j9vXyD60vNDO"
                        )
                val giphyId = ids[i % ids.size]
                GifItem(
                        "g_$i",
                        "https://media.giphy.com/media/$giphyId/giphy.gif",
                        "https://media.giphy.com/media/$giphyId/giphy.gif"
                )
            }
}
