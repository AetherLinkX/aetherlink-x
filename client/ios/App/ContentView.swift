import SwiftUI
import NetworkExtension
import UIKit

struct ContentView: View {
    @EnvironmentObject var store: ProfileStore

    var body: some View {
        TabView {
            HomeView().tabItem { Label("Home", systemImage: "bolt.fill") }
            ProfilesView().tabItem { Label("Profiles", systemImage: "list.bullet") }
            SubscriptionsView().tabItem { Label("Subs", systemImage: "arrow.triangle.2.circlepath") }
            SettingsView().tabItem { Label("Settings", systemImage: "gearshape") }
        }
        // Reactive tint so theme changes apply live across all tabs.
        .tint((AppTheme(rawValue: store.settings.appTheme) ?? .cyber).accent)
    }
}

// ── Home ──────────────────────────────────────────────────────────────────────

struct HomeView: View {
    @EnvironmentObject var store: ProfileStore
    @EnvironmentObject var vpn: VPNManager
    @State private var errorText: String?

    private var accent: Color { (AppTheme(rawValue: store.settings.appTheme) ?? .cyber).accent }

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Spacer()
                Button(action: toggle) {
                    ZStack {
                        Circle()
                            .fill(vpn.isConnected ? Color.green : accent)
                            .frame(width: 168, height: 168)
                            .shadow(color: (vpn.isConnected ? Color.green : accent).opacity(0.4), radius: 24)
                        Image(systemName: "power").font(.system(size: 60, weight: .bold)).foregroundColor(.white)
                    }
                }
                .disabled(vpn.isBusy)
                Text(statusText).font(.title3).bold()
                Text(store.activeProfile?.name ?? "No profile selected").foregroundColor(.secondary)
                if let errorText {
                    Text(errorText).font(.footnote).foregroundColor(.red)
                        .multilineTextAlignment(.center).padding(.horizontal)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity)
            .navigationTitle("AetherLink X")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var statusText: String {
        switch vpn.status {
        case .connected:     return "Connected"
        case .connecting:    return "Connecting…"
        case .disconnecting: return "Disconnecting…"
        case .reasserting:   return "Reconnecting…"
        default:             return "Disconnected"
        }
    }

    private func toggle() {
        errorText = nil
        if vpn.isConnected { vpn.disconnect(); return }
        guard store.writeActiveConfig() else { errorText = "Select a profile first"; return }
        Task {
            do { try await vpn.connect() } catch { errorText = error.localizedDescription }
        }
    }
}

// ── Profiles ──────────────────────────────────────────────────────────────────

enum ProfileSheet: Identifiable {
    case link, new, scan
    case edit(VpnProfile)
    var id: String {
        switch self {
        case .link: return "link"
        case .new: return "new"
        case .scan: return "scan"
        case .edit(let p): return "edit-\(p.id)"
        }
    }
}

struct ProfilesView: View {
    @EnvironmentObject var store: ProfileStore
    @State private var sheet: ProfileSheet?
    @State private var linkText = ""
    @State private var addError: String?
    @State private var warpBusy = false

    var body: some View {
        NavigationView {
            List {
                // Manual profiles, then one section per subscription — so it's clear which
                // profile came from which sub (like the Android grouped list).
                if !manualProfiles.isEmpty {
                    Section("Manual") {
                        ForEach(manualProfiles) { profileRow($0) }
                    }
                }
                ForEach(subsWithProfiles) { sub in
                    Section("\(sub.name) · \(profiles(for: sub).count)") {
                        ForEach(profiles(for: sub)) { profileRow($0) }
                    }
                }
            }
            .overlay {
                if store.profiles.isEmpty {
                    Text("No profiles yet.\nTap + to add.")
                        .multilineTextAlignment(.center).foregroundColor(.secondary)
                }
                if warpBusy { ProgressView("Setting up WARP…") }
            }
            .navigationTitle("Profiles")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { pingAll() } label: { Image(systemName: "speedometer") }
                        .disabled(store.profiles.isEmpty)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { sheet = .new } label: { Label("Add manually", systemImage: "square.and.pencil") }
                        Button { sheet = .link } label: { Label("Paste link", systemImage: "link") }
                        Button { sheet = .scan } label: { Label("Scan QR", systemImage: "qrcode.viewfinder") }
                        Button { generateWarp() } label: { Label("Generate WARP", systemImage: "cloud") }
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(item: $sheet) { which in
                switch which {
                case .link: linkSheet
                case .new:  ProfileEditView(profile: VpnProfile(), isNew: true).environmentObject(store)
                case .edit(let p): ProfileEditView(profile: p, isNew: false).environmentObject(store)
                case .scan: scanSheet
                }
            }
        }
    }

