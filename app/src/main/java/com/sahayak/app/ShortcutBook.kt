package com.sahayak.app

import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/** Direct commands ki library. Yahan Android ka code nahi, taaki tests chal saken. */
object ShortcutBook {
    data class Site(val url: String, val app: String?)
    data class Hit(val action: String, val value: String = "", val extra: String = "")

    val SITES = mapOf(
        "google" to Site("https://www.google.com/search?q=", null),
        "bing" to Site("https://www.bing.com/search?q=", null),
        "duckduckgo" to Site("https://duckduckgo.com/?q=", null),
        "wikipedia" to Site("https://en.wikipedia.org/w/index.php?search=", null),
        "google images" to Site("https://www.google.com/search?tbm=isch&q=", null),
        "images" to Site("https://www.google.com/search?tbm=isch&q=", null),
        "google news" to Site("https://news.google.com/search?q=", null),
        "news" to Site("https://news.google.com/search?q=", null),
        "google maps" to Site("MAPS", null),
        "maps" to Site("MAPS", null),
        "youtube" to Site("https://www.youtube.com/results?search_query=", "com.google.android.youtube"),
        "youtube music" to Site("https://music.youtube.com/search?q=", "com.google.android.apps.youtube.music"),
        "play store" to Site("market://search?q=", "com.android.vending"),
        "spotify" to Site("spotify:search:", "com.spotify.music"),
        "gaana" to Site("https://gaana.com/search/", null),
        "jiosaavn" to Site("https://www.jiosaavn.com/search/", null),
        "wynk" to Site("https://wynk.in/music/search/", null),
        "amazon" to Site("https://www.amazon.in/s?k=", "in.amazon.mShop.android.shopping"),
        "flipkart" to Site("https://www.flipkart.com/search?q=", "com.flipkart.android"),
        "myntra" to Site("https://www.myntra.com/", null),
        "ajio" to Site("https://www.ajio.com/search/?text=", null),
        "meesho" to Site("https://www.meesho.com/search?q=", null),
        "nykaa" to Site("https://www.nykaa.com/search/result/?q=", null),
        "snapdeal" to Site("https://www.snapdeal.com/search?keyword=", null),
        "tata cliq" to Site("https://www.tatacliq.com/search/?searchCategory=all&text=", null),
        "croma" to Site("https://www.croma.com/searchB?q=", null),
        "reliance digital" to Site("https://www.reliancedigital.in/search?q=", null),
        "swiggy" to Site("https://www.swiggy.com/search?query=", "in.swiggy.android"),
        "zomato" to Site("https://www.zomato.com/search?q=", "com.application.zomato"),
        "blinkit" to Site("https://blinkit.com/s/?q=", null),
        "zepto" to Site("https://www.zeptonow.com/search?query=", null),
        "bigbasket" to Site("https://www.bigbasket.com/ps/?q=", null),
        "jiomart" to Site("https://www.jiomart.com/search/", null),
        "1mg" to Site("https://www.1mg.com/search/all?name=", null),
        "netmeds" to Site("https://www.netmeds.com/catalogsearch/result/", null),
        "pharmeasy" to Site("https://pharmeasy.in/search/all?name=", null),
        "apollo pharmacy" to Site("https://www.apollopharmacy.in/search-medicines/", null),
        "makemytrip" to Site("https://www.makemytrip.com/", null),
        "justdial" to Site("https://www.justdial.com/search?q=", null),
        "indiamart" to Site("https://dir.indiamart.com/search.mp?ss=", null),
        "olx" to Site("https://www.olx.in/items/q-", null),
        "quikr" to Site("https://www.quikr.com/search?q=", null),
        "naukri" to Site("https://www.naukri.com/", null),
        "indeed" to Site("https://in.indeed.com/jobs?q=", null),
        "linkedin" to Site("https://www.linkedin.com/search/results/all/?keywords=", null),
        "twitter" to Site("https://x.com/search?q=", null),
        "x" to Site("https://x.com/search?q=", null),
        "reddit" to Site("https://www.reddit.com/search/?q=", null),
        "facebook" to Site("https://www.facebook.com/search/top?q=", null),
        "instagram" to Site("https://www.instagram.com/explore/search/keyword/?q=", null),
        "pinterest" to Site("https://www.pinterest.com/search/pins/?q=", null),
        "quora" to Site("https://www.quora.com/search?q=", null),
        "github" to Site("https://github.com/search?q=", null),
        "stack overflow" to Site("https://stackoverflow.com/search?q=", null),
        "imdb" to Site("https://www.imdb.com/find/?q=", null),
        "hotstar" to Site("https://www.hotstar.com/in/explore?search_query=", null),
        "netflix" to Site("https://www.netflix.com/search?q=", null),
        "prime video" to Site("https://www.primevideo.com/search?phrase=", null),
        "gmail" to Site("https://mail.google.com/mail/u/0/#search/", null),
        "google drive" to Site("https://drive.google.com/drive/search?q=", null),
        "drive" to Site("https://drive.google.com/drive/search?q=", null),
        "google photos" to Site("https://photos.google.com/search/", null),
        "photos" to Site("https://photos.google.com/search/", null),
        "moneycontrol" to Site("https://www.moneycontrol.com/stocks/cptmarket/compsearchnew.php?fname=", null),
        "cricbuzz" to Site("https://www.cricbuzz.com/search?q=", null),
        "chatgpt" to Site("https://chatgpt.com/?q=", null),
        "perplexity" to Site("https://www.perplexity.ai/search?q=", null),
        "gemini" to Site("https://gemini.google.com/app?q=", null)
    )
    val WEBSITES = mapOf(
        "google" to "https://www.google.com",
        "youtube" to "https://www.youtube.com",
        "gmail" to "https://mail.google.com",
        "wikipedia" to "https://www.wikipedia.org",
        "amazon" to "https://www.amazon.in",
        "flipkart" to "https://www.flipkart.com",
        "myntra" to "https://www.myntra.com",
        "meesho" to "https://www.meesho.com",
        "swiggy" to "https://www.swiggy.com",
        "zomato" to "https://www.zomato.com",
        "blinkit" to "https://blinkit.com",
        "zepto" to "https://www.zeptonow.com",
        "bigbasket" to "https://www.bigbasket.com",
        "1mg" to "https://www.1mg.com",
        "netmeds" to "https://www.netmeds.com",
        "pharmeasy" to "https://pharmeasy.in",
        "irctc" to "https://www.irctc.co.in",
        "confirmtkt" to "https://www.confirmtkt.com",
        "redbus" to "https://www.redbus.in",
        "makemytrip" to "https://www.makemytrip.com",
        "goibibo" to "https://www.goibibo.com",
        "oyo" to "https://www.oyorooms.com",
        "booking" to "https://www.booking.com",
        "digilocker" to "https://www.digilocker.gov.in",
        "aadhaar" to "https://uidai.gov.in",
        "pan" to "https://www.incometax.gov.in",
        "income tax" to "https://www.incometax.gov.in",
        "epfo" to "https://www.epfindia.gov.in",
        "gst" to "https://www.gst.gov.in",
        "passport" to "https://www.passportindia.gov.in",
        "voter id" to "https://voters.eci.gov.in",
        "umang" to "https://web.umang.gov.in",
        "cowin" to "https://www.cowin.gov.in",
        "chatgpt" to "https://chatgpt.com",
        "gemini" to "https://gemini.google.com",
        "claude" to "https://claude.ai",
        "github" to "https://github.com",
        "linkedin" to "https://www.linkedin.com",
        "facebook" to "https://www.facebook.com",
        "instagram" to "https://www.instagram.com",
        "twitter" to "https://x.com",
        "x" to "https://x.com",
        "reddit" to "https://www.reddit.com",
        "pinterest" to "https://www.pinterest.com",
        "quora" to "https://www.quora.com",
        "netflix" to "https://www.netflix.com",
        "hotstar" to "https://www.hotstar.com",
        "prime video" to "https://www.primevideo.com",
        "spotify" to "https://open.spotify.com",
        "gaana" to "https://gaana.com",
        "jiosaavn" to "https://www.jiosaavn.com",
        "cricbuzz" to "https://www.cricbuzz.com",
        "moneycontrol" to "https://www.moneycontrol.com",
        "google news" to "https://news.google.com",
        "news" to "https://news.google.com",
        "google drive" to "https://drive.google.com",
        "drive" to "https://drive.google.com",
        "google photos" to "https://photos.google.com",
        "google docs" to "https://docs.google.com",
        "google sheets" to "https://sheets.google.com",
        "google slides" to "https://slides.google.com",
        "google calendar" to "https://calendar.google.com",
        "google keep" to "https://keep.google.com",
        "google translate" to "https://translate.google.com",
        "translate" to "https://translate.google.com",
        "google maps" to "https://maps.google.com",
        "weather" to "https://www.google.com/search?q=weather",
        "speed test" to "https://fast.com"
    )
    private val SETTINGS = mapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "wi-fi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "airplane mode" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "flight mode" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "hotspot" to "android.settings.WIRELESS_SETTINGS",
        "tethering" to "android.settings.WIRELESS_SETTINGS",
        "nfc" to "android.settings.NFC_SETTINGS",
        "data usage" to "android.settings.DATA_USAGE_SETTINGS",
        "mobile data" to "android.settings.DATA_ROAMING_SETTINGS",
        "network" to "android.settings.WIRELESS_SETTINGS",
        "sim" to "android.settings.NETWORK_OPERATOR_SETTINGS",
        "vpn" to "android.settings.VPN_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "brightness" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "volume" to "android.settings.SOUND_SETTINGS",
        "notification" to "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
        "notifications" to "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
        "do not disturb" to "android.settings.ZEN_MODE_PRIORITY_SETTINGS",
        "apps" to "android.settings.MANAGE_APPLICATIONS_SETTINGS",
        "app" to "android.settings.MANAGE_APPLICATIONS_SETTINGS",
        "default apps" to "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "memory" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "battery" to "android.settings.BATTERY_SAVER_SETTINGS",
        "battery saver" to "android.settings.BATTERY_SAVER_SETTINGS",
        "security" to "android.settings.SECURITY_SETTINGS",
        "privacy" to "android.settings.PRIVACY_SETTINGS",
        "biometric" to "android.settings.BIOMETRIC_ENROLL",
        "accounts" to "android.settings.SYNC_SETTINGS",
        "sync" to "android.settings.SYNC_SETTINGS",
        "date" to "android.settings.DATE_SETTINGS",
        "time" to "android.settings.DATE_SETTINGS",
        "language" to "android.settings.LOCALE_SETTINGS",
        "keyboard" to "android.settings.INPUT_METHOD_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
        "developer" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
        "about phone" to "android.settings.DEVICE_INFO_SETTINGS",
        "print" to "android.settings.PRINT_SETTINGS",
        "cast" to "android.settings.CAST_SETTINGS",
        "night light" to "android.settings.NIGHT_DISPLAY_SETTINGS",
        "home" to "android.settings.HOME_SETTINGS",
        "wallpaper" to "android.settings.DISPLAY_SETTINGS",
        "system" to "android.settings.SETTINGS"
    )
    private val SYSTEM = mapOf(
        "take a screenshot" to "screenshot", "take screenshot" to "screenshot", "screenshot" to "screenshot", "capture screen" to "screenshot",
        "lock the phone" to "lock", "lock phone" to "lock", "lock the screen" to "lock", "lock screen" to "lock",
        "go home" to "home", "home screen" to "home", "home" to "home", "go back" to "back", "back" to "back",
        "show notifications" to "notifications", "open notifications" to "notifications", "close notifications" to "dismiss_shade", "hide notifications" to "dismiss_shade",
        "open quick settings" to "quick_settings", "show quick settings" to "quick_settings",
        "show recent apps" to "recents", "open recent apps" to "recents", "recent apps" to "recents", "switch app" to "last_app", "last app" to "last_app", "previous app" to "last_app",
        "open power menu" to "power_menu", "show power menu" to "power_menu", "split screen" to "split_screen",
        "scroll down" to "scroll_down", "scroll up" to "scroll_up", "page down" to "scroll_down", "page up" to "scroll_up", "swipe left" to "swipe_left", "swipe right" to "swipe_right", "next page" to "swipe_left", "previous page" to "swipe_right",
        "press enter" to "enter", "press send" to "enter",
        "take a photo" to "camera", "take a picture" to "camera", "take a selfie" to "camera", "open camera" to "camera", "camera" to "camera", "record a video" to "video", "record video" to "video", "take a video" to "video",
        "show alarms" to "show_alarms", "open alarms" to "show_alarms", "my alarms" to "show_alarms", "show timers" to "show_timers", "open timer" to "show_timers", "stop alarm" to "dismiss_alarm", "dismiss alarm" to "dismiss_alarm", "snooze alarm" to "snooze_alarm", "snooze" to "snooze_alarm",
        "silent mode" to "ringer_silent", "turn on silent mode" to "ringer_silent", "put phone on silent" to "ringer_silent", "silent the phone" to "ringer_silent", "phone silent" to "ringer_silent",
        "vibrate mode" to "ringer_vibrate", "turn on vibrate mode" to "ringer_vibrate", "put phone on vibrate" to "ringer_vibrate",
        "normal mode" to "ringer_normal", "ring mode" to "ringer_normal", "turn off silent mode" to "ringer_normal", "ringer on" to "ringer_normal",
        "mute" to "volume_mute", "mute the phone" to "volume_mute", "mute volume" to "volume_mute", "unmute" to "volume_unmute", "unmute the phone" to "volume_unmute", "unmute volume" to "volume_unmute",
        "volume up" to "volume_up", "increase volume" to "volume_up", "turn up the volume" to "volume_up", "turn volume up" to "volume_up", "louder" to "volume_up",
        "volume down" to "volume_down", "decrease volume" to "volume_down", "turn down the volume" to "volume_down", "turn volume down" to "volume_down", "quieter" to "volume_down",
        "turn on flashlight" to "torch_on", "turn on the flashlight" to "torch_on", "flashlight on" to "torch_on", "torch on" to "torch_on", "turn on torch" to "torch_on",
        "turn off flashlight" to "torch_off", "turn off the flashlight" to "torch_off", "flashlight off" to "torch_off", "torch off" to "torch_off", "turn off torch" to "torch_off",
        "pause" to "media_pause", "pause music" to "media_pause", "pause video" to "media_pause", "pause song" to "media_pause", "stop music" to "media_stop", "stop song" to "media_stop",
        "resume" to "media_resume", "resume music" to "media_resume", "resume video" to "media_resume", "play music" to "media_resume", "play song" to "media_resume",
        "next" to "media_next", "next song" to "media_next", "next track" to "media_next", "next video" to "media_next", "skip song" to "media_next", "previous" to "media_previous", "previous song" to "media_previous", "previous track" to "media_previous", "last song" to "media_previous",
        "fast forward" to "media_forward", "rewind" to "media_rewind",
        "what time is it" to "time", "what's the time" to "time", "what is the time" to "time", "tell me the time" to "time", "time" to "time",
        "what's the date" to "date", "what is the date" to "date", "what is today's date" to "date", "what day is it" to "date", "what is today" to "date", "today's date" to "date", "date" to "date",
        "battery level" to "battery", "how much battery" to "battery", "how much battery is left" to "battery", "what is the battery level" to "battery", "battery percentage" to "battery", "battery" to "battery",
        "how much storage" to "storage", "how much storage is left" to "storage", "free storage" to "storage", "storage left" to "storage",
        "what phone is this" to "device", "phone model" to "device", "android version" to "android", "which android version" to "android",
        "where am i" to "my_location", "show my location" to "my_location", "my location" to "my_location", "traffic near me" to "my_location",
        "new note" to "new_note", "add contact" to "add_contact", "new contact" to "add_contact", "new email" to "new_email", "write an email" to "new_email", "compose email" to "new_email", "open browser" to "browser"
    )
    private val CATEGORIES = mapOf(
        "gallery" to "APP_GALLERY", "photos" to "APP_GALLERY", "contacts" to "APP_CONTACTS", "messages" to "APP_MESSAGING", "sms" to "APP_MESSAGING",
        "calculator" to "APP_CALCULATOR", "calendar" to "APP_CALENDAR", "music" to "APP_MUSIC", "files" to "APP_FILES", "file manager" to "APP_FILES",
        "email" to "APP_EMAIL", "maps" to "APP_MAPS", "browser" to "APP_BROWSER", "market" to "APP_MARKET"
    )
    private val UNITS = mapOf(
        "km" to ("m" to 1000.0), "kilometer" to ("m" to 1000.0), "kilometers" to ("m" to 1000.0), "mile" to ("m" to 1609.344), "miles" to ("m" to 1609.344),
        "meter" to ("m" to 1.0), "meters" to ("m" to 1.0), "m" to ("m" to 1.0), "cm" to ("m" to 0.01), "inch" to ("m" to 0.0254), "inches" to ("m" to 0.0254),
        "foot" to ("m" to 0.3048), "feet" to ("m" to 0.3048), "kg" to ("g" to 1000.0), "kilo" to ("g" to 1000.0), "gram" to ("g" to 1.0), "grams" to ("g" to 1.0),
        "pound" to ("g" to 453.592), "pounds" to ("g" to 453.592), "liter" to ("l" to 1.0), "litre" to ("l" to 1.0), "liters" to ("l" to 1.0), "litres" to ("l" to 1.0), "ml" to ("l" to 0.001), "gallon" to ("l" to 3.78541), "gallons" to ("l" to 3.78541)
    )
    private val WEEK = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val IC = RegexOption.IGNORE_CASE
    private val SITE_NAMES = SITES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val SITE_SEARCH = Regex("^(?:search for|search|find|look up|look for|show me|buy|order|shop for|watch|listen to) (.+?) (?:on|in) ($SITE_NAMES)$", IC)
    private val PLAY = Regex("^play (.+?)(?: on youtube)?$", IC)
    private val WEB = Regex("^(?:google|search for|search the web for|search|look up|meaning of|define|what does (.+?) mean)\\s*(.*)$", IC)
    private val NAVIGATE = Regex("^(?:navigate to|directions to|take me to|how do i get to|route to) (.+)$", IC)
    private val SHOW_MAP = Regex("^(?:show (.+?) on (?:the )?maps?|where is (.+))$", IC)
    private val NEAR = Regex("^(?:find|show|search|where is|nearest)(?: the| a| an| me)?(?: nearest| closest)? (.+?)(?: near me| nearby)?$", IC)
    private val CALL = Regex("^(?:call|dial|phone) (.+)$", IC)
    private val SMS = Regex("^(?:text|sms|send sms to|send a message to|send a text to) (.+?) (?:saying|that|message) (.+)$", IC)
    private val EMAIL = Regex("^(?:email|send an email to|send email to|mail) (\\S+@\\S+)(?: about| subject| saying)? ?(.*)$", IC)
    private val EVENT = Regex("^(?:add|create|new) (?:a )?(?:calendar )?(?:event|meeting|appointment)(?: called| named| for)? (.+)$", IC)
    private val ALARM = Regex("^(?:set (?:an )?alarm|wake me up|alarm)(?: for| at)? (\\d{1,2})(?:[:.](\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?)$", IC)
    private val TIMER = Regex("^(?:set (?:a )?timer|timer)(?: for)? (\\d+) ?(second|seconds|sec|minute|minutes|min|hour|hours)$", IC)
    private val SETTINGS_OPEN = Regex("^(?:open |show |go to )?(.+?) settings$", IC)
    private val OPEN_SETTINGS_PAGE = Regex("^open settings$", IC)
    private val OPEN = Regex("^(?:open|launch|start|go to|show)(?: the| my)? (.+?)(?: app| website| site)?$", IC)
    private val DOMAIN = Regex("^(?:open |go to )?((?:https?://)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?:/\\S*)?)$", IC)
    private val TRANSLATE = Regex("^translate (.+?) (?:to|into) (hindi|marathi|english|gujarati|tamil|telugu|kannada|bengali|urdu|arabic|spanish|french|german)$", IC)
    private val WEATHER = Regex("^(?:what is the |what's the |show )?weather(?: in| at| for| today| tomorrow)?\\s*(.*)$", IC)
    private val NEWS = Regex("^(?:show |latest |today's )*news(?: about| on)? ?(.*)$", IC)
    private val TODO = Regex("^(?:add|put)(?: to)?(?: my)? (?:to[- ]?do|task)(?: list)?(?: item)?:? (.+)$|^(?:add) (.+?) to (?:my )?(?:to[- ]?do|task)(?: list)?$", IC)
    private val COPY = Regex("^copy (.+)$", IC)
    private val TAP = Regex("^(?:tap|click|press) (?:on )?(.+)$", IC)
    private val TYPE = Regex("^(?:type|write|enter) (.+)$", IC)
    private val REMEMBER = Regex("^(?:remember that|remember|make a note that|note that|save note) (.+)$", IC)
    private val REMIND_IN = Regex("^remind me in (\\d+) ?(minute|minutes|min|hour|hours)(?: to)? (.+)$", IC)
    private val REMIND_AT = Regex("^remind me (today |tomorrow )?(?:at )?(\\d{1,2})(?:[:.](\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?)(?: to)? (.+)$", IC)
    private val PERCENT = Regex("^(?:what is |what's |calculate )?(\\d+(?:\\.\\d+)?) ?(?:%|percent)(?: of) (\\d+(?:\\.\\d+)?)$", IC)
    private val CONVERT = Regex("^(?:convert |what is |how many (?:are )?)?(\\d+(?:\\.\\d+)?) ?([a-z]+) (?:to|in|into) ([a-z]+)$", IC)
    private val TEMP = Regex("^(?:convert )?(-?\\d+(?:\\.\\d+)?) ?(celsius|fahrenheit) (?:to|in) (celsius|fahrenheit)$", IC)
    private val MATH = Regex("^(?:what is |what's |calculate )?([0-9.+\\-*/x() ]+|.*\\d.*(?:plus|minus|times|into|multiplied by|divided by).*\\d.*)$", IC)
    private val DICE = Regex("^(?:roll (?:a )?dice|roll a die)$", IC)
    private val COIN = Regex("^(?:flip|toss) a coin$", IC)
    private val RANDOM = Regex("^(?:pick )?(?:a )?random number(?: between (\\d+) and (\\d+))?$", IC)
    private val DAY_OF = Regex("^what day (?:is|was) tomorrow$", IC)
    private val UNTIL = Regex("^how many days (?:until|till|to) (sunday|monday|tuesday|wednesday|thursday|friday|saturday)$", IC)

    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun clockValue(hourText: String, minutes: String, meridiem: String): Pair<Int, Int>? {
        val hour = hourText.toInt()
        if (hour !in 1..12) return null
        val minute = minutes.ifEmpty { "0" }.toInt()
        if (minute > 59) return null
        return (hour % 12 + if (meridiem.lowercase().startsWith("p")) 12 else 0) to minute
    }

    private fun number(value: Double): String =
        if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString()
        else String.format(Locale.ENGLISH, "%.2f", value)

    private fun site(name: String, query: String): Hit {
        val entry = SITES.getValue(name.lowercase())
        return if (entry.url == "MAPS") Hit("maps_search", query) else Hit("url", entry.url + encode(query), entry.app.orEmpty())
    }

    fun match(text: String, now: Long = System.currentTimeMillis()): Hit? {
        val lower = text.lowercase().trim()
        SYSTEM[lower]?.let { return Hit(it) }
        DOMAIN.find(lower)?.let {
            val url = it.groupValues[1]
            return Hit("url", if (url.startsWith("http")) url else "https://$url")
        }
        COIN.find(lower)?.let { return Hit("answer", if (Random.nextBoolean()) "Heads" else "Tails") }
        DICE.find(lower)?.let { return Hit("answer", Random.nextInt(1, 7).toString()) }
        RANDOM.find(lower)?.let {
            val low = it.groupValues[1].toIntOrNull() ?: 1
            val high = it.groupValues[2].toIntOrNull() ?: 100
            return if (high >= low) Hit("answer", Random.nextInt(low, high + 1).toString()) else null
        }
        DAY_OF.find(lower)?.let {
            val day = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1) }
            return Hit("answer", WEEK[day.get(Calendar.DAY_OF_WEEK) - 1].replaceFirstChar { c -> c.uppercase() })
        }
        UNTIL.find(lower)?.let {
            val today = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) - 1
            val days = (WEEK.indexOf(it.groupValues[1]) - today + 7) % 7
            return Hit("answer", "$days days")
        }
        PERCENT.find(lower)?.let {
            return Hit("answer", number(it.groupValues[1].toDouble() * it.groupValues[2].toDouble() / 100))
        }
        TEMP.find(lower)?.let {
            val value = it.groupValues[1].toDouble()
            val out = if (it.groupValues[2] == it.groupValues[3]) value
            else if (it.groupValues[2] == "celsius") value * 9 / 5 + 32 else (value - 32) * 5 / 9
            return Hit("answer", number(out))
        }
        CONVERT.find(lower)?.let {
            val from = UNITS[it.groupValues[2]]
            val to = UNITS[it.groupValues[3]]
            if (from != null && to != null && from.first == to.first) {
                return Hit("answer", number(it.groupValues[1].toDouble() * from.second / to.second) + " " + it.groupValues[3])
            }
        }
        MATH.find(lower)?.let { hit ->
            val raw = hit.groupValues[1]
            if (raw.any { it.isDigit() } && raw.any { it in "+-*/x" || raw.contains(Regex("plus|minus|times|into|multiplied|divided")) }) {
                val value = evaluate(raw)
                if (value != null) return Hit("answer", number(value))
            }
        }
        SITE_SEARCH.find(text)?.let { return site(it.groupValues[2], it.groupValues[1]) }
        ALARM.find(text)?.let {
            val time = clockValue(it.groupValues[1], it.groupValues[2], it.groupValues[3]) ?: return null
            return Hit("alarm", time.first.toString() + ":" + time.second)
        }
        TIMER.find(text)?.let {
            val unit = it.groupValues[2].lowercase()
            val scale = if (unit.startsWith("h")) 3600 else if (unit.startsWith("m")) 60 else 1
            return Hit("timer", (it.groupValues[1].toInt() * scale).toString())
        }
        REMIND_IN.find(text)?.let {
            val scale = if (it.groupValues[2].lowercase().startsWith("h")) 3_600_000L else 60_000L
            return Hit("reminder", (now + it.groupValues[1].toLong() * scale).toString(), it.groupValues[3])
        }
        REMIND_AT.find(text)?.let {
            val time = clockValue(it.groupValues[2], it.groupValues[3], it.groupValues[4]) ?: return null
            val at = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, time.first)
                set(Calendar.MINUTE, time.second)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (it.groupValues[1].trim().lowercase() == "tomorrow" || timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }
            return Hit("reminder", at.timeInMillis.toString(), it.groupValues[5])
        }
        OPEN_SETTINGS_PAGE.find(text)?.let { return Hit("open_settings", "android.settings.SETTINGS") }
        SETTINGS_OPEN.find(text)?.let { m -> SETTINGS[m.groupValues[1].lowercase()]?.let { return Hit("open_settings", it) } }
        TRANSLATE.find(text)?.let {
            val languages = mapOf("hindi" to "hi", "marathi" to "mr", "english" to "en", "gujarati" to "gu", "tamil" to "ta", "telugu" to "te", "kannada" to "kn", "bengali" to "bn", "urdu" to "ur", "arabic" to "ar", "spanish" to "es", "french" to "fr", "german" to "de")
            return Hit("url", "https://translate.google.com/?sl=auto&tl=" + languages[it.groupValues[2].lowercase()] + "&text=" + encode(it.groupValues[1]))
        }
        NAVIGATE.find(text)?.let { return Hit("navigate", it.groupValues[1]) }
        SHOW_MAP.find(text)?.let { return Hit("maps_search", it.groupValues[1] + it.groupValues[2]) }
        CALL.find(text)?.let { return Hit("dial", it.groupValues[1]) }
        SMS.find(text)?.let { return Hit("sms", it.groupValues[1] + "|" + it.groupValues[2]) }
        EMAIL.find(text)?.let { return Hit("compose_email", it.groupValues[1] + "|" + it.groupValues[2]) }
        EVENT.find(text)?.let { return Hit("calendar", it.groupValues[1]) }
        TODO.find(text)?.let { return Hit("todo_add", it.groupValues[1] + it.groupValues[2]) }
        REMEMBER.find(text)?.let { return Hit("note", it.groupValues[1]) }
        COPY.find(text)?.let { return Hit("copy", it.groupValues[1]) }
        if (lower.endsWith(" near me") || lower.endsWith(" nearby")) {
            NEAR.find(text)?.let { return Hit("maps_search", it.groupValues[1] + " near me") }
        }
        WEATHER.find(lower)?.let { return Hit("url", "https://www.google.com/search?q=" + encode(("weather " + it.groupValues[1]).trim())) }
        NEWS.find(lower)?.let {
            val topic = it.groupValues[1]
            return Hit("url", if (topic.isBlank()) "https://news.google.com" else "https://news.google.com/search?q=" + encode(topic))
        }
        TAP.find(text)?.let { return Hit("tap_words", it.groupValues[1]) }
        TYPE.find(text)?.let { return Hit("type_words", it.groupValues[1]) }
        PLAY.find(text)?.let {
            return Hit("url", "https://www.youtube.com/results?search_query=" + encode(it.groupValues[1]), "com.google.android.youtube|play")
        }
        OPEN.find(text)?.let { match ->
            val name = match.groupValues[1].lowercase()
            CATEGORIES[name]?.let { return Hit("app_category", "android.intent.category.$it", name) }
            return Hit("open_named", match.groupValues[1])
        }
        WEB.find(text)?.let {
            val query = (it.groupValues[1] + " " + it.groupValues[2]).trim()
            if (query.isNotEmpty()) return Hit("url", "https://www.google.com/search?q=" + encode(query))
        }
        return null
    }

    /** Chhota calculator: + - * / aur brackets. */
    fun evaluate(raw: String): Double? {
        val text = raw.lowercase().replace("multiplied by", "*").replace("divided by", "/").replace("plus", "+")
            .replace("minus", "-").replace("times", "*").replace("into", "*").replace("x", "*").replace(" ", "")
        if (text.isEmpty() || text.any { it !in "0123456789.+-*/()" }) return null
        var index = 0
        fun peek() = text.getOrNull(index)
        lateinit var sum: () -> Double?
        fun atom(): Double? {
            if (peek() == '-') {
                index++
                return atom()?.let { -it }
            }
            if (peek() == '(') {
                index++
                val inner = sum()
                if (peek() != ')') return null
                index++
                return inner
            }
            val start = index
            while (peek()?.let { it.isDigit() || it == '.' } == true) index++
            return text.substring(start, index).toDoubleOrNull()
        }
        fun product(): Double? {
            var left = atom() ?: return null
            while (peek() == '*' || peek() == '/') {
                val op = text[index++]
                val right = atom() ?: return null
                if (op == '/' && right == 0.0) return null
                left = if (op == '*') left * right else left / right
            }
            return left
        }
        sum = fun(): Double? {
            var left = product() ?: return null
            while (peek() == '+' || peek() == '-') {
                val op = text[index++]
                val right = product() ?: return null
                left = if (op == '+') left + right else left - right
            }
            return left
        }
        val result = sum()
        return if (index == text.length) result else null
    }
}
