import Foundation

/// OutSystems unified error codes for the Contacts plugin, in the
/// `OS-PLUG-CONT-NNNN` format. The numeric suffix mirrors the legacy Cordova
/// `ContactError` codes so both stacks report equivalent failures.
public enum ContactsError: Error {
    case unknown
    case invalidArgument
    case timeout
    case pendingOperation
    case ioError
    case notSupported
    case operationCancelled
    case permissionDenied

    /// The `OS-PLUG-CONT-NNNN` code carried on `call.reject`.
    public var code: String {
        switch self {
        case .unknown: return "OS-PLUG-CONT-0000"
        case .invalidArgument: return "OS-PLUG-CONT-0001"
        case .timeout: return "OS-PLUG-CONT-0002"
        case .pendingOperation: return "OS-PLUG-CONT-0003"
        case .ioError: return "OS-PLUG-CONT-0004"
        case .notSupported: return "OS-PLUG-CONT-0005"
        case .operationCancelled: return "OS-PLUG-CONT-0006"
        case .permissionDenied: return "OS-PLUG-CONT-0020"
        }
    }

    /// Human-readable message paired with the code.
    public var message: String {
        switch self {
        case .unknown: return "An unknown error occurred."
        case .invalidArgument: return "Invalid arguments were provided."
        case .timeout: return "The operation timed out."
        case .pendingOperation: return "A pending operation is already in progress."
        case .ioError: return "An I/O error occurred while accessing contacts."
        case .notSupported: return "The operation is not supported on this device."
        case .operationCancelled: return "The operation was cancelled."
        case .permissionDenied: return "Contacts permission was denied."
        }
    }
}