    private var manualProfiles: [VpnProfile] { store.profiles.filter { $0.subscriptionId == nil } }
    private func profiles(for sub: Subscription) -> [VpnProfile] { store.profiles.filter { $0.subscriptionId == sub.id } }
    private var subsWithProfiles: [Subscription] { store.subscriptions.filter { !profiles(for: $0).isEmpty } }

    /// One profile row: tap to activate, with an always-visible "⋯" action menu.
    @ViewBuilder private func profileRow(_ p: VpnProfile) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(p.name).foregroundColor(.primary)
                Text("\(p.proto.rawValue) · \(p.address):\(p.port)")
                    .font(.caption2).foregroundColor(.secondary).lineLimit(1)
            }
            Spacer()
            if p.latencyMs >= 0 {
                Text("\(p.latencyMs)ms").font(.caption2)
                    .foregroundColor(p.latencyMs < 300 ? .green : .orange)
            }
            if store.activeId == p.id {
                Image(systemName: "checkmark.circle.fill").foregroundColor(.accentColor)
            }
            // Ping is its own visible button (WireGuard is UDP/local — no ping).
            if p.proto != .wireguard {
                Button { ping(p) } label: { Image(systemName: "speedometer").font(.title3) }
                    .buttonStyle(.borderless).foregroundColor(.secondary)
            }
            Menu {
                Button { sheet = .edit(p) } label: { Label("Edit", systemImage: "pencil") }
                Button { store.duplicate(p) } label: { Label("Duplicate", systemImage: "plus.square.on.square") }
                Button { UIPasteboard.general.string = ProfileCodec.encodeNative(p) } label: { Label("Copy link", systemImage: "doc.on.doc") }
                Button(role: .destructive) { store.remove(p) } label: { Label("Delete", systemImage: "trash") }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title3).foregroundColor(.secondary)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { store.setActive(p) }
        .swipeActions {
            Button(role: .destructive) { store.remove(p) } label: { Label("Delete", systemImage: "trash") }
        }
    }

    private var linkSheet: some View {
        NavigationView {
            Form {
                Section("Share link") {
                    TextField("vless:// vmess:// trojan:// …", text: $linkText)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                }
                Button("Paste from clipboard") { if let s = UIPasteboard.general.string { linkText = s } }
                if let addError { Text(addError).foregroundColor(.red).font(.footnote) }
            }
            .navigationTitle("Add from Link")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { resetLink() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { addLink() }
                        .disabled(linkText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func addLink() {
        let link = linkText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let p = ProfileCodec.decode(link) else { addError = "Couldn't parse that link"; return }
        store.add(p); resetLink()
    }

    private func resetLink() { linkText = ""; addError = nil; sheet = nil }

    private var scanSheet: some View {
        NavigationView {
            QRScannerView { code in
                if let p = ProfileCodec.decode(code) { store.add(p) }
                sheet = nil
            }
            .ignoresSafeArea()
            .navigationTitle("Scan QR")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { sheet = nil } }
            }
        }
    }

    private func generateWarp() {
        warpBusy = true
        Task {
            let p = await WarpProvisioner.register()
            await MainActor.run {
                warpBusy = false
                if let p { store.add(p) }
            }
        }
    }

    private func ping(_ p: VpnProfile) {
        Task {
            let ms = await Pinger.tcp(host: p.address, port: p.port)
            await MainActor.run { store.setLatency(p.id, ms) }
        }
    }

    private func pingAll() {
        for p in store.profiles where p.proto != .wireguard { ping(p) }
    }
}

// ── Subscriptions ──────────────────────────────────────────────────────────────

struct SubscriptionsView: View {
    @EnvironmentObject var store: ProfileStore
    @State private var showAdd = false
    @State private var name = ""
    @State private var url = ""
    @State private var busy = false

    var body: some View {
        NavigationView {
            List {
                ForEach(store.subscriptions) { sub in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(sub.name).font(.headline)
                            // Android-style chips.
                            HStack(spacing: 6) {
                                Chip("\(sub.profileCount) profiles", systemImage: "rectangle.stack", tint: .accentColor)
                                if sub.hasUsageInfo {
                                    Chip(usageText(sub), systemImage: "chart.bar.fill", tint: .purple)
                                }
                                if sub.hasExpiry {
                                    Chip(expiryText(sub), systemImage: "clock", tint: expiryTint(sub))
                                }
                            }
                        }
                        Spacer()
                        // Visible refresh button (not duplicated in the menu).
                        Button { refresh(sub) } label: { Image(systemName: "arrow.clockwise").font(.title3) }
                            .buttonStyle(.borderless).foregroundColor(.secondary)
                        Menu {
                            Button(role: .destructive) { store.removeSubscription(sub) } label: { Label("Delete", systemImage: "trash") }
                        } label: {
                            Image(systemName: "ellipsis.circle").font(.title3).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 2)
                    .swipeActions {
                        Button(role: .destructive) { store.removeSubscription(sub) } label: { Label("Delete", systemImage: "trash") }
                    }
                }
            }
            .overlay {
                if store.subscriptions.isEmpty {
                    Text("No subscriptions.\nTap + to add a URL.")
                        .multilineTextAlignment(.center).foregroundColor(.secondary)
                }
                if busy { ProgressView("Updating…") }
            }
            .navigationTitle("Subscriptions")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { updateAll() } label: { Image(systemName: "arrow.clockwise") }
                        .disabled(store.subscriptions.isEmpty || busy)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $showAdd) {
                NavigationView {
                    Form {
                        TextField("Name", text: $name)
                        TextField("https://…", text: $url)
                            .autocorrectionDisabled().textInputAutocapitalization(.never)
                    }
                    .navigationTitle("Add Subscription")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showAdd = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(busy ? "Adding…" : "Add") { addSub() }.disabled(url.isEmpty || busy)
                        }
                    }
                }
            }
        }
    }

    private func usageText(_ s: Subscription) -> String {
        var t = ByteFmt.string(s.usedBytes)
        if s.totalBytes > 0 { t += " / " + ByteFmt.string(s.totalBytes) }
        return t
    }

    private func expiryDays(_ s: Subscription) -> Int64 {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        return (s.expireEpochSec * 1000 - nowMs) / 86_400_000
    }

    private func expiryText(_ s: Subscription) -> String {
        let days = expiryDays(s)
        return days < 0 ? "Expired" : "\(days)d left"
    }

    private func expiryTint(_ s: Subscription) -> Color {
        let days = expiryDays(s)
        if days < 0 { return .red }
        if days <= 7 { return .orange }
        return .secondary
    }

    private func refresh(_ sub: Subscription) { Task { await store.refresh(sub) } }

    private func updateAll() {
        busy = true
        Task { await store.updateAll(); await MainActor.run { busy = false } }
    }

    private func addSub() {
        busy = true
        var sub = Subscription(name: name.isEmpty ? "Subscription" : name, url: url)
        Task {
            let result = await SubscriptionService.fetch(sub, settings: store.settings)
            await MainActor.run {
                sub.profileCount = result.profiles.count
                sub.lastUpdated = Date().timeIntervalSince1970
                if let u = result.usage {
                    sub.uploadBytes = u.upload; sub.downloadBytes = u.download
                    sub.totalBytes = u.total; sub.expireEpochSec = u.expire
                }
                store.subscriptions.append(sub)
                store.profiles.append(contentsOf: result.profiles.map { var p = $0; p.subscriptionId = sub.id; return p })
                store.save()
                busy = false; showAdd = false; name = ""; url = ""
            }
        }
    }
}

