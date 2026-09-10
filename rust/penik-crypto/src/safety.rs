use sha2::{Digest, Sha256};

use crate::errors::CryptoError;
use crate::keys::normalize_public_key;

pub const SAFETY_NUMBER_BLOCKS: usize = 5;

pub const RUSSIAN_WORDS: [&str; 256] = [
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
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SafetyFingerprint {
    pub number: String,
    pub words: Vec<String>,
    pub hex: String,
    pub qr_payload: String,
}

pub fn compute_safety_hash(keys_a: &[&[u8]], keys_b: &[&[u8]]) -> Result<[u8; 32], CryptoError> {
    let mut normalized_keys = Vec::new();
    for k in keys_a.iter().chain(keys_b.iter()) {
        if !k.is_empty() {
            normalized_keys.push(normalize_public_key(k)?);
        }
    }
    if normalized_keys.is_empty() {
        return Err(CryptoError::NoIdentityKeysProvided);
    }
    normalized_keys.sort();

    let mut concat = Vec::with_capacity(normalized_keys.len() * 32);
    for k in &normalized_keys {
        concat.extend_from_slice(k);
    }

    let hash = Sha256::digest(&concat);
    let mut out = [0u8; 32];
    out.copy_from_slice(&hash);
    Ok(out)
}

pub fn compute_safety_number(keys_a: &[&[u8]], keys_b: &[&[u8]]) -> Result<String, CryptoError> {
    let hash = compute_safety_hash(keys_a, keys_b)?;
    let mut digits = String::with_capacity(25);
    for i in (0..hash.len() - 1).step_by(2) {
        if digits.len() >= SAFETY_NUMBER_BLOCKS * 5 {
            break;
        }
        let val = ((hash[i] as u32) << 8) | (hash[i + 1] as u32);
        let s = format!("{:05}", val);
        digits.push_str(&s[0..5]);
    }

    let mut blocks = Vec::with_capacity(SAFETY_NUMBER_BLOCKS);
    for i in (0..digits.len()).step_by(5) {
        blocks.push(&digits[i..i + 5]);
    }
    Ok(blocks.join(" "))
}

pub fn compute_safety_fingerprint(
    keys_a: &[&[u8]],
    keys_b: &[&[u8]],
    user_id: Option<&str>,
) -> Result<SafetyFingerprint, CryptoError> {
    let hash = compute_safety_hash(keys_a, keys_b)?;

    let mut digits = String::with_capacity(25);
    for i in (0..hash.len() - 1).step_by(2) {
        if digits.len() >= SAFETY_NUMBER_BLOCKS * 5 {
            break;
        }
        let val = ((hash[i] as u32) << 8) | (hash[i + 1] as u32);
        let s = format!("{:05}", val);
        digits.push_str(&s[0..5]);
    }

    let mut blocks = Vec::with_capacity(SAFETY_NUMBER_BLOCKS);
    for i in (0..digits.len()).step_by(5) {
        blocks.push(&digits[i..i + 5]);
    }
    let number = blocks.join(" ");

    let words: Vec<String> = hash[0..10]
        .iter()
        .map(|&b| RUSSIAN_WORDS[b as usize].to_string())
        .collect();

    let hex = hash.iter().map(|b| format!("{:02x}", b)).collect::<String>();
    let qr_payload = match user_id {
        Some(uid) if !uid.is_empty() => format!("penik://safety?fp={hex}&uid={uid}"),
        _ => format!("penik://safety?fp={hex}"),
    };

    Ok(SafetyFingerprint {
        number,
        words,
        hex,
        qr_payload,
    })
}
