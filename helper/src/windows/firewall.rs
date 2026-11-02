use ember_helper::Result;
use std::{marker::PhantomData, mem::ManuallyDrop, rc::Rc};
use windows::{
    core::{Interface, BSTR},
    Win32::{
        Foundation::VARIANT_TRUE,
        NetworkManagement::WindowsFirewall::*,
        System::{
            Com::*,
            Ole::{IEnumVARIANT, SafeArrayCreateVector, SafeArrayPutElement},
            Variant::*,
        },
    },
};

struct Apartment(PhantomData<Rc<()>>);
impl Apartment {
    fn new() -> Result<Self> {
        // SAFETY: the helper uses COM only on its main thread; successful init is balanced by Drop.
        unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) }
            .ok()
            .map_err(|e| e.to_string())?;
        Ok(Self(PhantomData))
    }
}
impl Drop for Apartment {
    fn drop(&mut self) {
        // SAFETY: runs on the initializing thread, after all owned COM interfaces have dropped.
        unsafe {
            CoUninitialize();
        }
    }
}

pub struct Firewall {
    rules: INetFwRules,
    _apartment: Apartment,
}

fn interfaces(names: &[String]) -> windows::core::Result<VARIANT> {
    // INetFwRule::Interfaces takes a SAFEARRAY of VARIANTs that each hold a BSTR (what scripting hosts send);
    // a SAFEARRAY of plain BSTRs is rejected with E_INVALIDARG ("The parameter is incorrect").
    // SAFETY: a vector of VARIANT elements. Ownership transfers immediately to the VARIANT,
    // whose Drop calls VariantClear on every success/error path, destroying the SAFEARRAY.
    let array = unsafe { SafeArrayCreateVector(VT_VARIANT, 0, names.len() as u32) };
    if array.is_null() {
        return Err(windows::core::Error::from_hresult(
            windows::Win32::Foundation::E_OUTOFMEMORY,
        ));
    }
    let variant = VARIANT {
        Anonymous: VARIANT_0 {
            Anonymous: ManuallyDrop::new(VARIANT_0_0 {
                vt: VARENUM(VT_ARRAY.0 | VT_VARIANT.0),
                wReserved1: 0,
                wReserved2: 0,
                wReserved3: 0,
                Anonymous: VARIANT_0_0_0 { parray: array },
            }),
        },
    };
    for (index, name) in names.iter().enumerate() {
        let element = VARIANT::from(BSTR::from(name.as_str()));
        // SAFETY: index is in bounds. For VT_VARIANT this API takes a pointer to the VARIANT and copies it
        // (including the BSTR), so `element` can be dropped afterwards.
        unsafe {
            SafeArrayPutElement(array, &(index as i32), (&element as *const VARIANT).cast())
        }?;
    }
    Ok(variant)
}

/// Whether the firewall accepts `name` as a rule interface. It rejects adapters it does not manage (for example the
/// Hyper-V / WSL virtual switch) with "Element not found", and one such name fails a whole list, so names are
/// checked one at a time.
fn accepts_interface(name: &str) -> bool {
    // SAFETY: COM is initialized by Firewall::new; the rule is a throwaway object that is never added.
    unsafe {
        let Ok(rule) = CoCreateInstance::<_, INetFwRule>(&NetFwRule, None, CLSCTX_INPROC_SERVER) else {
            return false;
        };
        let Ok(list) = interfaces(&[name.to_string()]) else {
            return false;
        };
        rule.SetInterfaces(&list).is_ok()
    }
}

fn next_rule(enumerator: &IEnumVARIANT) -> windows::core::Result<Option<INetFwRule>> {
    let mut values = [VARIANT::default()];
    let mut fetched = 0;
    // SAFETY: one writable VARIANT and count; values owns and clears the returned reference.
    unsafe { enumerator.Next(&mut values, &mut fetched) }.ok()?;
    if fetched == 0 {
        return Ok(None);
    }
    let value = &values[0];
    let rule = if value.vt() == VT_DISPATCH {
        // SAFETY: vt proves the union contains an Option<IDispatch>; cast AddRefs the interface.
        unsafe {
            value
                .Anonymous
                .Anonymous
                .Anonymous
                .pdispVal
                .as_ref()
                .ok_or_else(|| {
                    windows::core::Error::from_hresult(windows::Win32::Foundation::E_POINTER)
                })?
                .cast()?
        }
    } else {
        windows::core::IUnknown::try_from(value)?.cast()?
    };
    Ok(Some(rule))
}

impl Firewall {
    pub fn new() -> Result<Self> {
        let apartment = Apartment::new()?;
        // SAFETY: COM initialized on this thread; policy object is a known in-process COM class.
        let policy: INetFwPolicy2 =
            unsafe { CoCreateInstance(&NetFwPolicy2, None, CLSCTX_INPROC_SERVER) }
                .map_err(|e| e.to_string())?;
        // SAFETY: valid policy interface on this apartment.
        let rules = unsafe { policy.Rules() }.map_err(|e| e.to_string())?;
        Ok(Self {
            rules,
            _apartment: apartment,
        })
    }