// ── Settings ──────────────────────────────────────────────────────────────────

extension ProfileStore {
    func binding<T>(_ kp: WritableKeyPath<AppSettings, T>) -> Binding<T> {
        Binding(get: { self.settings[keyPath: kp] },
                set: { self.settings[keyPath: kp] = $0; self.save() })
    }
    /// Edits a [String] setting as a comma-separated text field.
    func listBinding(_ kp: WritableKeyPath<AppSettings, [String]>) -> Binding<String> {
        Binding(
            get: { self.settings[keyPath: kp].joined(separator: ", ") },
            set: {
                self.settings[keyPath: kp] = $0
                    .split(whereSeparator: { $0 == "," || $0 == "\n" })
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty }
                self.save()
            }
        )
    }
}

struct SettingsView: View {
    var body: some View {
        NavigationView {
            List {
                NavigationLink { AppearanceSettings() } label: { Label("Appearance", systemImage: "paintbrush") }
                NavigationLink { RoutingSettings() } label: { Label("Routing & Privacy", systemImage: "arrow.triangle.branch") }
                NavigationLink { DnsSettings() } label: { Label("DNS", systemImage: "network") }
                NavigationLink { AntiDpiSettings() } label: { Label("Anti-DPI & Geo", systemImage: "shield.lefthalf.filled") }
                NavigationLink { SubscriptionSettings() } label: { Label("Subscriptions", systemImage: "arrow.triangle.2.circlepath") }
                NavigationLink { BackupSettings() } label: { Label("Backup", systemImage: "externaldrive") }
                NavigationLink { AboutSettings() } label: { Label("About", systemImage: "info.circle") }
            }
            .navigationTitle("Settings")
        }
    }
}

