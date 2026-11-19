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

    /** greeting (uses {name}), clarify, noSpeech, thanks, plus the voice-enrollment lines (enroll1, enroll2, enrollOk, enrollRetry, enrollFail, sentence1, sentence2). */
    public static Map<String, String> of(String lang, String name) {
        Map<String, String> all = new java.util.HashMap<>(basic(lang, name));
        all.putAll(enrollment(lang, name));
        return all;
    }

    /**
     * Voice enrollment: Spark asks the customer to repeat two sentences so it can learn their voice and ignore other voices around them.
     * sentence1/sentence2 are what the customer says (shown on screen); enroll1/enroll2 are what Spark says (they include the sentence).
     */
    private static Map<String, String> enrollment(String lang, String name) {
        String first = name == null || name.isBlank() ? "" : name.trim().split("\\s+")[0];
        return switch (normalize(lang)) {
            case "ms" -> Map.of(
                    "sentence1", "Helo Spark, saya sedia untuk menjawab soalan anda, dan saya boleh mendengar anda dengan jelas.",
                    "sentence2", "Cuaca agak baik hari ini, dan saya gembira untuk bercakap tentang polisi saya.",
                    "enroll1", "Sebelum kita mulakan, saya akan mengenali suara anda supaya saya boleh mengabaikan suara lain di sekeliling anda. Sila baca kuat-kuat ayat pada skrin anda.",
                    "enroll2", "Terima kasih. Sekarang sila baca yang ini.",
                    "resume", "Selamat kembali" + (first.isEmpty() ? "" : ", " + first) + "! Mari kita teruskan dari tempat kita berhenti.",
                    "welcomeBack", "Selamat kembali" + (first.isEmpty() ? "" : ", " + first) + "! Mari kita teruskan dari tempat kita berhenti. Mula-mula, semakan suara ringkas: saya akan mengenali suara anda supaya saya boleh mengabaikan suara lain di sekeliling anda. Sila baca kuat-kuat ayat pada skrin anda.",
                    "welcome", "Helo" + (first.isEmpty() ? "" : " " + first) + "! Saya Spark. Terima kasih kerana memilih AIA. Saya ada beberapa soalan ringkas tentang pembelian polisi anda baru-baru ini. Mula-mula, semakan suara ringkas: saya akan mengenali suara anda supaya saya boleh mengabaikan suara lain di sekeliling anda. Sila baca kuat-kuat ayat pada skrin anda.",
                    "enrollOk", "Terima kasih, saya sudah mengenali suara anda. Mari kita mulakan.",
                    "enrollRetry", "Maaf, saya tidak dapat menangkapnya dengan jelas. Sila sebut sekali lagi.",
                    "enrollFail", "Saya tidak dapat mengenali suara anda dengan jelas, jadi saya akan mendengar semua orang buat masa ini.");
            case "zh" -> Map.of(
                    "sentence1", "你好 Spark，我已经准备好回答你的问题，而且我听得很清楚。",
                    "sentence2", "今天天气很不错，我很乐意谈谈我的保单，也很高兴告诉你我的想法。",
                    "enroll1", "开始之前，我会先熟悉一下您的声音，这样我就能忽略您周围的其他声音。请大声朗读屏幕上的句子。",
                    "enroll2", "谢谢。现在请读这一句。",
                    "resume", "欢迎回来" + (first.isEmpty() ? "" : "，" + first) + "！我们从上次停下的地方继续。",
                    "welcomeBack", "欢迎回来" + (first.isEmpty() ? "" : "，" + first) + "！我们从上次停下的地方继续。首先做一个简单的声音确认：我会熟悉您的声音，这样就能忽略您周围的其他声音。请大声朗读屏幕上的句子。",
                    "welcome", "您好" + (first.isEmpty() ? "" : "，" + first) + "！我是 Spark。感谢您选择友邦。关于您最近购买的保单，我有几个简短的问题。首先做一个简单的声音确认：我会熟悉您的声音，这样就能忽略您周围的其他声音。请大声朗读屏幕上的句子。",
                    "enrollOk", "谢谢，我已经记住您的声音了。我们开始吧。",
                    "enrollRetry", "抱歉，我没有听清楚。请再说一次。",
                    "enrollFail", "我暂时无法锁定您的声音，所以先听所有人的声音。");
            case "ta" -> Map.of(
                    "sentence1", "வணக்கம் Spark, உங்கள் கேள்விகளுக்குப் பதிலளிக்க நான் தயாராக இருக்கிறேன், உங்கள் குரல் எனக்குத் தெளிவாகக் கேட்கிறது.",
                    "sentence2", "இன்று வானிலை நன்றாக இருக்கிறது, என் பாலிசியைப் பற்றிப் பேசுவதில் எனக்கு மகிழ்ச்சி.",
                    "enroll1", "தொடங்குவதற்கு முன், உங்களைச் சுற்றியுள்ள மற்ற குரல்களை நான் புறக்கணிக்க, உங்கள் குரலை அறிந்துகொள்கிறேன். திரையில் உள்ள வாக்கியத்தை உரக்கப் படியுங்கள்.",
                    "enroll2", "நன்றி. இப்போது இதைப் படியுங்கள்.",
                    "resume", "மீண்டும் வரவேற்கிறேன்" + (first.isEmpty() ? "" : " " + first) + "! நாம் நிறுத்திய இடத்திலிருந்து தொடர்வோம்.",
                    "welcomeBack", "மீண்டும் வரவேற்கிறேன்" + (first.isEmpty() ? "" : " " + first) + "! நாம் நிறுத்திய இடத்திலிருந்து தொடர்வோம். முதலில், ஒரு சிறிய குரல் சோதனை: உங்களைச் சுற்றியுள்ள மற்ற குரல்களை நான் புறக்கணிக்க, உங்கள் குரலை அறிந்துகொள்கிறேன். திரையில் உள்ள வாக்கியத்தை உரக்கப் படியுங்கள்.",
                    "welcome", "வணக்கம்" + (first.isEmpty() ? "" : " " + first) + "! நான் Spark. AIA-வைத் தேர்ந்தெடுத்ததற்கு நன்றி. நீங்கள் சமீபத்தில் வாங்கிய பாலிசி பற்றி சில சிறு கேள்விகள் உள்ளன. முதலில், ஒரு சிறிய குரல் சோதனை: உங்களைச் சுற்றியுள்ள மற்ற குரல்களை நான் புறக்கணிக்க, உங்கள் குரலை அறிந்துகொள்கிறேன். திரையில் உள்ள வாக்கியத்தை உரக்கப் படியுங்கள்.",
                    "enrollOk", "நன்றி, உங்கள் குரலை அறிந்துகொண்டேன். தொடங்குவோம்.",
                    "enrollRetry", "மன்னிக்கவும், எனக்குத் தெளிவாகக் கேட்கவில்லை. தயவுசெய்து மீண்டும் சொல்லுங்கள்.",
                    "enrollFail", "உங்கள் குரலை என்னால் உறுதியாக அறிய முடியவில்லை, எனவே தற்போதைக்கு எல்லோரையும் கேட்கிறேன்.");
            default -> Map.of(
                    "sentence1", "Hello Spark, I am ready to answer your questions, and I can hear you clearly.",
                    "sentence2", "The weather is quite nice today, and I am happy to talk about my policy.",
                    "enroll1", "Before we begin, I'll learn your voice so I can ignore other voices around you. Please read the sentence on your screen out loud.",
                    "enroll2", "Thank you. Now please read this one.",
                    "resume", "Welcome back" + (first.isEmpty() ? "" : ", " + first) + "! Let's carry on where we left off.",
                    "welcomeBack", "Welcome back" + (first.isEmpty() ? "" : ", " + first) + "! Let's carry on where we left off. First, a quick voice check: I'll learn your voice so I can ignore other voices around you. Please read the sentence on your screen out loud.",
                    "welcome", "Hello" + (first.isEmpty() ? "" : " " + first) + "! I'm Spark. Thank you for choosing AIA. I have a few quick questions about your recent policy purchase. First, a quick voice check: I'll learn your voice so I can ignore other voices around you. Please read the sentence on your screen out loud.",
                    "enrollOk", "Thank you, I've got your voice. Let's begin.",
                    "enrollRetry", "Sorry, I didn't catch that clearly. Please read the sentence once more.",
                    "enrollFail", "I couldn't lock onto your voice, so I'll listen to everyone for now.");
        };
    }

    private static Map<String, String> basic(String lang, String name) {
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
