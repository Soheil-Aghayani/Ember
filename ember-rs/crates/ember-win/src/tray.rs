//! Notification-area (tray) icon with a context menu, on its own thread with a hidden message-only window.
//!
//! The engine describes the menu as plain data ([MenuEntry]) and replaces it whenever the state changes (Connect or
//! Disconnect, the server list); a click comes back as a [TrayEvent] on a channel. A left click or double-click on the
//! icon is [TrayEvent::Open]. Ember's menu (Open, Connect/Disconnect, Servers submenu, Quit) is built by the caller.

use std::sync::mpsc::Receiver;

/// One line of the tray menu.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MenuEntry {
    /// A clickable line; `id` comes back in [TrayEvent::Command]. `id` must be non-zero.
    Item { id: u32, label: String, checked: bool },
    Separator,
    Submenu { label: String, entries: Vec<MenuEntry> },
}

impl MenuEntry {
    pub fn item(id: u32, label: impl Into<String>) -> Self {
        MenuEntry::Item { id, label: label.into(), checked: false }
    }

    pub fn checked(id: u32, label: impl Into<String>, checked: bool) -> Self {
        MenuEntry::Item { id, label: label.into(), checked }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TrayEvent {
    /// The icon itself was clicked: show the window.
    Open,
    /// A menu line with this id was chosen.
    Command(u32),
}

/// Every command id in [entries], in order (used by tests and by callers that map ids back to actions).
pub fn command_ids(entries: &[MenuEntry]) -> Vec<u32> {
    let mut ids = Vec::new();
    for entry in entries {
        match entry {
            MenuEntry::Item { id, .. } => ids.push(*id),
            MenuEntry::Separator => {}
            MenuEntry::Submenu { entries, .. } => ids.extend(command_ids(entries)),
        }
    }
    ids
}

/// Wide, NUL-terminated copy of [text], cut to [max] units including the NUL (a tooltip holds 127).
pub fn wide(text: &str, max: usize) -> Vec<u16> {
    let mut units: Vec<u16> = text.encode_utf16().take(max.saturating_sub(1)).collect();
    units.push(0);
    units
}

pub struct Tray {
    #[cfg(windows)]
    imp: imp::Handle,
}

impl Tray {
    /// Adds the icon and starts its thread. `on_event` is called on the tray thread after each event is queued, so the
    /// UI can wake up. None when the notification area is not available.
    pub fn spawn(tooltip: &str, menu: Vec<MenuEntry>, on_event: Box<dyn Fn() + Send>) -> Option<(Tray, Receiver<TrayEvent>)> {
        #[cfg(windows)]
        {
            let (handle, rx) = imp::spawn(tooltip, menu, on_event)?;
            Some((Tray { imp: handle }, rx))
        }
        #[cfg(not(windows))]
        {
            let _ = (tooltip, menu, on_event);
            None
        }
    }

    /// Replaces the context menu (takes effect the next time it opens).
    pub fn set_menu(&self, menu: Vec<MenuEntry>) {
        #[cfg(windows)]
        self.imp.set_menu(menu);
        #[cfg(not(windows))]
        let _ = menu;
    }

    /// Shows a native notification-area balloon. Clicking it opens Ember; contextual actions live in the tray menu.
    pub fn notify(&self, title: &str, message: &str) {
        #[cfg(windows)]
        self.imp.notify(title, message);
        #[cfg(not(windows))]
        let _ = (title, message);
    }
}

#[cfg(windows)]
impl Drop for Tray {
    fn drop(&mut self) {
        self.imp.close();
    }
}

#[cfg(windows)]
mod imp {
    use std::sync::atomic::{AtomicIsize, Ordering};
    use std::sync::mpsc::{channel, Receiver, Sender};
    use std::sync::{Mutex, OnceLock};
    use std::thread::JoinHandle;

    use windows::core::{w, PCWSTR};
    use windows::Win32::Foundation::{HINSTANCE, HWND, LPARAM, LRESULT, POINT, WPARAM};
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::UI::Shell::{
        ExtractIconW, Shell_NotifyIconW, NIF_ICON, NIF_INFO, NIF_MESSAGE, NIF_TIP, NIM_ADD, NIM_DELETE, NIM_MODIFY,
        NIIF_INFO, NIN_BALLOONUSERCLICK, NOTIFYICONDATAW,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
        AppendMenuW, CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyMenu, DestroyWindow, DispatchMessageW, GetCursorPos,
        GetMessageW, LoadIconW, PostMessageW, PostQuitMessage, RegisterClassW, RegisterWindowMessageW, SetForegroundWindow,
        TrackPopupMenu, TranslateMessage, HICON, HMENU, HWND_MESSAGE, IDI_APPLICATION, MF_CHECKED, MF_POPUP, MF_SEPARATOR, MF_STRING,
        MSG, TPM_RETURNCMD, TPM_RIGHTBUTTON, WINDOW_EX_STYLE, WINDOW_STYLE, WM_APP, WM_CLOSE, WM_DESTROY, WM_LBUTTONDBLCLK,
        WM_LBUTTONUP, WM_NULL, WM_RBUTTONUP, WNDCLASSW,
    };

    use super::{wide, MenuEntry, TrayEvent};

    const CALLBACK: u32 = WM_APP + 1;
    const ICON_ID: u32 = 1;

    /// State the window procedure needs; there is one tray per process.
    struct Shared {
        menu: Mutex<Vec<MenuEntry>>,
        events: Mutex<Sender<TrayEvent>>,
        wake: Mutex<Box<dyn Fn() + Send>>,
        taskbar_created: u32,
        icon: Mutex<IconData>,
    }

    /// NOTIFYICONDATAW holds raw handles that are only used on the tray thread; wrap it so `Shared` can sit in a static.
    struct IconData(NOTIFYICONDATAW);
    // SAFETY: the data is only read and written while the `icon` mutex is held, and the handles in it are process-wide.
    unsafe impl Send for IconData {}

    static SHARED: OnceLock<Shared> = OnceLock::new();

    pub struct Handle {
        hwnd: AtomicIsize,
        thread: Option<JoinHandle<()>>,
    }

    impl Handle {
        pub fn set_menu(&self, menu: Vec<MenuEntry>) {
            if let Some(shared) = SHARED.get() {
                if let Ok(mut current) = shared.menu.lock() {
                    *current = menu;
                }
            }
        }

        pub fn notify(&self, title: &str, message: &str) {
            let Some(shared) = SHARED.get() else { return };
            let Ok(icon) = shared.icon.lock() else { return };
            let mut data = NOTIFYICONDATAW {
                cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
                hWnd: icon.0.hWnd,
                uID: icon.0.uID,
                uFlags: NIF_INFO,
                dwInfoFlags: NIIF_INFO,
                ..Default::default()
            };
            let title = wide(title, data.szInfoTitle.len());
            let message = wide(message, data.szInfo.len());
            data.szInfoTitle[..title.len()].copy_from_slice(&title);
            data.szInfo[..message.len()].copy_from_slice(&message);
            // SAFETY: the icon is active and the notification data has its fixed buffers and NUL terminators populated.
            let _ = unsafe { Shell_NotifyIconW(NIM_MODIFY, &data) };
        }

        pub fn close(&mut self) {
            let raw = self.hwnd.swap(0, Ordering::SeqCst);
            if raw != 0 {
                // SAFETY: the handle belongs to the tray thread's window; PostMessageW is safe to call from any thread.
                let _ = unsafe { PostMessageW(HWND(raw as *mut _), WM_CLOSE, WPARAM(0), LPARAM(0)) };
            }
            if let Some(t) = self.thread.take() {
                let _ = t.join();
            }
        }
    }

    pub fn spawn(tooltip: &str, menu: Vec<MenuEntry>, on_event: Box<dyn Fn() + Send>) -> Option<(Handle, Receiver<TrayEvent>)> {
        let (tx, rx) = channel();
        let (ready_tx, ready_rx) = channel::<isize>();
        let tip = wide(tooltip, 128);
        let thread = std::thread::Builder::new()
            .name("ember-tray".into())
            .spawn(move || run(tip, menu, tx, on_event, ready_tx))
            .ok()?;
        match ready_rx.recv() {
            Ok(raw) if raw != 0 => Some((Handle { hwnd: AtomicIsize::new(raw), thread: Some(thread) }, rx)),
            _ => {
                let _ = thread.join();
                None
            }
        }
    }

    fn run(tip: Vec<u16>, menu: Vec<MenuEntry>, tx: Sender<TrayEvent>, wake: Box<dyn Fn() + Send>, ready: Sender<isize>) {
        // SAFETY: plain Win32 calls on this thread; every handle created here is destroyed before the thread ends.
        unsafe {
            let Ok(module) = GetModuleHandleW(None) else {
                let _ = ready.send(0);
                return;
            };
            let instance = HINSTANCE(module.0);
            let class = w!("EmberTrayWindow");
            let wc = WNDCLASSW { lpfnWndProc: Some(wnd_proc), hInstance: instance, lpszClassName: class, ..Default::default() };
            RegisterClassW(&wc);
            let Ok(hwnd) = CreateWindowExW(WINDOW_EX_STYLE(0), class, w!("Ember"), WINDOW_STYLE(0), 0, 0, 0, 0, HWND_MESSAGE, None, instance, None)
            else {
                let _ = ready.send(0);
                return;
            };

            let mut data = NOTIFYICONDATAW {
                cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
                hWnd: hwnd,
                uID: ICON_ID,
                uFlags: NIF_MESSAGE | NIF_ICON | NIF_TIP,
                uCallbackMessage: CALLBACK,
                hIcon: load_icon(instance),
                ..Default::default()
            };
            let n = tip.len().min(data.szTip.len());
            data.szTip[..n].copy_from_slice(&tip[..n]);
            let added = Shell_NotifyIconW(NIM_ADD, &data).as_bool();
            let shared = Shared {
                menu: Mutex::new(menu),
                events: Mutex::new(tx),
                wake: Mutex::new(wake),
                taskbar_created: RegisterWindowMessageW(w!("TaskbarCreated")),
                icon: Mutex::new(IconData(data)),
            };
            if !added || SHARED.set(shared).is_err() {
                let _ = DestroyWindow(hwnd);
                let _ = ready.send(0);
                return;
            }
            let _ = ready.send(hwnd.0 as isize);

            let mut msg = MSG::default();
            while GetMessageW(&mut msg, None, 0, 0).as_bool() {
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
    }

    /// Ember's own icon from the running exe (resource 0), or the stock application icon.
    ///
    /// # Safety
    /// Calls Win32 icon functions; [instance] must be this process's module handle.
    unsafe fn load_icon(instance: HINSTANCE) -> HICON {
        let exe: Vec<u16> = std::env::current_exe().map(|p| wide(&p.to_string_lossy(), 520)).unwrap_or_else(|_| vec![0]);
        let icon = ExtractIconW(instance, PCWSTR(exe.as_ptr()), 0);
        // ExtractIconW returns 0 (none) or 1 (not an executable) on failure.
        if icon.0 as usize > 1 {
            icon
        } else {
            LoadIconW(None, IDI_APPLICATION).unwrap_or_default()
        }
    }

    unsafe extern "system" fn wnd_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
        let Some(shared) = SHARED.get() else { return DefWindowProcW(hwnd, msg, wparam, lparam) };
        match msg {
            CALLBACK => {
                match (lparam.0 & 0xFFFF) as u32 {
                    WM_LBUTTONUP | WM_LBUTTONDBLCLK => send(shared, TrayEvent::Open),
                    NIN_BALLOONUSERCLICK => send(shared, TrayEvent::Open),
                    WM_RBUTTONUP => {
                        if let Some(id) = show_menu(shared, hwnd) {
                            send(shared, TrayEvent::Command(id));
                        }
                    }
                    _ => {}
                }
                LRESULT(0)
            }
            WM_CLOSE => {
                let _ = DestroyWindow(hwnd);
                LRESULT(0)
            }
            WM_DESTROY => {
                if let Ok(icon) = shared.icon.lock() {
                    let _ = Shell_NotifyIconW(NIM_DELETE, &icon.0);
                }
                PostQuitMessage(0);
                LRESULT(0)
            }
            m if m == shared.taskbar_created => {
                // Explorer restarted: the icon is gone, add it again.
                if let Ok(icon) = shared.icon.lock() {
                    let _ = Shell_NotifyIconW(NIM_ADD, &icon.0);
                }
                LRESULT(0)
            }
            _ => DefWindowProcW(hwnd, msg, wparam, lparam),
        }
    }

    fn send(shared: &Shared, event: TrayEvent) {
        if let Ok(tx) = shared.events.lock() {
            let _ = tx.send(event);
        }
        if let Ok(wake) = shared.wake.lock() {
            wake();
        }
    }

    /// Shows the menu at the cursor and returns the chosen command id.
    ///
    /// # Safety
    /// [hwnd] must be the tray window, called from its thread.
    unsafe fn show_menu(shared: &Shared, hwnd: HWND) -> Option<u32> {
        let entries = shared.menu.lock().ok()?.clone();
        let menu = build_menu(&entries)?;
        let mut point = POINT::default();
        let _ = GetCursorPos(&mut point);
        // The window must be foreground or the menu will not close when the user clicks elsewhere.
        let _ = SetForegroundWindow(hwnd);
        let chosen = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON, point.x, point.y, 0, hwnd, None).0;
        let _ = PostMessageW(hwnd, WM_NULL, WPARAM(0), LPARAM(0));
        let _ = DestroyMenu(menu);
        u32::try_from(chosen).ok().filter(|id| *id != 0)
    }

    /// # Safety
    /// Creates native menus; the caller destroys the returned menu (submenus go with it).
    unsafe fn build_menu(entries: &[MenuEntry]) -> Option<HMENU> {
        let menu = CreatePopupMenu().ok()?;
        for entry in entries {
            match entry {
                MenuEntry::Separator => {
                    let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
                }
                MenuEntry::Item { id, label, checked } => {
                    let label = wide(label, 256);
                    let flags = if *checked { MF_STRING | MF_CHECKED } else { MF_STRING };
                    let _ = AppendMenuW(menu, flags, *id as usize, PCWSTR(label.as_ptr()));
                }
                MenuEntry::Submenu { label, entries } => {
                    if let Some(sub) = build_menu(entries) {
                        let label = wide(label, 256);
                        let _ = AppendMenuW(menu, MF_POPUP | MF_STRING, sub.0 as usize, PCWSTR(label.as_ptr()));
                    }
                }
            }
        }
        Some(menu)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn command_ids_walk_submenus_in_order() {
        let menu = vec![
            MenuEntry::item(1, "Open Ember"),
            MenuEntry::item(2, "Connect"),
            MenuEntry::Separator,
            MenuEntry::Submenu {
                label: "Servers".into(),
                entries: vec![MenuEntry::checked(10, "Fastest", true), MenuEntry::Separator, MenuEntry::item(11, "Berlin")],
            },
            MenuEntry::item(3, "Quit"),
        ];
        assert_eq!(command_ids(&menu), [1, 2, 10, 11, 3]);
    }

    #[test]
    fn wide_is_nul_terminated_and_cut() {
        assert_eq!(wide("ab", 10), [97, 98, 0]);
        assert_eq!(wide("abcdef", 4), [97, 98, 99, 0]);
        assert_eq!(wide("", 4), [0]);
    }
}