struct AppearanceSettings: View {
    @EnvironmentObject var store: ProfileStore
    var body: some View {
        Form {
            Section("Color theme") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(AppTheme.allCases) { t in
                            Button {
                                store.settings.appTheme = t.rawValue; store.save()
                            } label: {
                                VStack(spacing: 4) {
                                    Circle().fill(t.swatch).frame(width: 34, height: 34)
                                        .overlay(Circle().stroke(Color.primary,
                                                 lineWidth: store.settings.appTheme == t.rawValue ? 2.5 : 1)
                                                 .opacity(t == .amoled ? 1 : (store.settings.appTheme == t.rawValue ? 1 : 0)))
                                    Text(t.label).font(.caption2).foregroundColor(.secondary)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            Section("Dark mode") {
                Picker("Dark mode", selection: store.binding(\.darkMode)) {
                    ForEach(DarkMode.allCases) { Text($0.label).tag($0.rawValue) }
                }
                .pickerStyle(.segmented)
            }
        }
        .navigationTitle("Appearance")
    }
}

struct RoutingSettings: View {
    @EnvironmentObject var store: ProfileStore
    var body: some View {
        Form {
            Picker("Mode", selection: store.binding(\.routingMode)) {
                Text("Rule-based").tag(RoutingMode.ruleBased)
                Text("Global").tag(RoutingMode.global)
                Text("Bypass LAN").tag(RoutingMode.bypassLan)
            }
            Picker("Domain strategy", selection: store.binding(\.domainStrategy)) {
                ForEach(DomainStrategy.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            Toggle("Block ads", isOn: store.binding(\.blockAds))
            Toggle("Bypass China", isOn: store.binding(\.bypassChina))
            Toggle("FakeDNS", isOn: store.binding(\.enableFakeDns))
            Toggle("Route IPv6", isOn: store.binding(\.enableIpv6))
            Section("Custom domains") {
                TextField("Direct (comma separated)", text: store.listBinding(\.customDirectDomains)).autocorrectionDisabled()
                TextField("Blocked (comma separated)", text: store.listBinding(\.customBlockedDomains)).autocorrectionDisabled()
            }
        }
        .navigationTitle("Routing & Privacy")
    }
}

struct DnsSettings: View {
    @EnvironmentObject var store: ProfileStore
    var body: some View {
        Form {
            TextField("Remote DNS", text: store.binding(\.remoteDns)).autocorrectionDisabled().textInputAutocapitalization(.never)
            TextField("Direct DNS", text: store.binding(\.directDns)).autocorrectionDisabled().textInputAutocapitalization(.never)
        }
        .navigationTitle("DNS")
    }
}

struct AntiDpiSettings: View {
    @EnvironmentObject var store: ProfileStore
    @State private var geoBusy = false
    @State private var geoMsg: String?

    var body: some View {
        Form {
            Section("TLS fragment") {
                TextField("Packets", text: store.binding(\.fragmentPackets)).autocorrectionDisabled()
                TextField("Length", text: store.binding(\.fragmentLength)).autocorrectionDisabled()
                TextField("Interval", text: store.binding(\.fragmentInterval)).autocorrectionDisabled()
            }
            Section(header: Text("Geo files"), footer: Text("Set both URLs to override the bundled geoip/geosite. Reconnect to apply.")) {
                TextField("geoip.dat URL", text: store.binding(\.geoipUrl)).autocorrectionDisabled().textInputAutocapitalization(.never)
                TextField("geosite.dat URL", text: store.binding(\.geositeUrl)).autocorrectionDisabled().textInputAutocapitalization(.never)
                Button { updateGeo() } label: {
                    HStack { Text("Update geo files"); if geoBusy { Spacer(); ProgressView() } }
                }
                .disabled(store.settings.geoipUrl.isEmpty || store.settings.geositeUrl.isEmpty || geoBusy)
                if let geoMsg { Text(geoMsg).font(.caption).foregroundColor(.secondary) }
            }
        }
        .navigationTitle("Anti-DPI & Geo")
    }

    private func updateGeo() {
        geoBusy = true; geoMsg = nil
        Task {
            let ok = await GeoManager.update(store.settings)
            await MainActor.run { geoBusy = false; geoMsg = ok ? "Updated — reconnect to apply" : "Download failed" }
        }
    }
}

struct BackupSettings: View {
    @EnvironmentObject var store: ProfileStore
    @State private var importText = ""
    @State private var msg: String?

    var body: some View {
        Form {
            Section(header: Text("Export"), footer: Text("Copies every profile as share links.")) {
                Button("Copy all profiles") {
                    UIPasteboard.general.string = store.profiles.map { ProfileCodec.encodeNative($0) }.joined(separator: "\n")
                    msg = "Copied \(store.profiles.count) profiles"
                }
                .disabled(store.profiles.isEmpty)
            }
            Section(header: Text("Import"), footer: Text("Paste share links, one per line.")) {
                TextEditor(text: $importText).frame(height: 120).autocorrectionDisabled()
                Button("Import") {
                    let parsed = ProfileCodec.decodeSubscriptionBody(importText)
                    parsed.forEach { store.add($0) }
                    msg = "Imported \(parsed.count)"; importText = ""
                }
                .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if let msg { Text(msg).font(.caption).foregroundColor(.secondary) }
        }
        .navigationTitle("Backup")
    }
}

struct SubscriptionSettings: View {
    @EnvironmentObject var store: ProfileStore
    var body: some View {
        Form {
            TextField("User-Agent", text: store.binding(\.subscriptionUserAgent)).autocorrectionDisabled().textInputAutocapitalization(.never)
        }
        .navigationTitle("Subscriptions")
    }
}

struct AboutSettings: View {
    var body: some View {
        Form {
            HStack { Text("Version"); Spacer(); Text("2.0.2").foregroundColor(.secondary) }
            HStack { Text("Engine"); Spacer(); Text("Xray / SwiftyXrayKit").foregroundColor(.secondary) }
            HStack { Text("Based on"); Spacer(); Text("xStarRay (GPLv3)").foregroundColor(.secondary) }
        }
        .navigationTitle("About")
    }
}

/// Small rounded pill used for subscription stats (mirrors the Android chips).
struct Chip: View {
    let text: String
    var systemImage: String?
    var tint: Color

    init(_ text: String, systemImage: String? = nil, tint: Color = .secondary) {
        self.text = text; self.systemImage = systemImage; self.tint = tint
    }

    var body: some View {
        HStack(spacing: 3) {
            if let systemImage { Image(systemName: systemImage).font(.system(size: 9, weight: .semibold)) }
            Text(text).font(.caption2).fontWeight(.medium)
        }
        .padding(.horizontal, 8).padding(.vertical, 3)
        .background(tint.opacity(0.15))
        .foregroundColor(tint)
        .clipShape(Capsule())
    }
}

enum ByteFmt {
    static func string(_ bytes: Int64) -> String {
        let b = max(bytes, 0)
        if b < 1024 { return "\(b) B" }
        let units = ["KB", "MB", "GB", "TB"]
        var value = Double(b), i = -1
        while value >= 1024 && i < units.count - 1 { value /= 1024; i += 1 }
        return String(format: "%.1f %@", value, units[max(i, 0)])
    }
}
