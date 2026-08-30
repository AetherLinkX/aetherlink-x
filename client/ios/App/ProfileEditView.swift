import SwiftUI
import UIKit

/// Full per-field profile editor — add or edit any protocol's fields. Mirrors the
/// Android manual editor's coverage.
struct ProfileEditView: View {
    @EnvironmentObject var store: ProfileStore
    @Environment(\.dismiss) private var dismiss

    @State var profile: VpnProfile
    let isNew: Bool

    private var needsTransport: Bool {
        ![.wireguard, .shadowsocks, .hysteria2, .socks5, .http, .anytls].contains(profile.proto)
    }
    private var needsSecurity: Bool {
        ![.wireguard, .shadowsocks, .socks5, .http].contains(profile.proto)
    }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextField("Name", text: $profile.name)
                    Picker("Protocol", selection: $profile.proto) {
                        ForEach(ProxyProtocol.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    hostField("Address", $profile.address)
                    HStack {
                        Text("Port")
                        Spacer()
                        TextField("443", text: portBinding)
                            .keyboardType(.numberPad).multilineTextAlignment(.trailing)
                    }
                }

                credentialsSection

                if needsTransport {
                    Section("Transport") {
                        Picker("Transport", selection: $profile.transport) {
                            ForEach(Transport.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                        }
                        if [.ws, .h2, .grpc, .xhttp].contains(profile.transport) {
                            field(profile.transport == .grpc ? "Service Name" : "Path", $profile.path)
                            field("Host", $profile.host)
                        }
                    }
                }

                if needsSecurity {
                    Section("Security") {
                        Picker("Security", selection: $profile.security) {
                            ForEach(Security.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                        }
                        if profile.security != .none {
                            field("SNI", $profile.sni)
                            field("Fingerprint", $profile.fingerprint)
                        }
                        if profile.security == .reality {
                            field("Public Key (pbk)", $profile.publicKey)
                            field("Short ID (sid)", $profile.shortId)
                        }
                        if profile.security == .tls || profile.security == .xtls {
                            Toggle("Allow insecure", isOn: $profile.allowInsecure)
                        }
                    }
                }

                Section("Advanced") {
                    if profile.proto == .aetherlinkx {
                        Toggle("Allow insecure ALX transport", isOn: $profile.alxAllowInsecureTransport)
                        field("AetherLink Turbo JSON", $profile.alxTurboJson)
                        field("ALX PQ/security JSON", $profile.alxSecurityJson)
                        field("ALX Stealth JSON", $profile.alxStealthJson)
                    }
                    Toggle("Multiplexing (mux)", isOn: $profile.muxEnabled)
                    Toggle("TLS fragment (anti-DPI)", isOn: $profile.fragmentEnabled)
                }

                if !isNew {
                    Section {
                        Button("Copy share link") {
                            UIPasteboard.general.string = ProfileCodec.encodeNative(profile)
                        }
                        Button("Set as active") { store.setActive(profile); dismiss() }
                    }
                }
            }
            .navigationTitle(isNew ? "Add Profile" : "Edit Profile")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(profile.address.isEmpty)
                }
            }
        }
    }

    @ViewBuilder private var credentialsSection: some View {
        Section("Credentials") {
            switch profile.proto {
            case .aetherlinkx, .vmess, .vless:
                field("UUID", $profile.uuid)
                if profile.proto == .aetherlinkx {
                    secureField("AetherLink X secret", $profile.alxSecret)
                }
                if profile.proto == .vmess {
                    Picker("Cipher", selection: $profile.vmessSecurity) {
                        ForEach(["auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"], id: \.self) { Text($0).tag($0) }
                    }
                }
            case .trojan, .anytls:
                secureField("Password", $profile.uuid)
            case .tuic:
                field("UUID", $profile.uuid)
                secureField("Password", $profile.ssPassword)
            case .shadowsocks:
                field("Cipher", $profile.ssMethod)
                secureField("Password", $profile.ssPassword)
            case .hysteria2:
                secureField("Password", $profile.hystPassword)
                field("Obfs", $profile.hystObfs)
            case .socks5, .http:
                field("user:password (optional)", $profile.uuid)
            case .wireguard:
                secureField("Private Key", $profile.wgPrivateKey)
                field("Peer Public Key", $profile.wgPeerPublicKey)
                field("Endpoint", $profile.wgEndpoint)
                field("Reserved", $profile.wgReserved)
            }
        }
    }

    private func field(_ label: String, _ binding: Binding<String>) -> some View {
        TextField(label, text: binding)
            .autocorrectionDisabled().textInputAutocapitalization(.never)
    }

    private func secureField(_ label: String, _ binding: Binding<String>) -> some View {
        SecureField(label, text: binding)
    }

    /// A host/IP field: strips anything that can't appear in a hostname or IP
    /// (spaces, emoji, etc.) while still allowing domain text.
    private func hostField(_ label: String, _ binding: Binding<String>) -> some View {
        // `/` kept so WireGuard CIDR addresses (10.0.0.2/32) still edit; emoji/spaces stripped.
        let allowed = CharacterSet(charactersIn:
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-_:[]/")
        return TextField(label, text: Binding(
            get: { binding.wrappedValue },
            set: { binding.wrappedValue = String($0.unicodeScalars.filter { allowed.contains($0) }) }
        ))
        .keyboardType(.URL)
        .autocorrectionDisabled().textInputAutocapitalization(.never)
    }

    /// Port as a digits-only string bound to the Int field.
    private var portBinding: Binding<String> {
        Binding(
            get: { String(profile.port) },
            set: { profile.port = Int($0.filter { $0.isNumber }.prefix(5)) ?? 0 }
        )
    }

    private func save() {
        if profile.name.isEmpty { profile.name = "Unnamed" }
        if isNew { store.add(profile) } else { store.update(profile) }
        dismiss()
    }
}
