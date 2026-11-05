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
