# Windows app-specific VPN bypass

## Status

Feasibility work only. Ember does not currently bypass VPN traffic by Windows application.

## Current behavior

Windows whole-device mode sends traffic through the Wintun tunnel using system routes. The existing **Skip the VPN** list accepts domains, pasted website links, IPv4 addresses, and IPv4 ranges. Ember resolves a domain when connecting, then the elevated helper adds a physical-network route for each resulting destination range.

These are destination routes. A Windows route cannot distinguish which process created a connection, so a route added for an app's remote IP also affects other apps connecting to that same IP. A manual `.exe` picker backed by dynamic IP routes would therefore look app-specific while bypassing traffic for every app that shares those destinations.

## Feasible approaches

### Windows Filtering Platform

Windows Filtering Platform's Application Layer Enforcement (ALE) can identify a connection by application identity. That provides the process-aware classification Ember needs. Diverting the selected app's traffic around Ember's default tunnel route is a separate step: a custom redirect path may require a kernel callout driver and a user-mode proxy/service. This adds a privileged driver lifecycle, signing and distribution work, compatibility risk, and a new failure path for the kill switch.

### Windows VPN platform

Windows has a VPN plug-in and traffic-filter model with application identifiers. Using it would mean integrating Ember's custom Mozilla tunnel with the Windows VPN platform rather than relying only on Wintun plus global routes. This is a larger Windows networking architecture change and needs an isolated prototype before committing to it.

## Recommendation

Do not add an `.exe` selector to the current route-based implementation. First build a disposable Windows VM prototype that proves selected-process traffic can use the physical network while another process connecting to the same destination remains inside the VPN. The prototype must not alter the owner's daily Windows network.

Do not call the feature app-specific until it also has a tested policy for TCP, UDP, IPv4, IPv6, helper/service child processes, DNS, disconnects, crashes, and kill-switch behavior. Keep URL/domain/IP exclusions as the supported desktop method in the meantime.

## Acceptance criteria for a future implementation

- The user can identify an installed desktop app without manually finding its IP addresses.
- Only that app's traffic bypasses the VPN, including when another app connects to the same remote IP.
- Non-selected traffic remains in the VPN and cannot leak when the tunnel drops if the kill switch is on.
- App updates, executable path changes, helper restarts, and Windows reboots do not leave stale policies behind.
- Runtime behavior is validated in a disposable Windows environment before testing on the owner's PC.

## References

- [Microsoft: Application Layer Enforcement](https://learn.microsoft.com/en-us/windows/win32/fwp/application-layer-enforcement--ale-)
- [Microsoft: ALE connection redirection and callout drivers](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/using-proxied-connections-tracking)
- [Microsoft: kernel-mode driver signing requirements](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/kernel-mode-code-signing-requirements--windows-vista-and-later-)
- [Microsoft: VPN security features and per-app traffic filters](https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/vpn/vpn-security-features)
- [Microsoft: Windows VPN application triggers](https://learn.microsoft.com/en-us/windows/access-protection/vpn/vpn-auto-trigger-profile)
