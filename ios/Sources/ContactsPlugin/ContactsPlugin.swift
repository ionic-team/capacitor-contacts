import Capacitor
import Contacts
import ContactsUI
import Foundation

/// Capacitor bridge for the Contacts plugin.
///
/// Permission model is **implicit**: there are no `checkPermissions` /
/// `requestPermissions` methods. `find`, `save` and `remove` request Contacts
/// access before touching the store. On iOS 18+ Limited Access counts as
/// granted. `pickContact` and `pickContactField` present the system picker,
/// which requires no permission at all.
@objc(ContactsPlugin)
public class ContactsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ContactsPlugin"
    public let jsName = "Contacts"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "find", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "save", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pickContact", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pickContactField", returnType: CAPPluginReturnPromise)
    ]

    private let implementation = Contacts()

    /// Call awaiting the result of the contact picker UI.
    private var pickCall: CAPPluginCall?

    /// Delegate of the in-flight `pickContactField` picker. Held here because
    /// `CNContactPickerViewController.delegate` is a weak reference.
    private var fieldPickerDelegate: FieldPickerDelegate?

    // MARK: - find

    @objc func find(_ call: CAPPluginCall) {
        guard let fields = call.getArray("fields") as? [String], !fields.isEmpty else {
            reject(call, .invalidArgument)
            return
        }
        let filter = call.getString("filter") ?? ""
        let multiple = call.getBool("multiple") ?? false
        let desired = (call.getArray("desiredFields") as? [String]) ?? []
        let hasPhoneNumber = call.getBool("hasPhoneNumber") ?? false

        withAccess(call) {
            do {
                let contacts = try self.implementation.search(
                    fields: fields,
                    filter: filter,
                    multiple: multiple,
                    desiredFields: desired,
                    hasPhoneNumber: hasPhoneNumber
                )
                call.resolve(["contacts": contacts])
            } catch {
                self.reject(call, error as? ContactsError ?? .unknown)
            }
        }
    }

    // MARK: - save

    @objc func save(_ call: CAPPluginCall) {
        guard let contact = call.getObject("contact") else {
            reject(call, .invalidArgument)
            return
        }
        withAccess(call) {
            do {
                let saved = try self.implementation.save(contact: contact)
                call.resolve(saved)
            } catch {
                self.reject(call, error as? ContactsError ?? .unknown)
            }
        }
    }

    // MARK: - remove

    @objc func remove(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), !id.isEmpty else {
            reject(call, .invalidArgument)
            return
        }
        withAccess(call) {
            do {
                try self.implementation.remove(id: id)
                call.resolve()
            } catch {
                self.reject(call, error as? ContactsError ?? .unknown)
            }
        }
    }

    // MARK: - pickContact

    /// Presents `CNContactPickerViewController`, which runs out of process and
    /// needs no Contacts permission, the recommended flow under iOS 18+
    /// Limited Access (the full contact list is shown and only the picked
    /// contact's data is returned to the app).
    @objc func pickContact(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard self.pickCall == nil else {
                self.reject(call, .pendingOperation)
                return
            }
            guard let viewController = self.bridge?.viewController else {
                self.reject(call, .unknown)
                return
            }
            self.pickCall = call
            let picker = CNContactPickerViewController()
            picker.delegate = self
            self.present(picker, from: viewController)
        }
    }

    // MARK: - pickContactField

    /// Presents the system picker restricted to one kind of property (phone,
    /// email or address), so the user taps a single value and only that value
    /// plus the contact's name is returned. Needs no Contacts permission.
    ///
    /// - Parameter call: carries the `field` option. Rejects with
    ///   `OS-PLUG-CONT-0001` when it is missing or unknown.
    @objc func pickContactField(_ call: CAPPluginCall) {
        guard let field = call.getString("field"),
              let key = implementation.pickerPropertyKey(forField: field) else {
            reject(call, .invalidArgument)
            return
        }
        DispatchQueue.main.async {
            guard self.pickCall == nil else {
                self.reject(call, .pendingOperation)
                return
            }
            guard let viewController = self.bridge?.viewController else {
                self.reject(call, .unknown)
                return
            }
            self.pickCall = call
            let delegate = FieldPickerDelegate(
                onSelect: { [weak self] property in
                    guard let self = self, let call = self.finishPick() else { return }
                    if let contact = self.implementation.pickedPropertyToDictionary(
                        contact: property.contact,
                        key: property.key,
                        label: property.label,
                        value: property.value as Any
                    ) {
                        call.resolve(contact)
                    } else {
                        self.reject(call, .unknown)
                    }
                },
                onCancel: { [weak self] in
                    guard let self = self, let call = self.finishPick() else { return }
                    self.reject(call, .operationCancelled)
                }
            )
            self.fieldPickerDelegate = delegate
            let picker = CNContactPickerViewController()
            picker.displayedPropertyKeys = [key]
            picker.predicateForSelectionOfProperty = NSPredicate(format: "key == %@", key)
            picker.delegate = delegate
            self.present(picker, from: viewController)
        }
    }

    /// Clears the in-flight pick state.
    ///
    /// - Returns: the call the pick was holding.
    private func finishPick() -> CAPPluginCall? {
        let call = pickCall
        pickCall = nil
        fieldPickerDelegate = nil
        return call
    }

    /// Presents from the top-most controller so an already-presented modal
    /// doesn't make present() fail silently (stuck pick state).
    ///
    /// - Parameters:
    ///   - picker: the picker to show.
    ///   - viewController: the controller to start looking for a presenter from.
    private func present(_ picker: CNContactPickerViewController, from viewController: UIViewController) {
        var presenter = viewController
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        presenter.present(picker, animated: true)
    }

    // MARK: - Implicit permission handling

    /// Ensures Contacts access (full, or limited on iOS 18+), then runs
    /// `work` off the main thread. Rejects with `OS-PLUG-CONT-0020` when
    /// access is refused.
    private func withAccess(_ call: CAPPluginCall, work: @escaping () -> Void) {
        if implementation.isAuthorized() {
            DispatchQueue.global(qos: .userInitiated).async { work() }
            return
        }
        implementation.requestAccess { granted in
            if granted {
                DispatchQueue.global(qos: .userInitiated).async { work() }
            } else {
                self.reject(call, .permissionDenied)
            }
        }
    }

    private func reject(_ call: CAPPluginCall, _ error: ContactsError) {
        call.reject(error.message, error.code)
    }
}

