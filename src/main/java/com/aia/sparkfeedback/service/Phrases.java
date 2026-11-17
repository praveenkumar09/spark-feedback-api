package com.aia.sparkfeedback.service;

import java.util.Map;
import java.util.Set;

/** Spark's fixed lines in the four business languages of Singapore. Written by the developers, not a native speaker: have them reviewed. */
public final class Phrases {
    private Phrases() {}

    public static final Set<String> LANGUAGES = Set.of("en", "ms", "zh", "ta");
    public static final String DEFAULT = "en";

    public static String normalize(String lang) {
        if (lang == null) return DEFAULT;
        String l = lang.trim().toLowerCase();
        return LANGUAGES.contains(l) ? l : DEFAULT;
    }

    public static String languageName(String lang) {
        return switch (normalize(lang)) {
            case "ms" -> "Bahasa Melayu (Malay)";
            case "zh" -> "Mandarin Chinese";
            case "ta" -> "Tamil";
            default -> "English";
        };
    }

    /** The word for "Option" read before each choice, so the customer hears "Option 1, ...". */
    public static String optionWord(String lang) {
        return switch (normalize(lang)) {
            case "ms" -> "Pilihan";
            case "zh" -> "选项";
            case "ta" -> "விருப்பம்";
            default -> "Option";
        };
    }

    /** What Spark says when it could not tell which option the customer meant. */
    public static String clarifyChoice(String lang) {
        return switch (normalize(lang)) {
            case "ms" -> "Maaf, saya tidak pasti pilihan mana yang anda maksudkan. Biar saya tanya sekali lagi.";
            case "zh" -> "抱歉，我不太确定您选的是哪一项。让我再问一次。";
            case "ta" -> "மன்னிக்கவும், நீங்கள் எந்த விருப்பத்தைச் சொன்னீர்கள் என்று எனக்குத் தெளிவாகவில்லை. மீண்டும் கேட்கிறேன்.";
            default -> "Sorry, I wasn't sure which option you meant. Let me ask again.";
        };
    }

    /** greeting (uses {name}), clarify, noSpeech, thanks. */
    public static Map<String, String> of(String lang, String name) {
        String first = name == null || name.isBlank() ? "" : name.trim().split("\\s+")[0];
        return switch (normalize(lang)) {
            case "ms" -> Map.of(
                    "greeting", "Helo" + (first.isEmpty() ? "" : " " + first) + "! Saya Spark. Terima kasih kerana memilih AIA. Saya ada beberapa soalan ringkas tentang pembelian polisi anda baru-baru ini. Anda boleh menjawab dengan bercakap atau menaip.",
                    "clarify", "Maaf, saya tidak pasti sama ada jawapan anda ya atau tidak. Biar saya tanya sekali lagi.",
                    "noSpeech", "Maaf, saya tidak dapat mendengar anda. Cuba lagi, atau taip jawapan anda.",
                    "thanks", "Sila semak kontrak polisi anda. Jika anda ada sebarang pertanyaan lanjut mengenai pembelian anda, atau sebarang maklum balas lain pada masa hadapan, sila hubungi 1800 248.");
            case "zh" -> Map.of(
                    "greeting", "您好" + (first.isEmpty() ? "" : "，" + first) + "！我是 Spark。感谢您选择友邦。关于您最近购买的保单，我有几个简短的问题。您可以用说的，也可以打字回答。",
                    "clarify", "抱歉，我不太确定您的回答是“是”还是“否”。让我再问一次。",
                    "noSpeech", "抱歉，我没有听到您的声音。请再试一次，或者直接打字回答。",
                    "thanks", "请您查阅保单合约。如果您对这次购买有任何进一步的疑问，或日后有其他意见，欢迎致电 1800 248。");
            case "ta" -> Map.of(
                    "greeting", "வணக்கம்" + (first.isEmpty() ? "" : " " + first) + "! நான் Spark. AIA-வைத் தேர்ந்தெடுத்ததற்கு நன்றி. நீங்கள் சமீபத்தில் வாங்கிய பாலிசி பற்றி சில சிறு கேள்விகள் உள்ளன. பேசியோ தட்டச்சு செய்தோ பதிலளிக்கலாம்.",
                    "clarify", "மன்னிக்கவும், உங்கள் பதில் ஆம் என்பதா இல்லை என்பதா என்று எனக்குத் தெளிவாகவில்லை. மீண்டும் கேட்கிறேன்.",
                    "noSpeech", "மன்னிக்கவும், உங்கள் குரல் எனக்குக் கேட்கவில்லை. மீண்டும் முயற்சிக்கவும், அல்லது தட்டச்சு செய்யவும்.",
                    "thanks", "உங்கள் பாலிசி ஒப்பந்தத்தை மறுஆய்வு செய்யவும். உங்கள் கொள்முதல் தொடர்பாக மேலும் ஏதேனும் விளக்கங்கள் தேவைப்பட்டால், அல்லது எதிர்காலத்தில் வேறு ஏதேனும் கருத்துகள் இருந்தால், தயவுசெய்து 1800 248 என்ற எண்ணை அழைக்கவும்.");
            default -> Map.of(
                    "greeting", "Hello" + (first.isEmpty() ? "" : " " + first) + "! I'm Spark. Thank you for choosing AIA. I have a few quick questions about your recent policy purchase. You can answer by speaking or typing.",
                    "clarify", "Sorry, I wasn't sure whether that was a yes or a no. Let me ask again.",
                    "noSpeech", "Sorry, I couldn't hear you. Please try again, or type your answer.",
                    "thanks", "Please review your policy contract and if you have any further clarifications with regards to your purchase or any other feedback in future please feel free to call 1800 248.");
        };
    }
}