    /// The subset of `names` the firewall accepts as rule interfaces, in the same order.
    pub fn usable_interfaces(&self, names: &[String]) -> Vec<String> {
        names.iter().filter(|name| accepts_interface(name)).cloned().collect()
    }

    pub fn remove_ember(&self, mut log: impl FnMut(&str)) -> Result<()> {
        // SAFETY: valid COM rules interface and a QI for the documented enumeration interface.
        let enumerator: IEnumVARIANT = unsafe { self.rules._NewEnum() }
            .and_then(|e| e.cast())
            .map_err(|e| e.to_string())?;
        let mut names = Vec::new();
        while let Some(rule) = next_rule(&enumerator).map_err(|e| e.to_string())? {
            // SAFETY: property reads on an enumerated firewall rule.
            let name = match unsafe { rule.Name() } {
                Ok(name) => name,
                Err(e) => {
                    log(&format!("cleanup (firewall rule name): {e}"));
                    continue;
                }
            };
            // An unreadable Grouping must not prevent removing a legacy Ember-named rule.
            let group = unsafe { rule.Grouping() }.unwrap_or_default().to_string();
            if group.eq_ignore_ascii_case("Ember")
                || name.to_string().to_lowercase().starts_with("ember ")
            {
                names.push(name);
            }
        }
        // Snapshot names first: mutating the collection during enumeration can skip rules.
        for name in names {
            // SAFETY: removal by copied BSTR name; continue after individual cleanup errors.
            if let Err(e) = unsafe { self.rules.Remove(&name) } {
                log(&format!("cleanup (firewall rule): {e}"));
            }
        }
        Ok(())
    }

    pub fn block(
        &self,
        name: &str,
        remote: &str,
        protocol: i32,
        port: Option<&str>,
        names: Option<&[String]>,
    ) -> Result<()> {
        let rule = build_rule(name, remote, protocol, port, names).map_err(|e| e.to_string())?;
        let name = BSTR::from(name);
        // SAFETY: known rule collection. Hold the old object so a failed replacement can restore it.
        let old = unsafe { self.rules.Item(&name) }.ok();
        if old.is_some() {
            // SAFETY: name identifies the existing rule we just looked up.
            unsafe { self.rules.Remove(&name) }.map_err(|e| e.to_string())?;
        }
        // SAFETY: all properties are validated/set before making the new rule active.
        if let Err(error) = unsafe { self.rules.Add(&rule) } {
            if let Some(old) = old {
                // SAFETY: restore the prior rule if adding its replacement failed.
                unsafe { self.rules.Add(&old) }.map_err(|rollback| {
                    format!("{error}; restoring previous firewall rule failed: {rollback}")
                })?;
            }
            return Err(error.to_string());
        }
        Ok(())
    }
}

fn build_rule(
    name: &str,
    remote: &str,
    protocol: i32,
    port: Option<&str>,
    names: Option<&[String]>,
) -> windows::core::Result<INetFwRule> {
    // SAFETY: COM initialized; this rule is not added until all properties succeed. BSTRs
    // and the optional SAFEARRAY remain owned by Rust and COM copies their property values.
    unsafe {
        let rule: INetFwRule = CoCreateInstance(&NetFwRule, None, CLSCTX_INPROC_SERVER)?;
        rule.SetName(&BSTR::from(name))?;
        rule.SetGrouping(&BSTR::from("Ember"))?;
        rule.SetDirection(NET_FW_RULE_DIR_OUT)?;
        rule.SetAction(NET_FW_ACTION_BLOCK)?;
        rule.SetProfiles(NET_FW_PROFILE2_ALL.0)?;
        rule.SetProtocol(protocol)?;
        rule.SetRemoteAddresses(&BSTR::from(remote))?;
        if let Some(port) = port {
            rule.SetRemotePorts(&BSTR::from(port))?;
        }
        if let Some(names) = names {
            rule.SetInterfaces(&interfaces(names)?)?;
        }
        rule.SetEnabled(VARIANT_TRUE)?;
        Ok(rule)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rules_build_without_an_interface_list() {
        let _apartment = Apartment::new().unwrap();
        let rule = build_rule("Ember test (never added)", "0.0.0.0-9.255.255.255", 256, None, None);
        assert!(rule.is_ok(), "{:?}", rule.err());
    }

    #[test]
    fn unknown_adapters_are_not_usable_but_the_list_format_is_accepted() {
        let _apartment = Apartment::new().unwrap();
        // The firewall answers "Element not found" for a name it does not know (not "The parameter is incorrect",
        // which is what a malformed list gets).
        assert!(!accepts_interface("Ember test adapter that does not exist"));
        let rule: INetFwRule =
            unsafe { CoCreateInstance(&NetFwRule, None, CLSCTX_INPROC_SERVER) }.unwrap();
        let list = interfaces(&["Ember test adapter that does not exist".to_string()]).unwrap();
        let error = unsafe { rule.SetInterfaces(&list) }.unwrap_err();
        assert_eq!(error.code().0 as u32, 0x8007_0490, "{error:?}");
    }
}
