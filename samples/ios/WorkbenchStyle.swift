import SwiftUI

struct MonoCard<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.system(.caption, design: .monospaced).weight(.semibold)).tracking(1.5).foregroundStyle(.secondary)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(Color(uiColor: .systemBackground), in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.primary.opacity(0.16), lineWidth: 1))
    }
}

struct MonoButtonStyle: ButtonStyle {
    var primary = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 15).padding(.vertical, 12)
            .foregroundStyle(primary ? Color(uiColor: .systemBackground) : Color.primary)
            .background(primary ? Color.primary : Color.primary.opacity(configuration.isPressed ? 0.12 : 0.035),
                        in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.primary.opacity(primary ? 0 : 0.18)))
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.35)
    }
}

struct MonoTextFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .padding(12)
            .background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.primary.opacity(0.16)))
    }
}

@MainActor
struct ModelLibraryView: View {
    @ObservedObject var store: ModelStore
    @Binding var selectedId: String
    let editable: Bool

    var body: some View {
        MonoCard("MODEL LIBRARY / DEVICE STORAGE") {
            Text("Download once. Run here.").font(.title2.weight(.bold))
            Text("Selecting or publishing never downloads weights. Choose Download explicitly; verified files remain in Application Support across sessions and relaunches. CPU inference speed and memory use depend on your device.")
                .font(.subheadline).foregroundStyle(.secondary)
            ForEach(store.models) { model in
                VStack(alignment: .leading, spacing: 12) {
                    Button {
                        selectedId = model.id
                    } label: {
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: selected(model) ? "largecircle.fill.circle" : "circle")
                            VStack(alignment: .leading, spacing: 5) {
                                Text(model.title).font(.headline)
                                Text(model.summary).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!editable)
                    .accessibilityLabel("Select \(model.title)")
                    .accessibilityAddTraits(selected(model) ? .isSelected : [])
                    Text("\(model.sizeLabel) · \(model.parameters) · \(model.quantization)\n\(model.languages) · \(model.license)")
                        .font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary)
                    if let source = URL(string: model.source) { Link("Model source & license", destination: source).font(.caption) }
                    if store.cachedIds.contains(model.id) {
                        Label("Cached / SHA-256 verified", systemImage: "checkmark.circle").font(.subheadline.weight(.medium))
                        Button("Delete cached weights") { store.delete(model) }.disabled(!editable)
                    } else if store.downloadingId == model.id {
                        ProgressView(value: Double(store.receivedBytes), total: Double(model.downloadBytes))
                        Text("\(store.phase) · \(ByteCountFormatter.string(fromByteCount: store.receivedBytes, countStyle: .file)) / \(model.sizeLabel)")
                            .font(.caption).monospacedDigit()
                        Button("Cancel download") { store.cancelDownload() }
                    } else {
                        Text("Not downloaded").font(.caption).foregroundStyle(.secondary)
                        Button("Download · \(model.sizeLabel)") { store.download(model) }
                            .buttonStyle(MonoButtonStyle(primary: true))
                            .disabled(store.downloadingId != nil)
                    }
                }
                .padding(16)
                .background(Color.primary.opacity(0.025), in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.primary.opacity(selected(model) ? 0.65 : 0.12)))
            }
            if store.downloadingId == nil {
                Text(store.phase).font(.caption).foregroundStyle(.secondary)
            }
            if let error = store.error {
                Label("Model operation needs attention", systemImage: "exclamationmark.triangle").font(.headline)
                Text(error).font(.caption).textSelection(.enabled)
            }
            Text("No weights are bundled. A downloaded model is required before Publish becomes available. Uninstalling the app removes its cache.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func selected(_ model: LocalModelSpec) -> Bool {
        model.id == (selectedId.isEmpty ? store.models.first?.id : selectedId)
    }
}
