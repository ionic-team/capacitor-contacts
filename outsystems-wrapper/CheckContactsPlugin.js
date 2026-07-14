// JS node for the plugin-availability check.
// Output: IsAvailable (Boolean)
$parameters.IsAvailable =
    (typeof window !== "undefined" &&
        ((window.CapacitorPlugins && typeof window.CapacitorPlugins.Contacts !== "undefined") ||
         (window.Capacitor && window.Capacitor.Plugins && typeof window.Capacitor.Plugins.Contacts !== "undefined"))) ||
    (typeof cordova !== "undefined" && typeof navigator !== "undefined");
