#[cfg(windows)]
mod windows;

fn main() {
    #[cfg(windows)]
    std::process::exit(windows::entry());
    #[cfg(not(windows))]
    {
        eprintln!("ember-helper is only supported on Windows");
        std::process::exit(1);
    }
}
