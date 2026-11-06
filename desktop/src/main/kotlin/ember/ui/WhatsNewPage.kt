package ember.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One entry of the What's new page. [fa] is the same list in Persian. */
internal class Release(val version: String, val en: List<String>, val fa: List<String>)

/** Newest first. Add the entry for a version in the build that ships it. */
internal val Releases = listOf(
    Release(
        "1.0.87",
        listOf(
            "Kill switch for OpenVPN servers: before OpenVPN starts, Ember blocks the real network except for the server's address, and lifts it when you disconnect. It stays in place if Ember closes by accident, like in whole-device mode.",
            "App rules (Apps setting) fail closed: if the VPN stops, the apps that use it lose their connection instead of leaking. A firewall kill switch cannot be used there because it would also block the apps you skipped.",
        ),
        listOf(
            "محافظ قطع اتصال برای سرورهای OpenVPN: پیش از شروع OpenVPN، Ember شبکهٔ واقعی را جز آدرس سرور مسدود می‌کند و با قطع اتصال برمی‌دارد. اگر Ember ناخواسته بسته شود، مثل حالت کل دستگاه سر جایش می‌ماند.",
            "قوانین برنامه‌ها (تنظیم برنامه‌ها) امن‌شکست‌اند: اگر VPN قطع شود، برنامه‌هایی که از آن استفاده می‌کنند به جای نشت اطلاعات اتصالشان قطع می‌شود. محافظ فایروال در آنجا ممکن نیست چون برنامه‌های ردشده را هم مسدود می‌کرد.",
        ),
    ),
    Release(
        "1.0.86",
        listOf(
            "Automatic failover: if the connected server stops passing traffic for about a minute, Ember switches to the next-fastest server by itself (up to three times) and tells you. Turn it off in Settings > Connection > Protection.",
            "After a website check, a bolt button connects to the best server that reached the site.",
        ),
        listOf(
            "تعویض خودکار سرور: اگر سرور متصل حدود یک دقیقه ترافیک عبور ندهد، Ember خودش به سریع‌ترین سرور بعدی می‌رود (تا سه بار) و به شما خبر می‌دهد. از تنظیمات ← اتصال ← محافظت خاموشش کنید.",
            "بعد از بررسی وب‌سایت، یک دکمهٔ رعد به بهترین سروری که به آن سایت رسید وصل می‌شود.",
        ),
    ),
    Release(
        "1.0.85",
        listOf(
            "Cloudflare WARP: the + menu on the Servers page can register a free WARP device and add its servers (three ports), ready to test and connect like any other. It has one exit that follows your location, and only works if your network does not block Cloudflare.",
            "WireGuard files may carry a Reserved line (the three bytes WARP needs).",
        ),
        listOf(
            "Cloudflare WARP: منوی + در صفحهٔ سرورها می‌تواند یک دستگاه رایگان WARP ثبت کند و سرورهایش (سه پورت) را اضافه کند تا مثل بقیه تست و وصل شود. یک خروجی دارد که با موقعیت شما عوض می‌شود و فقط وقتی کار می‌کند که شبکه‌تان Cloudflare را مسدود نکرده باشد.",
            "فایل‌های WireGuard می‌توانند خط Reserved داشته باشند (سه بایتی که WARP لازم دارد).",
        ),
    ),
    Release(
        "1.0.84",
        listOf(
            "Fixed the dark theme: headings and list text were black on the dark background and could not be read.",
        ),
        listOf(
            "تم تیره درست شد: عنوان‌ها و متن فهرست‌ها روی پس‌زمینهٔ تیره مشکی بودند و خوانده نمی‌شدند.",
        ),
    ),
    Release(
        "1.0.83",
        listOf(
            "Website check: the magnifier on the Servers page asks every server for a website (Spotify, YouTube, ChatGPT or any address) and marks the ones that reach it. Servers that work come first.",
            "Some sites refuse certain servers' addresses; this finds the ones that work without trying them one by one. Disconnect first.",
        ),
        listOf(
            "بررسی وب‌سایت: ذره‌بین در صفحهٔ سرورها از همهٔ سرورها یک وب‌سایت (Spotify، YouTube، ChatGPT یا هر آدرس) را می‌خواهد و سرورهایی را که می‌رسند علامت می‌زند. سرورهای سالم اول می‌آیند.",
            "بعضی سایت‌ها آدرس بعضی سرورها را رد می‌کنند؛ این ابزار سرورهای سالم را بدون امتحان یکی‌یکی پیدا می‌کند. اول اتصال را قطع کنید.",
        ),
    ),
    Release(
        "1.0.82",
        listOf(
            "Short messages (toasts) slide up from the bottom for things like \"Added 17 servers\", \"Copied\" and errors, instead of a red line on the page.",
            "Empty lists now show a floating mark, what is missing and what to do: the Servers page offers Add link, clipboard and file; Logs and Apps have their own.",
        ),
        listOf(
            "پیام‌های کوتاه از پایین پنجره بالا می‌آیند، مثل «۱۷ سرور اضافه شد»، «کپی شد» و خطاها، به جای یک خط قرمز در صفحه.",
            "فهرست‌های خالی حالا یک نشان شناور، توضیح کمبود و کار بعدی را نشان می‌دهند: صفحهٔ سرورها افزودن لینک، کلیپ‌بورد و فایل را پیشنهاد می‌دهد؛ گزارش‌ها و برنامه‌ها هم نمای خودشان را دارند.",
        ),
    ),
    Release(
        "1.0.81",
        listOf(
            "Dark theme polish (Settings > Appearance > Dark, or System): warm surface tones for dialogs, menus, switches and text fields in both themes, and the colours glide when you change theme.",
        ),
        listOf(
            "بهبود تم تیره (تنظیمات > ظاهر > تیره، یا سیستم): رنگ‌های گرم برای پنجره‌های گفت‌وگو، منوها، کلیدها و کادرهای متن در هر دو تم، و رنگ‌ها هنگام تغییر تم نرم عوض می‌شوند.",
        ),
    ),
    Release(
        "1.0.80",
        listOf(
            "Servers page motion: cards fold and unfold smoothly, and the servers fade in one after another.",
            "Each tested server has a thin bar showing its speed next to the fastest; after a test the rows slide into their new order.",
            "A small spinner shows while a test runs, and the star bounces when you favourite a server.",
        ),
        listOf(
            "حرکت‌های صفحهٔ سرورها: کارت‌ها نرم باز و بسته می‌شوند و سرورها یکی‌یکی ظاهر می‌شوند.",
            "هر سرور تست‌شده یک نوار باریک دارد که سرعتش را نسبت به سریع‌ترین نشان می‌دهد؛ بعد از تست، ردیف‌ها به ترتیب تازه می‌لغزند.",
            "هنگام تست یک چرخانندهٔ کوچک دیده می‌شود و ستاره با علاقه‌مندی کردن سرور می‌جهد.",
        ),
    ),
    Release(
        "1.0.79",
        listOf(
            "Home screen motion: the Connect button runs a small arc while connecting, breathes slowly once connected, and sends out one ripple when the connection comes up.",
            "A check mark pops in beside \"Connected\"; the colours glide between states; the flag slides when you change server.",
            "Speed numbers glide between readings, with a line of the last minute of download speed below them.",
        ),
        listOf(
            "حرکت‌های صفحهٔ خانه: دکمهٔ اتصال هنگام اتصال یک کمان کوچک می‌چرخاند، پس از اتصال آرام نفس می‌کشد و لحظهٔ برقراری اتصال یک موج از خودش می‌فرستد.",
            "کنار «متصل» یک تیک ظاهر می‌شود؛ رنگ‌ها نرم عوض می‌شوند؛ با تغییر سرور، پرچم می‌لغزد.",
            "عددهای سرعت نرم تغییر می‌کنند و زیر آن‌ها خطی از یک دقیقهٔ اخیر سرعت دانلود دیده می‌شود.",
        ),
    ),
    Release(
        "1.0.78",
        listOf(
            "The installer now sets up Ember's network helpers for you (a checkbox, on by default), so connecting, app rules and OpenVPN never show a Windows permission prompt.",
        ),
        listOf(
            "نصب‌کننده حالا ابزارهای شبکهٔ Ember را برایتان آماده می‌کند (یک گزینه، به‌صورت پیش‌فرض روشن)، پس اتصال، قوانین برنامه‌ها و OpenVPN دیگر پیام اجازهٔ Windows نشان نمی‌دهند.",
        ),
    ),
    Release(
        "1.0.77",
        listOf(
            "App rules and OpenVPN no longer open a black console window when they start.",
            "Subscription cards: test, refresh, edit and remove sit next to the arrow. The add buttons are a plain + in a circle.",
            "After updating, the first app-rules or OpenVPN connection asks Windows for permission once more.",
        ),
        listOf(
            "قوانین برنامه‌ها و OpenVPN دیگر پنجرهٔ سیاه کنسول را باز نمی‌کنند.",
            "کارت اشتراک: دکمه‌های تست، به‌روزرسانی، ویرایش و حذف کنار فلش هستند. دکمه‌های افزودن یک + ساده داخل دایره‌اند.",
            "بعد از به‌روزرسانی، اولین اتصال با قوانین برنامه‌ها یا OpenVPN یک بار دیگر از Windows اجازه می‌گیرد.",
        ),
    ),
    Release(
        "1.0.76",
        listOf(
            "New Apps setting (Settings → Connection): turn the VPN off for programs you pick, such as Firefox, or use it only for programs you pick. Experimental.",
            "Add a program from the list of running ones or browse for its .exe. Needs whole-device mode and one Windows prompt the first time; the kill switch does not cover this mode.",
        ),
        listOf(
            "تنظیم تازهٔ برنامه‌ها (تنظیمات ← اتصال): VPN را برای برنامه‌هایی که انتخاب می‌کنید، مثل Firefox، خاموش کنید یا فقط برای آن‌ها روشن بماند. آزمایشی.",
            "برنامه را از فهرست برنامه‌های در حال اجرا اضافه کنید یا فایل exe آن را انتخاب کنید. به حالت کل دستگاه و یک بار پیام اجازهٔ Windows نیاز دارد؛ محافظ قطع اتصال این حالت را پوشش نمی‌دهد.",
        ),
    ),
    Release(
        "1.0.75",
        listOf(
            "Servers page: one + button with a menu (add a link, add from the clipboard, add from a file).",
            "Each subscription card starts folded; its notes and buttons (test, refresh, edit, remove) sit behind the arrow.",
        ),
        listOf(
            "صفحهٔ سرورها: یک دکمهٔ + با منو (افزودن لینک، افزودن از کلیپ‌بورد، افزودن از فایل).",
            "هر کارت اشتراک بسته شروع می‌شود؛ یادداشت‌ها و دکمه‌ها (تست، به‌روزرسانی، ویرایش، حذف) پشت فلش هستند.",
        ),
    ),
    Release(
        "1.0.74",
        listOf(
            "OpenVPN servers: add a .ovpn file and connect with the OpenVPN you installed. Certificates next to the file are picked up, and Ember asks for a user name and password when the profile needs them.",
            "The first OpenVPN connection asks Windows for permission once. OpenVPN sets up its own routes, so the kill switch does not cover these servers.",
            "Servers added from JSON now keep their names after a restart.",
        ),
        listOf(
            "سرورهای OpenVPN: فایل ovpn را اضافه کنید و با OpenVPN نصب‌شدهٔ خودتان وصل شوید. گواهی‌های کنار فایل خوانده می‌شوند و اگر پروفایل نام کاربری و رمز بخواهد، Ember می‌پرسد.",
            "اولین اتصال OpenVPN یک بار از Windows اجازه می‌گیرد. OpenVPN مسیرهای خودش را می‌سازد، پس محافظ قطع اتصال این سرورها را پوشش نمی‌دهد.",
            "سرورهایی که از JSON اضافه می‌شوند بعد از راه‌اندازی دوباره نامشان را حفظ می‌کنند.",
        ),
    ),
    Release(
        "1.0.73",
        listOf(
            "Settings is a short list of groups (Interface, Connection, More); each row opens its own page.",
            "New What's new and About pages, bottom left. About shows the versions and how to reach the developer.",
            "Add servers from a file: Xray JSON, a single Xray outbound, or a WireGuard .conf.",
            "OpenVPN files are recognised, with a clear message that Ember cannot run them yet.",
        ),
        listOf(
            "تنظیمات به چند گروه کوتاه (ظاهر و رفتار، اتصال، بیشتر) تقسیم شد و هر ردیف صفحهٔ خودش را باز می‌کند.",
            "صفحه‌های تازه‌ها و درباره، پایین سمت چپ. درباره نسخه‌ها و راه ارتباط با سازنده را نشان می‌دهد.",
            "افزودن سرور از فایل: Xray JSON، یک outbound تکی Xray یا فایل WireGuard با پسوند conf.",
            "فایل‌های OpenVPN شناخته می‌شوند و پیام روشنی می‌گیرید که Ember هنوز نمی‌تواند آن‌ها را اجرا کند.",
        ),
    ),
    Release(
        "1.0.72",
        listOf(
            "Connecting no longer shows the PowerShell pop-up. The fast network helper gave the tunnel a path it could not open, so Ember fell back to the slow helper.",
            "Skip the VPN works for website names now: a listed hostname goes straight out. Only the exact name is skipped.",
            "The helper checks that the tunnel is still running after its adapter appears.",
        ),
        listOf(
            "اتصال دیگر پنجرهٔ PowerShell را نشان نمی‌دهد. ابزار سریع شبکه مسیری به تونل می‌داد که باز نمی‌شد و Ember به ابزار کند برمی‌گشت.",
            "«عبور بدون VPN» حالا برای نام وب‌سایت‌ها کار می‌کند: نام واردشده مستقیم می‌رود. فقط همان نام دقیق رد می‌شود.",
            "ابزار شبکه بعد از ظاهر شدن رابط، بررسی می‌کند که تونل هنوز اجرا باشد.",
        ),
    ),
    Release(
        "1.0.58 – 1.0.71",
        listOf(
            "Tray menu with Connect, Disconnect and a list of servers.",
            "Favourite servers, flags, and icon buttons with tooltips.",
            "Add from the clipboard; Hysteria2 links and JSON subscriptions.",
            "IPv6 through the tunnel, and protection that resumes after a crash or restart.",
            "A speed test you can stop, and you can connect while one runs.",
            "Faster connecting and a native network helper.",
        ),
        listOf(
            "منوی نوار وظیفه با اتصال، قطع اتصال و فهرست سرورها.",
            "سرورهای مورد علاقه، پرچم‌ها و دکمه‌های آیکونی با راهنما.",
            "افزودن از کلیپ‌بورد؛ لینک‌های Hysteria2 و اشتراک JSON.",
            "IPv6 از داخل تونل و ادامهٔ محافظت پس از خرابی یا راه‌اندازی دوباره.",
            "تست سرعتی که می‌توان متوقفش کرد و اتصال همزمان با آن.",
            "اتصال سریع‌تر و ابزار شبکهٔ بومی.",
        ),
    ),
    Release(
        "1.0.0 – 1.0.57",
        listOf(
            "Ember became a subscription-link client: paste a link, see the servers, test and sort them by speed.",
            "Connects through Xray-core for one app or the whole device, with a kill switch.",
        ),
        listOf(
            "Ember به کلاینت لینک اشتراک تبدیل شد: لینک را بچسبانید، سرورها را ببینید، تست و بر اساس سرعت مرتب کنید.",
            "اتصال با Xray-core برای یک برنامه یا کل دستگاه، همراه با محافظ قطع اتصال.",
        ),
    ),
)

@Composable
internal fun WhatsNewPage(language: String) {
    val strings = LocalStrings.current
    val running = System.getProperty("jpackage.app-version")
    Text(strings.whatsNew, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(8.dp))
    Text(strings.whatsNewIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    for (release in Releases) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(release.version, style = MaterialTheme.typography.titleMedium)
                    if (release.version == running) {
                        Spacer(Modifier.width(8.dp))
                        Text(strings.currentVersion, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                for (line in if (language == "fa") release.fa else release.en) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("•", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
