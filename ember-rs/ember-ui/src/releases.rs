//! The release history shown on the What's new page, ported from WhatsNewPage.kt (newest first; English and Persian
//! lists of the same length). The entries from 1.0.101 down were generated from the Kotlin file; the Rust build adds its
//! own at the top.

pub struct Release {
    pub version: &'static str,
    pub en: &'static [&'static str],
    pub fa: &'static [&'static str],
}

pub const RELEASES: &[Release] = &[
    Release {
        version: "2.0.2",
        en: &[
            "KitKat brings a chocolate backdrop and playful connection lines, including “Getting your break ready…” and “Take a break.”",
            "Switching pages now has a short, steady fade-and-rise transition.",
        ],
        fa: &[
            "تم کیت‌کت پس‌زمینهٔ شکلاتی و پیام‌های صمیمی برای اتصال دارد: «داریم اتصال را برای استراحت آماده می‌کنیم…» و «وقت استراحت است.»",
            "تعویض صفحه‌ها با محوشدن و بالا آمدن کوتاه و نرمی همراه است.",
        ],
    },
    Release {
        version: "2.0.1",
        en: &["Add a server from a QR-code picture (PNG, JPEG, WebP or BMP). The picture is decoded locally."],
        fa: &["اکنون می‌توانید سرور را از تصویر کد QR (PNG، JPEG، WebP یا BMP) اضافه کنید؛ تصویر فقط روی دستگاه شما خوانده می‌شود."],
    },
    Release {
        version: "2.0.0",
        en: &[
            "Ember is rebuilt as a native Windows app: it opens at once and sits at about 30 MB of memory when idle (about 23 MB in the tray).",
            "A new look: choose your chocolate flavour: Strawberry or Dark Choco (Settings, Chocolate flavour), a smoother glass orb that springs when you connect and reacts to your pointer, and lists that glide in.",
            "The window has its own title bar, rounded corners and a soft shadow; every page is laid out again for narrow windows and for Persian, which now uses the Vazirmatn font.",
            "Servers: test only the delay (quick, almost no data) or the download speed as well, add servers from a file, add Cloudflare WARP, show a server as a QR code for your phone, and see which servers reach a website you care about.",
            "Home shows the country that websites really see once you are connected, and the speed test no longer sits on the main page.",
            "Settings: the MTU box, and Backup and restore.",
            "A third chocolate flavour: Mint, with a mint background and a green glass orb (Settings, Chocolate flavour).",
            "A soft chime when Ember connects and a lower one when it disconnects (Settings, Startup and tray; you can turn it off).",
            "Installing is tidier: one Ember program with everything else in a resources folder.",
        ],
        fa: &[
            "Ember از نو به‌صورت یک برنامهٔ بومی ویندوز ساخته شد: بی‌درنگ باز می‌شود و در حالت آماده حدود ۳۰ مگابایت حافظه می‌گیرد (در سینی حدود ۲۳ مگابایت).",
            "ظاهر تازه: طعم شکلات خود را انتخاب کنید: «توت‌فرنگی» یا «شکلات تلخ» (تنظیمات، طعم شکلات)، گوی شیشه‌ای نرم‌تر که هنگام اتصال فنری می‌جهد و به اشاره‌گر شما واکنش نشان می‌دهد، و فهرست‌هایی که نرم وارد می‌شوند.",
            "پنجره نوار عنوان خودش، گوشه‌های گرد و سایهٔ ملایم دارد؛ همهٔ صفحه‌ها برای پنجرهٔ باریک و برای فارسی دوباره چیده شد و فارسی حالا از قلم وزیرمتن استفاده می‌کند.",
            "سرورها: فقط تأخیر را بسنجید (سریع و تقریباً بدون مصرف داده) یا سرعت دانلود را هم، سرور را از فایل اضافه کنید، Cloudflare WARP را اضافه کنید، سرور را برای گوشی خود با کد QR نشان دهید، و ببینید کدام سرورها به وب‌سایت موردنظرتان می‌رسند.",
            "صفحهٔ خانه کشوری را نشان می‌دهد که وب‌سایت‌ها پس از اتصال واقعاً می‌بینند، و آزمون سرعت دیگر روی صفحهٔ اصلی نیست.",
            "تنظیمات: کادر MTU و پشتیبان‌گیری و بازیابی.",
            "طعم سوم شکلات: «نعنا» با پس‌زمینهٔ نعنایی و گوی شیشه‌ای سبز (تنظیمات، طعم شکلات).",
            "هنگام اتصال یک زنگ ملایم و هنگام قطع یک زنگ پایین‌تر پخش می‌شود (تنظیمات، اجرا و کنار ساعت؛ می‌توانید خاموشش کنید).",
            "نصب مرتب‌تر شد: یک برنامهٔ Ember و بقیهٔ فایل‌ها در پوشهٔ resources.",
        ],
    },
    Release {
        version: "1.0.101",
        en: &[
            "Logs now has Copy report: version, this computer and the recent log in one paste for asking for help. Server links, account ids and IP addresses are removed first.",
        ],
        fa: &[
            "در بخش گزارش‌ها دکمهٔ «کپی گزارش» اضافه شد: نسخه، مشخصات رایانه و گزارش اخیر در یک بار چسباندن. لینک سرورها، شناسهٔ حساب و نشانی‌های IP از آن حذف می‌شود.",
        ],
    },
    Release {
        version: "1.0.100",
        en: &[
            "Backup and restore (Settings, More): save your subscriptions and settings to one file and put them back on this or another computer. The file contains your account keys, so keep it private.",
        ],
        fa: &[
            "پشتیبان‌گیری و بازیابی (تنظیمات، بیشتر): اشتراک‌ها و تنظیمات را در یک فایل ذخیره کنید و روی همین رایانه یا رایانهٔ دیگر برگردانید. این فایل کلیدهای حساب شما را دارد، آن را خصوصی نگه دارید.",
        ],
    },
    Release {
        version: "1.0.99",
        en: &[
            "Much lighter: the animated background and the orb stop completely while the window is minimized or in the tray (0.3% of a core instead of a constant load), and redraw less often while the window is open.",
            "Ember's memory is capped (it had grown past 1 GB after hours); it now stays near 600 MB or less.",
        ],
        fa: &[
            "بسیار سبک‌تر: پس‌زمینه و گوی متحرک وقتی پنجره کوچک شده یا در سینی است کاملاً می‌ایستند، و در پنجرهٔ باز هم کمتر بازرسم می‌شوند.",
            "حافظهٔ امبر محدود شد (پس از ساعت‌ها از ۱ گیگابایت می‌گذشت) و حالا حدود ۶۰۰ مگابایت یا کمتر می‌ماند.",
        ],
    },
    Release {
        version: "1.0.98",
        en: &[
            "Fixes a blank window on some Windows PCs: Ember now draws with OpenGL, which works where the default graphics path showed nothing.",
            "Xray gets 30 seconds instead of 10 to start before Ember gives up, and the error now says when Xray wrote nothing (often an antivirus scan).",
        ],
        fa: &[
            "رفع پنجرهٔ خالی در برخی رایانه‌های ویندوزی: امبر حالا با OpenGL رسم می‌کند.",
            "Xray برای شروع ۳۰ ثانیه فرصت دارد (به‌جای ۱۰)، و پیام خطا مشخص می‌کند که Xray چیزی ننوشته است (اغلب اسکن آنتی‌ویروس).",
        ],
    },
    Release {
        version: "1.0.97",
        en: &[
            "The orb's animation now loops with no seam: the second ring's dots and the arc that runs round it while connecting no longer jump every so often.",
        ],
        fa: &[
            "انیمیشن گوی حالا بدون هیچ درزی تکرار می‌شود: نقطه‌های حلقهٔ دوم و کمانی که هنگام اتصال دورش می‌چرخد دیگر هر از چندگاهی نمی‌پرند.",
        ],
    },
    Release {
        version: "1.0.96",
        en: &[
            "Easier to read: secondary text, the orange links and the star icons are brighter or darker as needed, and the dark theme's panels and background are deeper so text never sits on a bright patch.",
        ],
        fa: &[
            "خواندن آسان‌تر: متن‌های فرعی، پیوندهای نارنجی و ستاره‌ها روشن‌تر یا تیره‌تر شدند و پنل‌ها و پس‌زمینهٔ تم تاریک عمیق‌تر است تا متن هیچ‌گاه روی یک لکهٔ روشن نباشد.",
        ],
    },
    Release {
        version: "1.0.95",
        en: &[
            "The orb is now drawn live and animated: it warms up with a running arc while connecting, ripples when the connection comes up, and drains its colour when you disconnect. The pale halo around it is gone.",
        ],
        fa: &[
            "گوی حالا به‌صورت زنده کشیده و متحرک می‌شود: هنگام اتصال گرم می‌شود و کمانی دورش می‌چرخد، وقتی وصل شد موجی از آن بیرون می‌رود و هنگام قطع، رنگش محو می‌شود. هالهٔ کم‌رنگ دور آن حذف شد.",
        ],
    },
    Release {
        version: "1.0.94",
        en: &[
            "Hovering over the menu and the server list now fades in and out smoothly, with no grey patches when the mouse moves fast.",
            "The side menu now shares the window's background instead of being a separate panel, and the colours behind the glass drift slowly.",
            "A cleaner Ember icon, and the real Telegram icon on the About page.",
        ],
        fa: &[
            "حالا نشانگر روی منو و فهرست سرورها نرم ظاهر و محو می‌شود و با حرکت سریع ماوس لکه‌ٔ خاکستری باقی نمی‌ماند.",
            "منوی کناری حالا همان پس‌زمینهٔ پنجره را دارد و جدا نیست، و رنگ‌های پشت شیشه به‌آرامی حرکت می‌کنند.",
            "نماد ساده‌تر Ember و نماد واقعی تلگرام در صفحهٔ درباره.",
        ],
    },
    Release {
        version: "1.0.93",
        en: &[
            "The Connect button is now a glass orb that dims when idle and glows when connected, and the window has a soft picture behind it.",
            "The Android app has the same look, a QR code for sharing a server, and a new home-screen widget.",
        ],
        fa: &[
            "دکمهٔ اتصال حالا یک گوی شیشه‌ای است که در حالت آماده کم‌نور و هنگام اتصال درخشان می‌شود، و پشت پنجره یک تصویر نرم قرار دارد.",
            "برنامهٔ اندروید همین ظاهر را دارد، به‌علاوهٔ کد QR برای اشتراک‌گذاری سرور و یک ویجت تازهٔ صفحهٔ اصلی.",
        ],
    },
    Release {
        version: "1.0.92",
        en: &[
            "A new frosted-glass look: translucent panels and a soft coloured backdrop, in light and dark.",
        ],
        fa: &[
            "ظاهر شیشه‌ای تازه: پنل‌های نیمه‌شفاف و پس‌زمینهٔ رنگی نرم، در حالت روشن و تاریک.",
        ],
    },
    Release {
        version: "1.0.91",
        en: &[
            "The window's rounded corners are smooth now: the hard cut-out that made them look jagged is gone.",
            "The Android app is new: it opens the same subscription links, files, QR codes and Cloudflare WARP as this app (see Ember-2.0.0.apk).",
        ],
        fa: &[
            "گوشه‌های گردِ پنجره حالا نرم هستند: برش سخت که آن‌ها را دندانه‌دار نشان می‌داد حذف شد.",
            "برنامهٔ اندروید تازه است: همان لینک‌های اشتراک، فایل‌ها، کدهای QR و Cloudflare WARP این برنامه را باز می‌کند (Ember-2.0.0.apk).",
        ],
    },
    Release {
        version: "1.0.90",
        en: &[
            "WARP through one of your servers: for networks that block Cloudflare, the + menu can add WARP carried inside a connection to a server you pick. Your traffic leaves from Cloudflare. The server must carry UDP.",
        ],
        fa: &[
            "WARP از طریق یکی از سرورهای شما: برای شبکه‌هایی که Cloudflare را مسدود می‌کنند، منوی + می‌تواند WARP را درون اتصال به سروری که انتخاب می‌کنید اضافه کند. ترافیک شما از Cloudflare خارج می‌شود. سرور باید UDP را عبور دهد.",
        ],
    },
    Release {
        version: "1.0.89",
        en: &[
            "Fixed a wrong error (\"The coroutine scope left the composition\") that appeared when you left the Servers page while servers were being added or refreshed. The work now finishes in the background.",
        ],
        fa: &[
            "خطای نادرست («The coroutine scope left the composition») که با ترک صفحهٔ سرورها هنگام افزودن یا به‌روزرسانی سرورها دیده می‌شد برطرف شد. کار حالا در پس‌زمینه تمام می‌شود.",
        ],
    },
    Release {
        version: "1.0.88",
        en: &[
            "QR codes: point at a server on the Servers page and press the QR button to show its link as a code your phone can scan.",
            "You can also add servers from a picture of a QR code (for example a screenshot): use the file option in the + menu.",
        ],
        fa: &[
            "کد QR: روی یک سرور در صفحهٔ سرورها بروید و دکمهٔ QR را بزنید تا لینکش به‌صورت کدی که گوشی اسکن می‌کند نشان داده شود.",
            "همچنین می‌توانید از روی تصویر یک کد QR (مثلاً اسکرین‌شات) سرور اضافه کنید: گزینهٔ فایل در منوی + را بزنید.",
        ],
    },
    Release {
        version: "1.0.87",
        en: &[
            "Kill switch for OpenVPN servers: before OpenVPN starts, Ember blocks the real network except for the server's address, and lifts it when you disconnect. It stays in place if Ember closes by accident, like in whole-device mode.",
            "App rules (Apps setting) fail closed: if the VPN stops, the apps that use it lose their connection instead of leaking. A firewall kill switch cannot be used there because it would also block the apps you skipped.",
        ],
        fa: &[
            "محافظ قطع اتصال برای سرورهای OpenVPN: پیش از شروع OpenVPN، Ember شبکهٔ واقعی را جز آدرس سرور مسدود می‌کند و با قطع اتصال برمی‌دارد. اگر Ember ناخواسته بسته شود، مثل حالت کل دستگاه سر جایش می‌ماند.",
            "قوانین برنامه‌ها (تنظیم برنامه‌ها) امن‌شکست‌اند: اگر VPN قطع شود، برنامه‌هایی که از آن استفاده می‌کنند به جای نشت اطلاعات اتصالشان قطع می‌شود. محافظ فایروال در آنجا ممکن نیست چون برنامه‌های ردشده را هم مسدود می‌کرد.",
        ],
    },
    Release {
        version: "1.0.86",
        en: &[
            "Automatic failover: if the connected server stops passing traffic for about a minute, Ember switches to the next-fastest server by itself (up to three times) and tells you. Turn it off in Settings > Connection > Protection.",
            "After a website check, a bolt button connects to the best server that reached the site.",
        ],
        fa: &[
            "تعویض خودکار سرور: اگر سرور متصل حدود یک دقیقه ترافیک عبور ندهد، Ember خودش به سریع‌ترین سرور بعدی می‌رود (تا سه بار) و به شما خبر می‌دهد. از تنظیمات ← اتصال ← محافظت خاموشش کنید.",
            "بعد از بررسی وب‌سایت، یک دکمهٔ رعد به بهترین سروری که به آن سایت رسید وصل می‌شود.",
        ],
    },
    Release {
        version: "1.0.85",
        en: &[
            "Cloudflare WARP: the + menu on the Servers page can register a free WARP device and add its servers (three ports), ready to test and connect like any other. It has one exit that follows your location, and only works if your network does not block Cloudflare.",
            "WireGuard files may carry a Reserved line (the three bytes WARP needs).",
        ],
        fa: &[
            "Cloudflare WARP: منوی + در صفحهٔ سرورها می‌تواند یک دستگاه رایگان WARP ثبت کند و سرورهایش (سه پورت) را اضافه کند تا مثل بقیه تست و وصل شود. یک خروجی دارد که با موقعیت شما عوض می‌شود و فقط وقتی کار می‌کند که شبکه‌تان Cloudflare را مسدود نکرده باشد.",
            "فایل‌های WireGuard می‌توانند خط Reserved داشته باشند (سه بایتی که WARP لازم دارد).",
        ],
    },
    Release {
        version: "1.0.84",
        en: &[
            "Fixed the dark theme: headings and list text were black on the dark background and could not be read.",
        ],
        fa: &[
            "تم تیره درست شد: عنوان‌ها و متن فهرست‌ها روی پس‌زمینهٔ تیره مشکی بودند و خوانده نمی‌شدند.",
        ],
    },
    Release {
        version: "1.0.83",
        en: &[
            "Website check: the magnifier on the Servers page asks every server for a website (Spotify, YouTube, ChatGPT or any address) and marks the ones that reach it. Servers that work come first.",
            "Some sites refuse certain servers' addresses; this finds the ones that work without trying them one by one. Disconnect first.",
        ],
        fa: &[
            "بررسی وب‌سایت: ذره‌بین در صفحهٔ سرورها از همهٔ سرورها یک وب‌سایت (Spotify، YouTube، ChatGPT یا هر آدرس) را می‌خواهد و سرورهایی را که می‌رسند علامت می‌زند. سرورهای سالم اول می‌آیند.",
            "بعضی سایت‌ها آدرس بعضی سرورها را رد می‌کنند؛ این ابزار سرورهای سالم را بدون امتحان یکی‌یکی پیدا می‌کند. اول اتصال را قطع کنید.",
        ],
    },
    Release {
        version: "1.0.82",
        en: &[
            "Short messages (toasts) slide up from the bottom for things like \"Added 17 servers\", \"Copied\" and errors, instead of a red line on the page.",
            "Empty lists now show a floating mark, what is missing and what to do: the Servers page offers Add link, clipboard and file; Logs and Apps have their own.",
        ],
        fa: &[
            "پیام‌های کوتاه از پایین پنجره بالا می‌آیند، مثل «۱۷ سرور اضافه شد»، «کپی شد» و خطاها، به جای یک خط قرمز در صفحه.",
            "فهرست‌های خالی حالا یک نشان شناور، توضیح کمبود و کار بعدی را نشان می‌دهند: صفحهٔ سرورها افزودن لینک، کلیپ‌بورد و فایل را پیشنهاد می‌دهد؛ گزارش‌ها و برنامه‌ها هم نمای خودشان را دارند.",
        ],
    },
    Release {
        version: "1.0.81",
        en: &[
            "Dark theme polish (Settings > Appearance > Dark, or System): warm surface tones for dialogs, menus, switches and text fields in both themes, and the colours glide when you change theme.",
        ],
        fa: &[
            "بهبود تم تیره (تنظیمات > ظاهر > تیره، یا سیستم): رنگ‌های گرم برای پنجره‌های گفت‌وگو، منوها، کلیدها و کادرهای متن در هر دو تم، و رنگ‌ها هنگام تغییر تم نرم عوض می‌شوند.",
        ],
    },
    Release {
        version: "1.0.80",
        en: &[
            "Servers page motion: cards fold and unfold smoothly, and the servers fade in one after another.",
            "Each tested server has a thin bar showing its speed next to the fastest; after a test the rows slide into their new order.",
            "A small spinner shows while a test runs, and the star bounces when you favourite a server.",
        ],
        fa: &[
            "حرکت‌های صفحهٔ سرورها: کارت‌ها نرم باز و بسته می‌شوند و سرورها یکی‌یکی ظاهر می‌شوند.",
            "هر سرور تست‌شده یک نوار باریک دارد که سرعتش را نسبت به سریع‌ترین نشان می‌دهد؛ بعد از تست، ردیف‌ها به ترتیب تازه می‌لغزند.",
            "هنگام تست یک چرخانندهٔ کوچک دیده می‌شود و ستاره با علاقه‌مندی کردن سرور می‌جهد.",
        ],
    },
    Release {
        version: "1.0.79",
        en: &[
            "Home screen motion: the Connect button runs a small arc while connecting, breathes slowly once connected, and sends out one ripple when the connection comes up.",
            "A check mark pops in beside \"Connected\"; the colours glide between states; the flag slides when you change server.",
            "Speed numbers glide between readings, with a line of the last minute of download speed below them.",
        ],
        fa: &[
            "حرکت‌های صفحهٔ خانه: دکمهٔ اتصال هنگام اتصال یک کمان کوچک می‌چرخاند، پس از اتصال آرام نفس می‌کشد و لحظهٔ برقراری اتصال یک موج از خودش می‌فرستد.",
            "کنار «متصل» یک تیک ظاهر می‌شود؛ رنگ‌ها نرم عوض می‌شوند؛ با تغییر سرور، پرچم می‌لغزد.",
            "عددهای سرعت نرم تغییر می‌کنند و زیر آن‌ها خطی از یک دقیقهٔ اخیر سرعت دانلود دیده می‌شود.",
        ],
    },
    Release {
        version: "1.0.78",
        en: &[
            "The installer now sets up Ember's network helpers for you (a checkbox, on by default), so connecting, app rules and OpenVPN never show a Windows permission prompt.",
        ],
        fa: &[
            "نصب‌کننده حالا ابزارهای شبکهٔ Ember را برایتان آماده می‌کند (یک گزینه، به‌صورت پیش‌فرض روشن)، پس اتصال، قوانین برنامه‌ها و OpenVPN دیگر پیام اجازهٔ Windows نشان نمی‌دهند.",
        ],
    },
    Release {
        version: "1.0.77",
        en: &[
            "App rules and OpenVPN no longer open a black console window when they start.",
            "Subscription cards: test, refresh, edit and remove sit next to the arrow. The add buttons are a plain + in a circle.",
            "After updating, the first app-rules or OpenVPN connection asks Windows for permission once more.",
        ],
        fa: &[
            "قوانین برنامه‌ها و OpenVPN دیگر پنجرهٔ سیاه کنسول را باز نمی‌کنند.",
            "کارت اشتراک: دکمه‌های تست، به‌روزرسانی، ویرایش و حذف کنار فلش هستند. دکمه‌های افزودن یک + ساده داخل دایره‌اند.",
            "بعد از به‌روزرسانی، اولین اتصال با قوانین برنامه‌ها یا OpenVPN یک بار دیگر از Windows اجازه می‌گیرد.",
        ],
    },
    Release {
        version: "1.0.76",
        en: &[
            "New Apps setting (Settings → Connection): turn the VPN off for programs you pick, such as Firefox, or use it only for programs you pick. Experimental.",
            "Add a program from the list of running ones or browse for its .exe. Needs whole-device mode and one Windows prompt the first time; the kill switch does not cover this mode.",
        ],
        fa: &[
            "تنظیم تازهٔ برنامه‌ها (تنظیمات ← اتصال): VPN را برای برنامه‌هایی که انتخاب می‌کنید، مثل Firefox، خاموش کنید یا فقط برای آن‌ها روشن بماند. آزمایشی.",
            "برنامه را از فهرست برنامه‌های در حال اجرا اضافه کنید یا فایل exe آن را انتخاب کنید. به حالت کل دستگاه و یک بار پیام اجازهٔ Windows نیاز دارد؛ محافظ قطع اتصال این حالت را پوشش نمی‌دهد.",
        ],
    },
    Release {
        version: "1.0.75",
        en: &[
            "Servers page: one + button with a menu (add a link, add from the clipboard, add from a file).",
            "Each subscription card starts folded; its notes and buttons (test, refresh, edit, remove) sit behind the arrow.",
        ],
        fa: &[
            "صفحهٔ سرورها: یک دکمهٔ + با منو (افزودن لینک، افزودن از کلیپ‌بورد، افزودن از فایل).",
            "هر کارت اشتراک بسته شروع می‌شود؛ یادداشت‌ها و دکمه‌ها (تست، به‌روزرسانی، ویرایش، حذف) پشت فلش هستند.",
        ],
    },
    Release {
        version: "1.0.74",
        en: &[
            "OpenVPN servers: add a .ovpn file and connect with the OpenVPN you installed. Certificates next to the file are picked up, and Ember asks for a user name and password when the profile needs them.",
            "The first OpenVPN connection asks Windows for permission once. OpenVPN sets up its own routes, so the kill switch does not cover these servers.",
            "Servers added from JSON now keep their names after a restart.",
        ],
        fa: &[
            "سرورهای OpenVPN: فایل ovpn را اضافه کنید و با OpenVPN نصب‌شدهٔ خودتان وصل شوید. گواهی‌های کنار فایل خوانده می‌شوند و اگر پروفایل نام کاربری و رمز بخواهد، Ember می‌پرسد.",
            "اولین اتصال OpenVPN یک بار از Windows اجازه می‌گیرد. OpenVPN مسیرهای خودش را می‌سازد، پس محافظ قطع اتصال این سرورها را پوشش نمی‌دهد.",
            "سرورهایی که از JSON اضافه می‌شوند بعد از راه‌اندازی دوباره نامشان را حفظ می‌کنند.",
        ],
    },
    Release {
        version: "1.0.73",
        en: &[
            "Settings is a short list of groups (Interface, Connection, More); each row opens its own page.",
            "New What's new and About pages, bottom left. About shows the versions and how to reach the developer.",
            "Add servers from a file: Xray JSON, a single Xray outbound, or a WireGuard .conf.",
            "OpenVPN files are recognised, with a clear message that Ember cannot run them yet.",
        ],
        fa: &[
            "تنظیمات به چند گروه کوتاه (ظاهر و رفتار، اتصال، بیشتر) تقسیم شد و هر ردیف صفحهٔ خودش را باز می‌کند.",
            "صفحه‌های تازه‌ها و درباره، پایین سمت چپ. درباره نسخه‌ها و راه ارتباط با سازنده را نشان می‌دهد.",
            "افزودن سرور از فایل: Xray JSON، یک outbound تکی Xray یا فایل WireGuard با پسوند conf.",
            "فایل‌های OpenVPN شناخته می‌شوند و پیام روشنی می‌گیرید که Ember هنوز نمی‌تواند آن‌ها را اجرا کند.",
        ],
    },
    Release {
        version: "1.0.72",
        en: &[
            "Connecting no longer shows the PowerShell pop-up. The fast network helper gave the tunnel a path it could not open, so Ember fell back to the slow helper.",
            "Skip the VPN works for website names now: a listed hostname goes straight out. Only the exact name is skipped.",
            "The helper checks that the tunnel is still running after its adapter appears.",
        ],
        fa: &[
            "اتصال دیگر پنجرهٔ PowerShell را نشان نمی‌دهد. ابزار سریع شبکه مسیری به تونل می‌داد که باز نمی‌شد و Ember به ابزار کند برمی‌گشت.",
            "«عبور بدون VPN» حالا برای نام وب‌سایت‌ها کار می‌کند: نام واردشده مستقیم می‌رود. فقط همان نام دقیق رد می‌شود.",
            "ابزار شبکه بعد از ظاهر شدن رابط، بررسی می‌کند که تونل هنوز اجرا باشد.",
        ],
    },
    Release {
        version: "1.0.58 – 1.0.71",
        en: &[
            "Tray menu with Connect, Disconnect and a list of servers.",
            "Favourite servers, flags, and icon buttons with tooltips.",
            "Add from the clipboard; Hysteria2 links and JSON subscriptions.",
            "IPv6 through the tunnel, and protection that resumes after a crash or restart.",
            "A speed test you can stop, and you can connect while one runs.",
            "Faster connecting and a native network helper.",
        ],
        fa: &[
            "منوی نوار وظیفه با اتصال، قطع اتصال و فهرست سرورها.",
            "سرورهای مورد علاقه، پرچم‌ها و دکمه‌های آیکونی با راهنما.",
            "افزودن از کلیپ‌بورد؛ لینک‌های Hysteria2 و اشتراک JSON.",
            "IPv6 از داخل تونل و ادامهٔ محافظت پس از خرابی یا راه‌اندازی دوباره.",
            "تست سرعتی که می‌توان متوقفش کرد و اتصال همزمان با آن.",
            "اتصال سریع‌تر و ابزار شبکهٔ بومی.",
        ],
    },
    Release {
        version: "1.0.0 – 1.0.57",
        en: &[
            "Ember became a subscription-link client: paste a link, see the servers, test and sort them by speed.",
            "Connects through Xray-core for one app or the whole device, with a kill switch.",
        ],
        fa: &[
            "Ember به کلاینت لینک اشتراک تبدیل شد: لینک را بچسبانید، سرورها را ببینید، تست و بر اساس سرعت مرتب کنید.",
            "اتصال با Xray-core برای یک برنامه یا کل دستگاه، همراه با محافظ قطع اتصال.",
        ],
    },
];
