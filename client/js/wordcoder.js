// WordCoder - Base256 mnemonic word coder for human-friendly security verification

export const RUSSIAN_DICTIONARY = [
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
  "плющ", "побег", "подвиг", "полюс", "порог", "порыв", "поток", "прибой",
  "привал", "призма", "пристань", "провод", "прорыв", "пульсар", "пульс", "пустыня"
];

export class WordCoder {
  constructor(dictionary = RUSSIAN_DICTIONARY) {
    if (Array.isArray(dictionary)) {
      this.words = [...dictionary];
      this.checkDict(this.words);
      this.dictionary = {};
      this.reverseDictionary = {};
      for (let i = 0; i < this.words.length; i++) {
        this.dictionary[i] = this.words[i];
        this.reverseDictionary[this.words[i]] = i;
      }
    } else {
      const words = Object.values(dictionary);
      this.checkDict(words);
      this.dictionary = {};
      this.reverseDictionary = {};
      for (const [k, v] of Object.entries(dictionary)) {
        const num = typeof k === "string" && k.startsWith("0x") ? parseInt(k, 16) : Number(k);
        this.dictionary[num] = v;
        this.reverseDictionary[v] = num;
      }
    }
  }

  checkDict(words) {
    for (const w of words) {
      if (w.length > 10) {
        throw new TypeError(`Word size > 10 char: ${w}`);
      }
    }
    const unique = new Set(words);
    if (unique.size !== words.length) {
      throw new Error("Duplicates in WordCoder dictionary");
    }
  }

  encode(bytesArray) {
    const bytes = bytesArray instanceof Uint8Array ? bytesArray : new Uint8Array(bytesArray);
    const result = [];
    for (let i = 0; i < bytes.length; i++) {
      const b = bytes[i];
      result.push(this.dictionary[b] || "???");
    }
    return result;
  }

  decode(words) {
    const result = new Uint8Array(words.length);
    for (let i = 0; i < words.length; i++) {
      const byte = this.reverseDictionary[words[i].trim().toLowerCase()];
      if (byte === undefined) {
        throw new Error(`Unknown word in dictionary: ${words[i]}`);
      }
      result[i] = byte;
    }
    return result;
  }
}

export const defaultWordCoder = new WordCoder(RUSSIAN_DICTIONARY);
