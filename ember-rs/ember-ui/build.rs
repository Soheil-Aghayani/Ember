fn main() {
    slint_build::compile("ui/app.slint").unwrap();
    // the icon of ember-ui.exe in Explorer, the taskbar and the Start menu
    #[cfg(windows)]
    {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("ui/assets/ember.ico");
        res.set("ProductName", "Ember");
        res.set("FileDescription", "Ember");
        res.compile().expect("could not embed the icon");
    }
}
