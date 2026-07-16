import Capacitor
import Contacts
import ContactsUI
import Foundation

/// Capacitor bridge for the Contacts plugin.
///
/// Permission model is **implicit**: there are no `checkPermissions` /
/// `requestPermissions` methods. `find`, `save` and `remove` request Contacts
/// access before touching the store. On iOS 18+ Limited Access counts as
/// granted. `pickContact` presents the system picker, which requires no
/// permission at all.
@objc(ContactsPlugin)
public class ContactsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ContactsPlugin"
    public let jsName = "Contacts"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "find", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "save", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pickContact", returnType: CAPPluginReturnPromise)
    ]

    private let implementation = Contacts()

    /// Call awaiting the result of the contact picker UI.
    private var pickCall: CAPPluginCall?

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
    /// needs no Contacts permission — the recommended flow under iOS 18+
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
            // Present from the top-most controller so an already-presented
            // modal doesn't make present() fail silently (stuck pick state).
            var presenter = viewController
            while let presented = presenter.presentedViewController {
                presenter = presented
            }
            presenter.present(picker, animated: true)
        }
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
        // directly — the mapper guards every key with `isKeyAvailable`.
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
