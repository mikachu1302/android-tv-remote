import SwiftUI
import TVRemoteCore

/// Everything about the app row in one place: your apps (drag to reorder, remove) and the apps to add.
/// A row in the lists does nothing when tapped; only its buttons change anything.
struct AppShortcutsScreen: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let onBack: () -> Void
    @State private var custom = false

    var body: some View {
        let apps = vm.apps
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenHeader(title: "App shortcuts", onBack: onBack)

                SettingsSection(title: "Your apps (\(apps.count))")
                if apps.isEmpty {
                    Text("None yet. Add some below.").font(term.font(13)).foregroundColor(term.dim)
                } else {
                    Text("Drag the handle to reorder.").font(term.font(12)).foregroundColor(term.dim).padding(.bottom, 4)
                    ReorderableApps()
                }

                // Apps already in your list are not offered again: they are above, with their Remove button.
                // Whatever is open on the TV right now is offered too, even when it is not in the catalog.
                if vm.tvState.on != false, let pkg = vm.tvState.appPackage {
                    let open = shortcutForPackage(pkg, label: vm.tvState.appLabel)
                    if !apps.contains(where: { $0.link == open.link }) {
                        SettingsSection(title: "Open on your TV now")
                        CatalogRow(app: open, subtitle: "Currently open") { vm.addApp(open) }
                    }
                }

                SettingsSection(title: "Add apps")
                let available = appCatalog.filter { entry in !apps.contains { $0.link == entry.link } }
                if available.isEmpty { Text("All apps are in your list.").font(term.font(13)).foregroundColor(term.dim) }
                ForEach(available, id: \.link) { app in CatalogRow(app: app, subtitle: nil) { vm.addApp(app) } }

                SettingsSection(title: "Something else")
                TButton(kind: .outlined, action: { custom = true }) { Text("Add by package name or link…").frame(maxWidth: .infinity) }
                TButton(kind: .text, action: vm.resetApps) { Text("Reset to defaults").frame(maxWidth: .infinity) }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
        }
        .clipped() // content scrolls under the status bar otherwise
        .sheet(isPresented: $custom) { CustomAppSheet() }
    }
}

private struct CatalogRow: View {
    @Environment(\.term) private var term
    let app: AppShortcut
    let subtitle: String?
    let onAdd: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            AppTile(name: app.name, size: 44)
            VStack(alignment: .leading, spacing: 0) {
                Text(app.name).font(term.font(16, bold: true)).foregroundColor(term.text).lineLimit(1)
                if let subtitle { Text(subtitle).font(term.font(12)).foregroundColor(term.dim) }
            }
            Spacer(minLength: 0)
            TButton(kind: .outlined, action: onAdd) { Text("Add") }
                .accessibilityIdentifier("add-" + app.name)
        }
        .padding(.vertical, 4)
    }
}

/// The rows of your apps. Drag a row by its handle: it follows the finger and the list reorders under it.
private struct ReorderableApps: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    @State private var dragging: String?
    @State private var startIndex = 0
    @State private var dragY: CGFloat = 0
    private let rowHeight: CGFloat = 60

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(vm.apps.enumerated()), id: \.element.link) { index, app in
                row(app, index)
                    .frame(height: rowHeight)
                    .background(dragging == app.link ? term.surface : Color.clear)
                    .offset(y: dragging == app.link ? dragY - CGFloat(index - startIndex) * rowHeight : 0)
                    .zIndex(dragging == app.link ? 1 : 0)
            }
        }
    }

    private func row(_ app: AppShortcut, _ index: Int) -> some View {
        HStack(spacing: 12) {
            Glyph(name: "grip", color: term.dim, size: 24)
                .frame(width: 44, height: rowHeight)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            if dragging == nil {
                                dragging = app.link
                                startIndex = vm.apps.firstIndex(of: app) ?? index
                            }
                            guard dragging == app.link, let current = vm.apps.firstIndex(of: app) else { return }
                            dragY = value.translation.height
                            let steps = Int((value.translation.height / rowHeight).rounded())
                            let target = min(max(startIndex + steps, 0), vm.apps.count - 1)
                            if target != current { vm.moveApp(from: current, to: target) }
                        }
                        .onEnded { _ in
                            withAnimation(.easeOut(duration: 0.15)) { dragY = 0 }
                            dragging = nil
                        }
                )
                .accessibilityLabel("Reorder \(app.name)")
                .accessibilityAction(named: "Move up") { if index > 0 { vm.moveApp(from: index, to: index - 1) } }
                .accessibilityAction(named: "Move down") { if index < vm.apps.count - 1 { vm.moveApp(from: index, to: index + 1) } }
            AppTile(name: app.name, size: 40)
            Text(app.name).font(term.font(16, bold: true)).foregroundColor(term.text).lineLimit(1)
            Spacer(minLength: 0)
            TButton(kind: .text, action: { vm.removeApp(at: vm.apps.firstIndex(of: app) ?? index) }) { Text("Remove") }
                .accessibilityIdentifier("remove-" + app.name)
        }
    }
}

private struct CustomAppSheet: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var link = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Add an app").font(term.font(22, bold: true)).foregroundColor(term.text)
            Text("Type the Android package name of an app on the TV (like com.example.app) or a link it opens.")
                .font(term.font(13)).foregroundColor(term.dim)
            field("Name", text: $name)
            field("Package name or link", text: $link)
            Spacer(minLength: 0)
            HStack {
                TButton(kind: .text, action: { dismiss() }) { Text("Cancel") }
                Spacer()
                TButton(action: {
                    vm.addApp(AppShortcut(name: name.trimmingCharacters(in: .whitespaces), link: customLink(link)))
                    dismiss()
                }) { Text("Add") }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || link.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(term.bg.ignoresSafeArea())
        .environment(\.term, term)
        .presentationDetents([.medium])
    }

    private func field(_ title: String, text: Binding<String>) -> some View {
        TextField(title, text: text)
            .font(term.font(16))
            .foregroundColor(term.text)
            .tint(term.primary)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .padding(.horizontal, 12)
            .frame(height: 44)
            .overlay(term.controlShape.strokeBorder(term.border, lineWidth: 1))
    }
}