// MARK: - CNContactPickerDelegate

extension ContactsPlugin: CNContactPickerDelegate {
    public func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
        let call = pickCall
        pickCall = nil
        guard let call = call else { return }
        // When the store is readable, re-fetch by identifier for the full key
        // set. Without access (picker needs none), map the picker's contact
        // directly; the mapper guards every key with `isKeyAvailable`.
        if implementation.isAuthorized(), let full = implementation.getContact(byId: contact.identifier) {
            call.resolve(full)
        } else {
            call.resolve(implementation.contactToDictionary(contact, desiredFields: []))
        }
    }

    public func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
        let call = pickCall
        pickCall = nil
        call?.reject(ContactsError.operationCancelled.message, ContactsError.operationCancelled.code)
    }
}

/// Delegate for `pickContactField`. Implements only the property-selection
/// callback: also implementing `didSelect contact:` would make the picker
/// select whole contacts and never report a property.
private final class FieldPickerDelegate: NSObject, CNContactPickerDelegate {
    private let onSelect: (CNContactProperty) -> Void
    private let onCancel: () -> Void

    /// - Parameters:
    ///   - onSelect: called with the property the user tapped.
    ///   - onCancel: called when the user dismisses the picker.
    init(onSelect: @escaping (CNContactProperty) -> Void, onCancel: @escaping () -> Void) {
        self.onSelect = onSelect
        self.onCancel = onCancel
    }

    func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
        onSelect(contactProperty)
    }

    func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
        onCancel()
    }
}
