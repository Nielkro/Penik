package niel.kro.penik.data.crypto

import java.security.MessageDigest

/**
 * Conversation safety number — the human-comparable fingerprint of two identity
 * keys.
 *
 * This is the single Android definition and it must stay byte-for-byte identical
 * to `computeSafetyNumber` in `client/js/crypto.js`. The codebase previously
 * carried three variants; a fingerprint that differs across platforms is worse
 * than none, because the users compare, see different numbers, and conclude they
 * are being intercepted.
 *
 * Shape: strip the legacy 0x05 type prefix, order the two keys by *unsigned* byte
 * value (Kotlin bytes are signed, so the mask is not optional), SHA-256 over the
 * 64 concatenated bytes, then 5 groups of 5 digits.
 */
object SafetyNumber {

    private const val BLOCKS = 5

    fun computeHash(identityKeysA: List<ByteArray>, identityKeysB: List<ByteArray>): ByteArray {
        val allKeys = (identityKeysA + identityKeysB)
            .filter { it.isNotEmpty() }
            .map { normalize(it) }
            .sortedWith { a, b -> compareUnsigned(a, b) }

        require(allKeys.isNotEmpty()) { "safety number: no identity keys provided" }

        val concat = ByteArray(allKeys.size * 32)
        allKeys.forEachIndexed { index, key ->
            System.arraycopy(key, 0, concat, index * 32, 32)
        }

        return MessageDigest.getInstance("SHA-256").digest(concat)
    }

    fun computeFingerprintHex(identityKeysA: List<ByteArray>, identityKeysB: List<ByteArray>): String {
        val hash = computeHash(identityKeysA, identityKeysB)
        val sb = java.lang.StringBuilder(hash.size * 2)
        for (b in hash) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun computeQrPayload(identityKeysA: List<ByteArray>, identityKeysB: List<ByteArray>): String {
        return "penik://safety?fp=" + computeFingerprintHex(identityKeysA, identityKeysB)
    }

    fun compute(identityKeysA: List<ByteArray>, identityKeysB: List<ByteArray>): String {
        val hash = computeHash(identityKeysA, identityKeysB)

        val digits = StringBuilder()
        var i = 0
        while (i + 1 < hash.size && digits.length < BLOCKS * 5) {
            val value = ((hash[i].toInt() and 0xFF) shl 8) or (hash[i + 1].toInt() and 0xFF)
            digits.append(value.toString().padStart(5, '0').take(5))
            i += 2
        }

        return digits.toString().chunked(5).joinToString(" ")
    }

    fun compute(identityKeyA: ByteArray, identityKeyB: ByteArray): String {
        return compute(listOf(identityKeyA), listOf(identityKeyB))
    }

    val RUSSIAN_WORDS = arrayOf(
        "агат", "айсберг", "акула", "алмаз", "алтарь", "аметист", "ангел", "антенна",
        "апельсин", "арка", "арсенал", "атлас", "атом", "багор", "байкал", "бамбук",
        "бард", "барьер", "башня", "бедуин", "берег", "беркут", "бисер", "бластер",
        "буран", "буря", "бухта", "валун", "ветер", "ветка", "вершина", "весна",
        "витязь", "вишня", "вихрь", "водопад", "волна", "волокно", "ворон", "восток",
        "вулкан", "вымпел", "высь", "гавань", "газон", "галактика", "гвардия", "гейзер",
        "гелий", "гепард", "герб", "гитара", "гладь", "глина", "глубина", "горизонт",
        "горн", "город", "гранит", "грот", "гроза", "гром", "дельфин", "дерево",
        "дельта", "джип", "джунгли", "дирижабль", "диск", "дичь", "дождь", "дозор",
        "долина", "домбай", "доспех", "древо", "дюна", "дым", "жасмин", "жемчуг",
        "жерло", "жила", "завет", "закат", "залив", "замок", "запад", "заповедник",
        "заря", "заслон", "затишье", "звезда", "зефир", "зима", "знак", "знамя",
        "золото", "зубр", "ива", "игла", "игуана", "изумруд", "ильм", "импульс",
        "иней", "ирбис", "искра", "исток", "йод", "кабель", "кадет", "калибр",
        "камея", "камень", "камыш", "каньон", "капля", "караван", "карат", "каскад",
        "катер", "кедр", "кипарис", "клан", "клевер", "клен", "клинок", "ключ",
        "кобальт", "ковчег", "код", "кокос", "колчан", "комета", "компас", "кондор",
        "конь", "коралл", "корвет", "космос", "костер", "кратер", "кремень", "крепость",
        "кристалл", "крона", "крыло", "кубок", "купол", "курган", "куст", "лабиринт",
        "лагуна", "лазер", "лазурь", "ландыш", "лапа", "ларец", "ласточка", "лебедь",
        "ледник", "легион", "легенда", "лемур", "лента", "леопард", "лес", "лето",
        "ливень", "лилия", "лимон", "липа", "лира", "лиса", "лист", "лодка",
        "локомотив", "лоно", "лотос", "луч", "луг", "луна", "магнит", "май",
        "малахит", "малина", "манго", "мачта", "маяк", "медведь", "медуза", "металл",
        "метеорит", "меч", "мираж", "мозаика", "молния", "монолит", "море", "мост",
        "мох", "музыка", "муссон", "набат", "небо", "нефрит", "нить", "новатор",
        "ножны", "ночь", "оазис", "оберег", "облако", "обрыв", "овраг", "океан",
        "око", "олень", "олимп", "опал", "орбита", "орден", "орел", "орех",
        "орион", "орхидея", "осада", "осина", "остров", "отзвук", "отмель", "отряд",
        "павлин", "паладин", "пальма", "панцирь", "парус", "пассат", "перо", "песок",
        "пещера", "пингвин", "пирамида", "пирс", "пламя", "планета", "племя", "плита",
        "плющ", "побег", "подвиг", "полюс", "порог", "порыв", "поток", "прибой"
    )

    fun computeWords(identityKeyA: ByteArray, identityKeyB: ByteArray): List<String> {
        return computeWords(listOf(identityKeyA), listOf(identityKeyB))
    }

    fun computeWords(identityKeysA: List<ByteArray>, identityKeysB: List<ByteArray>): List<String> {
        val hash = computeHash(identityKeysA, identityKeysB)
        return (0 until 10).map { i ->
            val idx = hash[i].toInt() and 0xFF
            RUSSIAN_WORDS[idx]
        }
    }

    private fun normalize(key: ByteArray): ByteArray {
        val clean = if (key.size == 33 && key[0] == 5.toByte()) key.copyOfRange(1, 33) else key
        require(clean.size == 32) { "safety number: expected a 32-byte identity key, got ${clean.size}" }
        return clean
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until 32) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }
}
